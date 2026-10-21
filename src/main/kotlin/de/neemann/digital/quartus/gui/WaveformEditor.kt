/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.gui

import de.neemann.digital.gui.Main
import de.neemann.digital.lang.Lang
import de.neemann.digital.quartus.VwfFile
import de.neemann.digital.quartus.Waveform
import de.neemann.gui.ErrorMessage
import de.neemann.gui.MyFileChooser
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.io.File
import java.io.IOException
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The waveform editor, like the Waveform Editor of Quartus: the input signals are drawn with the mouse,
 * the outputs are calculated by a functional or a timing simulation of the circuit of the main window.
 * The waveforms are stored in Quartus vector waveform files (vwf).
 *
 * @param main the main window which shows the circuit
 */
class WaveformEditor private constructor(private val main: Main) : JFrame(Lang.get("menu_waveformEditor")) {
    private var waveform: Waveform
    private var vwfFile: File? = null
    private var cellWidth = 24.0
    private var selected = -1
    private var selStart = -1
    private var selEnd = -1
    private var cursorTime = -1.0

    private val endField = JTextField(8)
    private val gridField = JTextField(8)
    private val modeBox = JComboBox(arrayOf(Lang.get("wf_functional"), Lang.get("wf_timing")))
    private val delayField = JTextField("10 ns", 6)
    private val radixBox = JComboBox(arrayOf(Lang.get("wf_binary"), Lang.get("wf_hex"), Lang.get("wf_unsigned"), Lang.get("wf_signed")))
    private val status = JLabel(" ")
    private val names = NamePanel()
    private val ruler = Ruler()
    private val view = WaveView()
    private val scroll = JScrollPane(view)

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        iconImages = main.iconImages
        waveform = Waveform.create(main.circuitComponent.circuit, main.library, 1000.0, 50.0)
        // like in the booklet: the inputs start with clocks of different periods, so all combinations are shown
        val inputs = waveform.traces.count { it.input && !it.clock && it.bits == 1 }
        waveform.endTime = waveform.gridSize * (1 shl min(inputs + 1, 9)).coerceAtLeast(16)
        for (t in waveform.traces) if (t.clock) t.clock(0, waveform.stepCount, 1, 0)
        var period = 2
        for (t in waveform.traces) if (t.input && !t.clock && t.bits == 1) {
            t.clock(0, waveform.stepCount, period, 0)
            period *= 2
        }
        updateFields()

