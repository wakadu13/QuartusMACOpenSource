/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.gui

import de.neemann.digital.core.element.Keys
import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.gui.Main
import de.neemann.digital.gui.components.modification.ModifyAttribute
import de.neemann.digital.lang.Lang
import de.neemann.digital.quartus.Board
import de.neemann.digital.quartus.PinPlanner
import de.neemann.digital.undo.Modifications
import de.neemann.gui.ErrorMessage
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * The pin planner: shows the DE0 board, the inputs and outputs of the circuit are assigned to the switches,
 * buttons, LEDs and displays by a click or by drag and drop. The pins are written to the attribute "pin number"
 * of the inputs and outputs, so the circuit can be shown on the virtual board and exported to Quartus.
 *
 * @param main the main window
 */
class PinPlannerDialog private constructor(private val main: Main) : JDialog(main, Lang.get("menu_pinPlanner"), true) {
    private val planner = PinPlanner.create(main.circuitComponent.circuit, Board.DE0)
    private val model = SignalModel()
    private val table = JTable(model)
    private val board = BoardPanel()
    private val status = JLabel(" ")

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.selectionModel.addListSelectionListener { board.repaint() }
        table.rowHeight = 24
        table.dragEnabled = true
        table.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent?) = COPY
            override fun createTransferable(c: JComponent?): Transferable = StringSelection(table.selectedRow.toString())
        }
        table.columnModel.getColumn(0).preferredWidth = 120
        table.columnModel.getColumn(1).preferredWidth = 70
        table.columnModel.getColumn(2).preferredWidth = 40
        table.columnModel.getColumn(3).preferredWidth = 140
        table.columnModel.getColumn(4).preferredWidth = 130
        if (planner.signals.isNotEmpty()) table.setRowSelectionInterval(0, 0)

        val left = JPanel(BorderLayout())
        left.add(JScrollPane(table), BorderLayout.CENTER)
        val clear = JButton(Lang.get("btn_ppClear"))
        clear.toolTipText = Lang.get("btn_ppClear_tt")
        clear.addActionListener {
            selectedSignal()?.let { planner.clear(it) }
            changed()
        }
        left.add(clear, BorderLayout.SOUTH)
        left.preferredSize = Dimension(520, 420)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, JScrollPane(board))
        split.resizeWeight = 0.4
        contentPane.add(split, BorderLayout.CENTER)

        val south = JPanel(BorderLayout())
        status.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        status.text = Lang.get("msg_ppHelp")
        south.add(status, BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
        val show = JButton(Lang.get("menu_showOnBoard"))
        show.toolTipText = Lang.get("btn_ppShow_tt")
        show.addActionListener { if (apply()) showOnBoard() }
        buttons.add(show)
        buttons.add(JButton(Lang.get("cancel")).also { it.addActionListener { dispose() } })
        buttons.add(JButton(Lang.get("ok")).also { it.addActionListener { if (apply()) dispose() } })
        south.add(buttons, BorderLayout.EAST)
        contentPane.add(south, BorderLayout.SOUTH)
        pack()
        setLocationRelativeTo(main)
    }

    private fun selectedSignal(): PinPlanner.Signal? = planner.signals.getOrNull(table.selectedRow)

    private fun changed() {
        model.fireTableDataChanged()
        board.repaint()
    }

    private fun assign(s: PinPlanner.Signal, pin: String) {
        when (planner.assign(s, pin)) {
            PinPlanner.Result.OK -> status.text = Lang.get("msg_ppAssigned_NN", s.label, planner.resources(s))
            PinPlanner.Result.WRONG_DIRECTION -> status.text =
                if (s.input) Lang.get("msg_ppNeedsInput_N", s.label) else Lang.get("msg_ppNeedsOutput_N", s.label)
            PinPlanner.Result.NOT_ENOUGH_RESOURCES -> status.text = Lang.get("msg_ppNotEnough_N", s.bits)
            PinPlanner.Result.NO_RESOURCE -> {}
        }
        val row = planner.signals.indexOf(s)
        changed()
        table.setRowSelectionInterval(row, row)
    }

    /* writes the pins to the circuit, undoable */
    private fun apply(): Boolean {
        val b = Modifications.Builder<Circuit>(Lang.get("menu_pinPlanner"))
        var n = 0
        for (s in planner.signals) {
            val ve = s.element ?: continue
            if (ve.elementAttributes.get(Keys.PINNUMBER) != s.pinNumber) {
                b.add(ModifyAttribute(ve, Keys.PINNUMBER, s.pinNumber))
                n++
            }
        }
        if (n > 0) main.circuitComponent.modify(b.build())
        return true
    }

    private fun showOnBoard() {
        val file = main.baseFileName
        if (file == null || !file.exists()) {
            JOptionPane.showMessageDialog(this, Lang.get("msg_saveBeforeBoard"))
            return
        }
        try {
            if (main.isStateChanged) main.saveChanges()
            val f = Board.createBoardFor(main.circuitComponent.circuit, file)
            if (f == null) JOptionPane.showMessageDialog(this, Lang.get("err_noPinOnBoard_N", "DE0"))
            else {
                dispose()
                main.open(f, true)
            }
        } catch (e: Exception) {
            ErrorMessage().addCause(e).show(this)
        }
    }

    private inner class SignalModel : AbstractTableModel() {
        override fun getRowCount() = planner.signals.size
        override fun getColumnCount() = 5
        override fun getColumnName(c: Int): String = when (c) {
            0 -> Lang.get("msg_ppSignal")
            1 -> Lang.get("msg_ppDirection")
            2 -> Lang.get("msg_ppBits")
            3 -> Lang.get("msg_ppPins")
            else -> Lang.get("msg_ppResources")
        }

        override fun isCellEditable(r: Int, c: Int) = c == 3

        override fun getValueAt(r: Int, c: Int): Any {
            val s = planner.signals[r]
            return when (c) {
                0 -> s.label
                1 -> if (s.clock) Lang.get("msg_ppClock") else if (s.input) Lang.get("msg_ppInput") else Lang.get("msg_ppOutput")
                2 -> s.bits
                3 -> s.pinNumber
                else -> planner.resources(s)
            }
        }

        override fun setValueAt(v: Any?, r: Int, c: Int) {
            planner.setPinNumber(planner.signals[r], v?.toString() ?: "")
            changed()
        }
    }

    /* the drawing of the board, the resources can be clicked and are drop targets */
    private inner class BoardPanel : JComponent() {
        private val shapes = ArrayList<Pair<Shape, String>>()

        init {
            toolTipText = ""
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val pin = pinAt(e.point) ?: return
                    val s = selectedSignal()
                    if (s == null || SwingUtilities.isRightMouseButton(e) || e.isControlDown || e.isMetaDown) {
                        // selects the signal assigned to the resource
                        planner.owner(pin)?.let { (o, _) -> planner.signals.indexOf(o).let { table.setRowSelectionInterval(it, it) } }
                        return
                    }
                    assign(s, pin)
                }
            })
            transferHandler = object : TransferHandler() {
                override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.stringFlavor)
                override fun importData(support: TransferSupport): Boolean {
                    val row = (support.transferable.getTransferData(DataFlavor.stringFlavor) as String).toIntOrNull() ?: return false
                    val pin = pinAt(support.dropLocation.dropPoint) ?: return false
                    val s = planner.signals.getOrNull(row) ?: return false
                    assign(s, pin)
                    return true
                }
            }
        }

        private fun pinAt(p: Point): String? = shapes.lastOrNull { it.first.contains(p) }?.second

        override fun getToolTipText(e: MouseEvent): String? {
            val pin = pinAt(e.point) ?: return null
            val o = planner.owner(pin)
            val res = planner.resourceName(pin) + " = PIN_" + pin
            return if (o == null) res else res + " → " + o.first.label + if (o.first.bits > 1) "[${o.second}]" else ""
        }

        override fun getPreferredSize() = Dimension(640, 470)

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            shapes.clear()
            g2.color = PCB
            g2.fillRoundRect(10, 10, 620, 450, 24, 24)
            g2.color = Color.WHITE
            g2.font = font.deriveFont(Font.BOLD, 16f)
            g2.drawString("Terasic DE0  —  Cyclone III EP3C16F484C6", 30, 38)
            g2.font = font.deriveFont(11f)

            // the displays HEX3 .. HEX0
            val b = planner.board
            for (d in b.hex.indices.reversed()) {
                val x = 40 + (b.hex.size - 1 - d) * 120
                drawDisplay(g2, x, 60, d)
            }
            // the LEDs LEDG9 .. LEDG0
            g2.color = Color.WHITE
            for (i in b.leds.indices.reversed()) {
                val x = 40 + (b.leds.size - 1 - i) * 56
                val shape = Ellipse2D.Double(x + 12.0, 225.0, 20.0, 20.0)
                fill(g2, shape, b.leds[i], LED_OFF, LED_ON)
                label(g2, "LEDG$i", x + 22, 262)
            }
            // the switches SW9 .. SW0
            for (i in b.switches.indices.reversed()) {
                val x = 40 + (b.switches.size - 1 - i) * 56
                val shape = Rectangle2D.Double(x + 10.0, 290.0, 24.0, 44.0)
                fill(g2, shape, b.switches[i], SWITCH, SWITCH_USED)
                g2.color = Color.DARK_GRAY
                g2.fillRect(x + 14, 312, 16, 18)
                label(g2, "SW$i", x + 22, 350)
            }
            // the buttons BUTTON2 .. BUTTON0 and the clocks
            for (i in b.buttons.indices.reversed()) {
                val x = 300 + (b.buttons.size - 1 - i) * 80
                val shape = Rectangle2D.Double(x.toDouble(), 380.0, 40.0, 40.0)
                fill(g2, shape, b.buttons[i], BUTTON, SWITCH_USED)
                g2.color = Color.DARK_GRAY
                g2.fillOval(x + 10, 390, 20, 20)
                label(g2, "BUTTON$i", x + 20, 438)
            }
            b.clocks.keys.forEachIndexed { i, pin ->
                val x = 40 + i * 120
                val shape = Rectangle2D.Double(x.toDouble(), 380.0, 90.0, 40.0)
                fill(g2, shape, pin, OSC, SWITCH_USED)
                g2.color = Color.WHITE
                g2.drawString("50 MHz", x + 24, 405)
                label(g2, if (i == 0) "CLOCK_50" else "CLOCK_50_2", x + 45, 438)
            }
        }

        private fun drawDisplay(g2: Graphics2D, x: Int, y: Int, d: Int) {
            g2.color = Color(30, 30, 30)
            g2.fillRoundRect(x, y, 90, 130, 8, 8)
            val segs = planner.board.hex[d]
            // a, b, c, d, e, f, g as rectangles, dp as a circle
            val r = listOf(
                Rectangle2D.Double(x + 22.0, y + 12.0, 40.0, 9.0),
                Rectangle2D.Double(x + 63.0, y + 20.0, 9.0, 42.0),
                Rectangle2D.Double(x + 63.0, y + 70.0, 9.0, 42.0),
                Rectangle2D.Double(x + 22.0, y + 111.0, 40.0, 9.0),
                Rectangle2D.Double(x + 12.0, y + 70.0, 9.0, 42.0),
                Rectangle2D.Double(x + 12.0, y + 20.0, 9.0, 42.0),
                Rectangle2D.Double(x + 22.0, y + 61.0, 40.0, 9.0),
                Ellipse2D.Double(x + 76.0, y + 110.0, 9.0, 9.0)
            )
            for (s in segs.indices) fill(g2, r[s], segs[s], SEG_OFF, SEG_ON)
            label(g2, "HEX$d", x + 45, y + 150)
        }

        /* fills a resource: colored if assigned, highlighted if assigned to the selected signal */
        private fun fill(g2: Graphics2D, shape: Shape, pin: String, off: Color, on: Color) {
            shapes.add(shape to pin)
            val o = planner.owner(pin)
            g2.color = when {
                o == null -> off
                o.first === selectedSignal() -> SELECTED
                else -> on
            }
            g2.fill(shape)
            g2.color = Color.BLACK
            g2.draw(shape)
            if (o != null) {
                val text = o.first.label + if (o.first.bits > 1) o.second.toString() else ""
                val bounds = shape.bounds
                g2.color = Color.WHITE
                val w = g2.fontMetrics.stringWidth(text)
                g2.fillRect(bounds.centerX.toInt() - w / 2 - 2, bounds.y - 13, w + 4, 13)
                g2.color = Color.BLACK
                g2.drawString(text, bounds.centerX.toInt() - w / 2, bounds.y - 3)
            }
        }

        private fun label(g2: Graphics2D, text: String, cx: Int, y: Int) {
            g2.color = Color.WHITE
            g2.drawString(text, cx - g2.fontMetrics.stringWidth(text) / 2, y)
        }
    }

    companion object {
        private val PCB = Color(20, 90, 60)
        private val LED_OFF = Color(60, 90, 60)
        private val LED_ON = Color(80, 230, 80)
        private val SEG_OFF = Color(80, 30, 30)
        private val SEG_ON = Color(240, 60, 60)
        private val SWITCH = Color(200, 200, 200)
        private val BUTTON = Color(180, 180, 180)
        private val SWITCH_USED = Color(120, 180, 255)
        private val OSC = Color(110, 110, 110)
        private val SELECTED = Color(255, 170, 0)

        /**
         * Shows the pin planner for the circuit of the main window.
         *
         * @param main the main window
         */
        @JvmStatic
        fun show(main: Main) {
            val d = PinPlannerDialog(main)
            if (d.planner.signals.isEmpty())
                JOptionPane.showMessageDialog(main, Lang.get("msg_ppNoSignals"))
            else d.isVisible = true
        }
    }
}