        scroll.setRowHeaderView(names)
        scroll.setColumnHeaderView(ruler)
        scroll.horizontalScrollBar.unitIncrement = 20
        contentPane.add(createToolBar(), BorderLayout.NORTH)
        contentPane.add(scroll, BorderLayout.CENTER)
        val south = JPanel(BorderLayout())
        south.add(status, BorderLayout.CENTER)
        south.add(JLabel(Lang.get("msg_wfHelp")), BorderLayout.EAST)
        south.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        contentPane.add(south, BorderLayout.SOUTH)

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) = fit()
            override fun windowActivated(e: WindowEvent?) = refreshFromCircuit(false)
            override fun windowClosed(e: WindowEvent?) {
                EDITORS.remove(main)
            }
        })
        size = Dimension(1100, 160 + 34 * waveform.traces.size.coerceIn(3, 14))
        setLocationRelativeTo(main)
        simulate()
    }

    private fun createToolBar(): JComponent {
        val tb1 = JToolBar()
        tb1.isFloatable = false
        val tb2 = JToolBar()
        tb2.isFloatable = false
        tb1.add(button(Lang.get("btn_wfOpen"), Lang.get("btn_wfOpen_tt")) { openVwf() })
        tb1.add(button(Lang.get("btn_wfSave"), Lang.get("btn_wfSave_tt")) { saveVwf() })
        tb1.addSeparator()
        tb1.add(button("0", Lang.get("btn_wfLow_tt")) { apply { t, a, b -> t.set(a, b, 0) } })
        tb1.add(button("1", Lang.get("btn_wfHigh_tt")) { apply { t, a, b -> t.set(a, b, -1) } })
        tb1.add(button(Lang.get("btn_wfInvert"), Lang.get("btn_wfInvert_tt")) {
            apply { t, a, b -> for (i in a until b) t.set(i, i + 1, t.step(i).inv()) }
        })
        tb1.add(button(Lang.get("btn_wfClock"), Lang.get("btn_wfClock_tt")) { clockDialog() })
        tb1.add(button(Lang.get("btn_wfCount"), Lang.get("btn_wfCount_tt")) { countDialog() })
        tb1.add(button(Lang.get("btn_wfValue"), Lang.get("btn_wfValue_tt")) { valueDialog(-1) })
        tb1.addSeparator()
        tb1.add(radixBox)
        radixBox.selectedIndex = 1
        radixBox.maximumSize = radixBox.preferredSize
        radixBox.addActionListener { repaintAll() }
        tb1.add(button("+", Lang.get("btn_wfZoomIn_tt")) { zoom(1.5) })
        tb1.add(button("−", Lang.get("btn_wfZoomOut_tt")) { zoom(1 / 1.5) })
        tb1.add(button("↔", Lang.get("btn_wfFit_tt")) { fit() })
        tb1.addSeparator()
        tb1.add(button(Lang.get("btn_wfRefresh"), Lang.get("btn_wfRefresh_tt")) { refreshFromCircuit(true) })
        tb1.add(button(Lang.get("btn_wfCopyTest"), Lang.get("btn_wfCopyTest_tt")) { copyTest() })

        tb2.add(JLabel(Lang.get("wf_endTime") + " "))
        tb2.add(endField)
        tb2.add(JLabel(" " + Lang.get("wf_gridSize") + " "))
        tb2.add(gridField)
        val timeListener = ActionListener { setTimes() }
        endField.addActionListener(timeListener)
        gridField.addActionListener(timeListener)
        tb2.addSeparator()
        tb2.add(modeBox)
        tb2.add(JLabel(" " + Lang.get("wf_gateDelay") + " "))
        tb2.add(delayField)
        delayField.isEnabled = false
        modeBox.addActionListener {
            delayField.isEnabled = modeBox.selectedIndex == 1
            simulate()
        }
        delayField.addActionListener { simulate() }
        tb2.add(button(Lang.get("btn_wfSimulate"), Lang.get("btn_wfSimulate_tt")) { setTimes() })
        tb2.add(Box.createHorizontalGlue())
        for (f in listOf(endField, gridField, delayField)) f.maximumSize = f.preferredSize
        modeBox.maximumSize = modeBox.preferredSize
        val p = JPanel(GridLayout(2, 1))
        p.add(tb1)
        p.add(tb2)
        return p
    }

    private fun button(text: String, tip: String, action: () -> Unit): JButton {
        val b = JButton(text)
        b.toolTipText = tip
        b.isFocusable = false
        b.addActionListener { action() }
        return b
    }

    private fun updateFields() {
        endField.text = Waveform.formatTime(waveform.endTime)
        gridField.text = Waveform.formatTime(waveform.gridSize)
    }

    private fun setTimes() {
        val end = Waveform.parseTime(endField.text)
        val grid = Waveform.parseTime(gridField.text)
        if (end == null || end <= 0 || grid == null || grid <= 0) {
            status.text = Lang.get("msg_wfInvalidTime")
            return
        }
        if (end / grid > Waveform.MAX_STEPS) {
            status.text = Lang.get("msg_wfTooManySteps_N", Waveform.MAX_STEPS)
            return
        }
        if (end != waveform.endTime || grid != waveform.gridSize) {
            // keep the waveforms in time: resample the steps to the new grid
            val old = waveform.traces.map { it to it.steps.copyOf() }
            val oldGrid = waveform.gridSize
            waveform.gridSize = grid
            waveform.endTime = end
            for ((t, steps) in old) for (i in 0 until waveform.stepCount) {
                val k = ((i * grid + grid / 2) / oldGrid).toInt()
                t.steps[i] = if (k < steps.size) steps[k] else steps.lastOrNull() ?: 0
            }
            selStart = -1
            fit()
        }
        updateFields()
        simulate()
    }

    private fun gateDelay(): Double {
        if (modeBox.selectedIndex == 0) return 0.0
        return Waveform.parseTime(delayField.text)?.takeIf { it > 0 } ?: 10.0
    }

    private fun simulate() {
        try {
            main.ensureModelIsStopped()
            waveform.simulate(main.circuitComponent.circuit, main.library, gateDelay())
            val msg = ArrayList<String>()
            val missing = waveform.messages.filter { it != Waveform.LONG_DELAY }
            if (missing.isNotEmpty()) msg.add(Lang.get("msg_wfMissing_N", missing.joinToString(", ")))
            if (waveform.messages.contains(Waveform.LONG_DELAY)) msg.add(Lang.get("msg_wfLongDelay"))
            status.text = if (msg.isEmpty()) Lang.get("msg_wfSimulated_N", waveform.stepCount) else msg.joinToString("  ")
            status.foreground = if (msg.isEmpty()) Color(0, 110, 0) else Color(170, 90, 0)
        } catch (e: Exception) {
            waveform.clearResults()
            status.text = Lang.get("msg_wfError_N", e.message ?: e.javaClass.simpleName)
            status.foreground = Color.RED
        }
        repaintAll()
    }

    private fun refreshFromCircuit(always: Boolean) {
        try {
            val w = Waveform.create(main.circuitComponent.circuit, main.library, waveform.endTime, waveform.gridSize)
            val same = w.traces.map { it.name to it.bits } == waveform.traces.map { it.name to it.bits }
            if (same && !always) return
            w.takeValuesFrom(waveform)
            waveform = w
            selected = -1
            simulate()
        } catch (e: Exception) {
            if (always) ErrorMessage(Lang.get("btn_wfRefresh")).addCause(e).show(this)
        }
    }

    /* applies an operation to the selected input in the selected interval, or to the whole time */
    private fun apply(op: (Waveform.Trace, Int, Int) -> Unit) {
        val t = selectedInput() ?: return
        val (a, b) = interval()
        op(t, a, b)
        simulate()
    }

    private fun selectedInput(): Waveform.Trace? {
        val t = waveform.traces.getOrNull(selected)
        if (t == null || !t.input) {
            status.text = Lang.get("msg_wfSelectInput")
            status.foreground = Color(170, 90, 0)
            return null
        }
        return t
    }

    private fun interval(): Pair<Int, Int> =
        if (selStart < 0) 0 to waveform.stepCount else min(selStart, selEnd) to max(selStart, selEnd) + 1

    private fun askInt(message: String, def: Long): Long? {
        val s = JOptionPane.showInputDialog(this, message, def.toString()) ?: return null
        return parseValue(s)
    }

    private fun parseValue(s: String): Long? {
        val v = s.trim().lowercase()
        return when {
            v.startsWith("0x") -> v.substring(2).toLongOrNull(16)
            v.startsWith("0b") -> v.substring(2).toLongOrNull(2)
            v.startsWith("-") -> v.toLongOrNull()
            else -> v.toLongOrNull()
        }
    }

    private fun clockDialog() {
        val t = selectedInput() ?: return
        val half = askInt(Lang.get("msg_wfClockHalfPeriod"), 1) ?: return
        val (a, b) = interval()
        t.clock(a, b, half.toInt(), 0)
        simulate()
    }

    private fun countDialog() {
        val t = selectedInput() ?: return
        val n = askInt(Lang.get("msg_wfCountSteps"), 1) ?: return
        val (a, b) = interval()
        t.count(a, b, n.toInt(), 0)
        simulate()
    }

    private fun valueDialog(cell: Int) {
        val t = selectedInput() ?: return
        val (a, b) = if (cell >= 0 && (selStart < 0 || cell < interval().first || cell >= interval().second))
            cell to cell + 1 else interval()
        val v = askInt(Lang.get("msg_wfValue"), t.step(a)) ?: return
        t.set(a, b, v)
        simulate()
    }

    private fun openVwf() {
        val fc = MyFileChooser()
        fc.fileFilter = FileNameExtensionFilter("Quartus Vector Waveform File", "vwf")
        vwfFile?.let { fc.selectedFile = it } ?: main.baseFileName?.let { fc.currentDirectory = it.parentFile }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        try {
            val missing = waveform.readInputs(VwfFile.read(fc.selectedFile))
            vwfFile = fc.selectedFile
            title = Lang.get("menu_waveformEditor") + " - " + fc.selectedFile.name
            updateFields()
            fit()
            simulate()
            if (missing.isNotEmpty()) status.text = Lang.get("msg_wfNotInVwf_N", missing.joinToString(", "))
        } catch (e: IOException) {
            ErrorMessage(Lang.get("btn_wfOpen")).addCause(e).show(this)
        }
    }

    private fun saveVwf() {
        val fc = MyFileChooser()
        fc.fileFilter = FileNameExtensionFilter("Quartus Vector Waveform File", "vwf")
        fc.selectedFile = vwfFile ?: main.baseFileName?.let { File(it.parentFile, it.nameWithoutExtension + ".vwf") }
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var f = fc.selectedFile
        if (!f.name.lowercase().endsWith(".vwf")) f = File(f.parentFile, f.name + ".vwf")
        try {
            waveform.writeVwf(f)
            vwfFile = f
            title = Lang.get("menu_waveformEditor") + " - " + f.name
        } catch (e: IOException) {
            ErrorMessage(Lang.get("btn_wfSave")).addCause(e).show(this)
        }
    }

    private fun copyTest() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(waveform.createTestData()), null)
        status.text = Lang.get("msg_wfCopied")
        status.foreground = Color(0, 110, 0)
    }

    private fun zoom(f: Double) {
        cellWidth = (cellWidth * f).coerceIn(0.05, 2000.0)
        repaintAll()
    }

    private fun fit() {
        val w = max(200, scroll.viewport.width - 10)
        cellWidth = (w.toDouble() / waveform.stepCount).coerceIn(0.05, 80.0)
        repaintAll()
    }

    private fun repaintAll() {
        view.revalidate()
        ruler.revalidate()
        names.revalidate()
        view.repaint()
        ruler.repaint()
        names.repaint()
    }

    private fun radix(): Waveform.Radix = Waveform.Radix.values()[radixBox.selectedIndex]

    private fun x(t: Double): Int = (t / waveform.gridSize * cellWidth).toInt()

    private fun cellAt(x: Int): Int = (x / cellWidth).toInt().coerceIn(0, waveform.stepCount - 1)

    private fun timeAt(x: Int): Double = x / cellWidth * waveform.gridSize

    private val totalWidth: Int get() = x(waveform.endTime) + 1

    /* the names and the values at the cursor */
    private inner class NamePanel : JComponent() {
        init {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    selected = e.y / ROW
                    repaintAll()
                }
            })
        }

        override fun getPreferredSize() = Dimension(NAME_WIDTH, waveform.traces.size * ROW)

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = background ?: Color.WHITE
            g2.fillRect(0, 0, width, height)
            waveform.traces.forEachIndexed { i, t ->
                val y = i * ROW
                if (i == selected) {
                    g2.color = SELECTION
                    g2.fillRect(0, y, width, ROW)
                }
                g2.color = if (t.input) INPUT_COLOR else OUTPUT_COLOR
                g2.font = font.deriveFont(Font.BOLD)
                val arrow = if (t.input) "▶ " else "◀ "
                g2.drawString(arrow + t.name + if (t.bits > 1) " [${t.bits - 1}..0]" else "", 6, y + ROW / 2 + 5)
                if (cursorTime >= 0) {
                    val v = if (t.input) t.step(cellAt(x(cursorTime))) else t.resultAt(cursorTime)
                    val s = waveform.text(t, v, if (t.bits == 1) Waveform.Radix.BINARY else radix())
                    g2.font = font
                    g2.color = Color.DARK_GRAY
                    g2.drawString(s, width - g2.fontMetrics.stringWidth(s) - 6, y + ROW / 2 + 5)
                }
                g2.color = Color.LIGHT_GRAY
                g2.drawLine(0, y + ROW - 1, width, y + ROW - 1)
            }
        }
    }

    /* the time axis, a click sets the cursor */
    private inner class Ruler : JComponent() {
        init {
            val ml = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = setCursor(e)
                override fun mouseDragged(e: MouseEvent) = setCursor(e)
                private fun setCursor(e: MouseEvent) {
                    cursorTime = timeAt(e.x).coerceIn(0.0, waveform.endTime)
                    repaintAll()
                }
            }
            addMouseListener(ml)
            addMouseMotionListener(ml)
            toolTipText = Lang.get("msg_wfCursor")
        }

        override fun getPreferredSize() = Dimension(totalWidth, 24)

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = Color(245, 245, 245)
            g2.fillRect(0, 0, width, height)
            g2.color = Color.DARK_GRAY
            // a label at least every 90 pixels
            val stepsPerLabel = generateSequence(1L) { it * 10 }.flatMap { sequenceOf(it, it * 2, it * 5) }
                .first { it * cellWidth >= 90 }.toInt()
            val clip = g.clipBounds
            val first = max(0, (clip.x / cellWidth).toInt() / stepsPerLabel * stepsPerLabel)
            var i = first
            while (i <= waveform.stepCount && i * cellWidth <= clip.x + clip.width) {
                val x = (i * cellWidth).toInt()
                g2.drawLine(x, 14, x, 24)
                g2.drawString(Waveform.formatTime(i * waveform.gridSize), x + 2, 12)
                i += stepsPerLabel
            }
            if (cursorTime >= 0) {
                g2.color = Color.RED
                val x = x(cursorTime)
                g2.drawLine(x, 0, x, 24)
                val s = Waveform.formatTime(cursorTime)
                g2.drawString(s, x + 3, 23)
            }
        }
    }

    /* the waveforms */
    private inner class WaveView : JComponent() {
        private var paintValue = -1L
        private var paintRow = -1
        private var selecting = false

        init {
            val ml = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val row = e.y / ROW
                    if (row !in waveform.traces.indices) return
                    selected = row
                    val t = waveform.traces[row]
                    val cell = cellAt(e.x)
                    if (e.isShiftDown || !t.input) {
                        selecting = true
                        selStart = cell
                        selEnd = cell
                    } else if (SwingUtilities.isRightMouseButton(e) || e.clickCount > 1 || t.bits > 1) {
                        valueDialog(cell)
                    } else {
                        if (selStart >= 0 && (cell < interval().first || cell >= interval().second)) selStart = -1
                        paintRow = row
                        paintValue = t.step(cell) xor 1
                        t.set(cell, cell + 1, paintValue)
                    }
                    repaintAll()
                }

                override fun mouseDragged(e: MouseEvent) {
                    val cell = cellAt(e.x)
                    if (selecting) selEnd = cell
                    else if (paintRow >= 0) waveform.traces[paintRow].set(cell, cell + 1, paintValue)
                    repaintAll()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (selecting && selStart == selEnd && abs(e.x - selStart * cellWidth) < 3) selStart = -1
                    selecting = false
                    if (paintRow >= 0) {
                        paintRow = -1
                        simulate()
                    }
                }
            }
            addMouseListener(ml)
            addMouseMotionListener(ml)
        }

        override fun getPreferredSize() = Dimension(totalWidth, waveform.traces.size * ROW)

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = Color.WHITE
            g2.fillRect(0, 0, width, height)
            val clip = g.clipBounds
            // grid
            if (cellWidth >= 4) {
                g2.color = GRID
                val first = max(0, (clip.x / cellWidth).toInt())
                var i = first
                while (i <= waveform.stepCount && i * cellWidth <= clip.x + clip.width) {
                    val x = (i * cellWidth).toInt()
                    g2.drawLine(x, 0, x, height)
                    i++
                }
            }
            waveform.traces.forEachIndexed { row, t ->
                val y = row * ROW
                if (row == selected) {
                    g2.color = SELECTION
                    g2.fillRect(0, y, totalWidth, ROW)
                    if (selStart >= 0) {
                        val (a, b) = interval()
                        g2.color = INTERVAL
                        g2.fillRect((a * cellWidth).toInt(), y, ((b - a) * cellWidth).toInt(), ROW)
                    }
                }
                val changes = ArrayList<Pair<Double, Long>>()
                if (t.input) {
                    var last: Long? = null
                    for (i in 0 until waveform.stepCount) {
                        val v = t.step(i)
                        if (v != last) changes.add(i * waveform.gridSize to v)
                        last = v
                    }
                } else changes.addAll(t.result)
                g2.color = if (t.input) INPUT_COLOR else OUTPUT_COLOR
                g2.stroke = BasicStroke(1.6f)
                for (k in changes.indices) {
                    val (time, v) = changes[k]
                    val end = if (k + 1 < changes.size) changes[k + 1].first else waveform.endTime
                    val x1 = x(time)
                    val x2 = x(end)
                    if (x2 < clip.x || x1 > clip.x + clip.width) continue
                    drawValue(g2, t, v, x1, x2, y, k > 0)
                }
                g2.stroke = BasicStroke(1f)
                g2.color = Color.LIGHT_GRAY
                g2.drawLine(0, y + ROW - 1, totalWidth, y + ROW - 1)
            }
            if (cursorTime >= 0) {
                g2.color = Color.RED
                val x = x(cursorTime)
                g2.drawLine(x, 0, x, height)
            }
        }

        private fun drawValue(g2: Graphics2D, t: Waveform.Trace, v: Long, x1: Int, x2: Int, y: Int, edge: Boolean) {
            val top = y + 6
            val bottom = y + ROW - 7
            if (v < 0) {
                val c = g2.color
                g2.color = UNDEFINED
                g2.fillRect(x1, top, x2 - x1, bottom - top)
                g2.color = c
                return
            }
            if (t.bits == 1) {
                val yy = if (v != 0L) top else bottom
                if (edge) g2.drawLine(x1, top, x1, bottom)
                g2.drawLine(x1, yy, x2, yy)
            } else {
                val d = min(4, (x2 - x1) / 2)
                g2.drawLine(x1, (top + bottom) / 2, x1 + d, top)
                g2.drawLine(x1 + d, top, x2 - d, top)
                g2.drawLine(x2 - d, top, x2, (top + bottom) / 2)
                g2.drawLine(x1, (top + bottom) / 2, x1 + d, bottom)
                g2.drawLine(x1 + d, bottom, x2 - d, bottom)
                g2.drawLine(x2 - d, bottom, x2, (top + bottom) / 2)
                val s = waveform.text(t, v, radix())
                val w = g2.fontMetrics.stringWidth(s)
                if (w < x2 - x1 - 2 * d - 2) {
                    val c = g2.color
                    g2.color = Color.BLACK
                    g2.drawString(s, (x1 + x2 - w) / 2, (top + bottom) / 2 + 5)
                    g2.color = c
                }
            }
        }
    }

    companion object {
        private const val ROW = 34
        private const val NAME_WIDTH = 220
        private val INPUT_COLOR = Color(0, 70, 170)
        private val OUTPUT_COLOR = Color(0, 120, 40)
        private val SELECTION = Color(225, 235, 255)
        private val INTERVAL = Color(170, 200, 255, 120)
        private val GRID = Color(235, 235, 235)
        private val UNDEFINED = Color(255, 150, 150, 110)
        private val EDITORS = HashMap<Main, WaveformEditor>()

        /**
         * Shows the waveform editor of the given main window.
         *
         * @param main the main window
         */
        @JvmStatic
        fun show(main: Main) {
            try {
                val e = EDITORS.getOrPut(main) { WaveformEditor(main) }
                e.isVisible = true
                e.toFront()
            } catch (ex: Exception) {
                ErrorMessage(Lang.get("menu_waveformEditor")).addCause(ex).show(main)
            }
        }
    }
}
