/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.draw.graphics.Vector
import de.neemann.digital.draw.shapes.custom.CustomShapeDescription
import de.neemann.digital.quartus.BdfFile.Direction
import de.neemann.digital.quartus.BdfFile.Point
import de.neemann.digital.quartus.BdfFile.Rect

/**
 * Creates symbols which look like the symbols of the Quartus libraries:
 * pins, gates, flip-flops and the default rectangular block symbol.
 * All coordinates are Quartus coordinates, all ports are on the grid of 8 units.
 */
object SymbolFactory {
    /** the distance of two ports */
    const val STEP = 16
    private const val LINE = 16
    private const val CHAR_WIDTH = 6

    /** a port of a block symbol */
    class Port(val name: String, val label: String = name, val bus: Boolean = false)

    /** creates an input pin, the connection point is at (168,8) */
    @JvmStatic
    fun inputPin(name: String, default: String? = "VCC"): QSymbol = QSymbol("INPUT", 168, 16).apply {
        line(92, 12, 117, 12)
        line(92, 4, 117, 4)
        line(121, 8, 168, 8)
        line(92, 12, 92, 4)
        line(117, 4, 121, 8)
        line(117, 12, 121, 8)
        text("INPUT", 133, 5, 6)
        if (default != null) text(default, 136, 12, 6)
        text(name, 88 - name.length * CHAR_WIDTH, 6)
        ports.add(QSymbol.QPort(name, Direction.OUTPUT, Point(168, 8), null, null))
        valuePos = Point(105, 8)
    }

    /** creates an output pin, the connection point is at (0,8) */
    @JvmStatic
    fun outputPin(name: String): QSymbol = QSymbol("OUTPUT", 176, 16).apply {
        line(0, 8, 52, 8)
        line(52, 4, 78, 4)
        line(52, 12, 78, 12)
        line(52, 12, 52, 4)
        line(78, 4, 82, 8)
        line(82, 8, 78, 12)
        text("OUTPUT", 1, 5, 6)
        text(name, 90, 6)
        ports.add(QSymbol.QPort(name, Direction.INPUT, Point(0, 8), null, null))
        valuePos = Point(65, 8)
    }

    /** creates a bidirectional pin, the connection point is at (0,8) */
    @JvmStatic
    fun bidirPin(name: String): QSymbol = QSymbol("BIDIR", 192, 16).apply {
        line(0, 8, 52, 8)
        line(52, 8, 56, 4)
        line(56, 4, 82, 4)
        line(82, 4, 86, 8)
        line(86, 8, 82, 12)
        line(82, 12, 56, 12)
        line(56, 12, 52, 8)
        text("BIDIR", 1, 5, 6)
        text(name, 94, 6)
        ports.add(QSymbol.QPort(name, Direction.BIDIR, Point(0, 8), null, null))
        valuePos = Point(69, 8)
    }

    /** returns the width of the body of a block symbol */
    @JvmStatic
    fun bodyWidth(title: String, inputs: List<Port>, outputs: List<Port>): Int {
        val maxIn = inputs.maxOfOrNull { it.label.length } ?: 0
        val maxOut = outputs.maxOfOrNull { it.label.length } ?: 0
        val w = maxOf((maxIn + maxOut) * CHAR_WIDTH + 3 * CHAR_WIDTH, title.length * (CHAR_WIDTH + 1), 64)
        return (w + 7) / 8 * 8
    }

    /**
     * Creates a block symbol. The inputs are at x=0, the outputs at x=bodyWidth+32.
     * The first port is at y=32.
     */
    @JvmStatic
    fun block(title: String, inputs: List<Port>, outputs: List<Port>): QSymbol {
        val w = bodyWidth(title, inputs, outputs)
        val n = maxOf(inputs.size, outputs.size)
        val x1 = LINE
        val x2 = x1 + w
        val y2 = LINE + STEP * (n + 1)
        return QSymbol(title, x2 + LINE, y2 + 16).apply {
            rect(x1, LINE, x2, y2)
            typeText = QSymbol.createText(title, x1, 8, 8, true)
            inputs.forEachIndexed { i, p ->
                val y = 2 * STEP + STEP * i
                port(p.name, Direction.INPUT, 0, y, x1, y, p.bus, QSymbol.createText(p.label, x1 + 4, y))
            }
            outputs.forEachIndexed { i, p ->
                val y = 2 * STEP + STEP * i
                val l = QSymbol.createText(p.label, x2 - 4 - p.label.length * CHAR_WIDTH, y)
                port(p.name, Direction.OUTPUT, x2 + LINE, y, x2, y, p.bus, l)
            }
            instanceText = QSymbol.createText("inst", x1, y2 + 8)
        }
    }

    /** the kinds of gates */
    enum class Gate { AND, OR, XOR }

    /**
     * Creates a gate.
     *
     * @param name      the name of the symbol, e.g. "NAND3"
     * @param gate      the kind of the gate
     * @param inputs    the number of inputs
     * @param invOut    true if the output is inverted
     * @param invIn     true if the inputs are inverted
     */
    @JvmStatic
    fun gate(name: String, gate: Gate, inputs: Int, invOut: Boolean, invIn: Boolean): QSymbol {
        val top = 8
        val bot = STEP * inputs + 8
        val mid = (top + bot) / 2 / 8 * 8
        return QSymbol(name, 64, bot + 8).apply {
            typeText = QSymbol.createText(name, 1, 3, 6)
            instanceText = QSymbol.createText("inst", 3, bot + 6)
            val tip: Int
            when (gate) {
                Gate.AND -> {
                    line(14, top, 30, top)
                    line(14, bot, 30, bot)
                    line(14, top, 14, bot)
                    arc(30, bot, 30, top, Rect(14, top, 46, bot))
                    tip = 46
                }
                else -> {
                    arc(14, bot, 14, top, Rect(8, top, 20, bot))
                    if (gate == Gate.XOR) arc(8, bot, 8, top, Rect(2, top, 14, bot))
                    curve(14, top, 38, top, 50, mid)
                    curve(14, bot, 38, bot, 50, mid)
                    tip = 50
                }
            }
            val outStart = if (invOut) {
                circle(tip + 4, mid, 4)
                tip + 8
            } else tip
            for (i in 1..inputs) {
                val y = STEP * i
                val inner = if (gate == Gate.AND) 14 else 16
                if (invIn) {
                    circle(inner - 4, y, 4)
                    port("IN$i", Direction.INPUT, 0, y, inner - 8, y)
                } else
                    port("IN$i", Direction.INPUT, 0, y, inner, y)
            }
            port("OUT", Direction.OUTPUT, 64, mid, outStart, mid)
        }
    }

    /** creates an inverter */
    @JvmStatic
    fun inverter(name: String = "NOT"): QSymbol = QSymbol(name, 48, 32).apply {
        typeText = QSymbol.createText(name, 1, 3, 6)
        instanceText = QSymbol.createText("inst", 3, 28)
        line(13, 25, 13, 7)
        line(13, 7, 31, 16)
        line(13, 25, 31, 16)
        circle(35, 16, 4)
        port("IN", Direction.INPUT, 0, 16, 13, 16)
        port("OUT", Direction.OUTPUT, 48, 16, 39, 16)
    }

    /** creates a buffer, optional with an output enable from the top */
    @JvmStatic
    fun buffer(name: String, enable: String? = null, openDrain: Boolean = false): QSymbol = QSymbol(name, 48, 32).apply {
        typeText = QSymbol.createText(name, 1, 3, 6)
        instanceText = QSymbol.createText("inst", 3, 28)
        line(13, 25, 13, 7)
        line(13, 7, 33, 16)
        line(13, 25, 33, 16)
        if (openDrain) {
            line(18, 13, 22, 16)
            line(22, 16, 18, 19)
        }
        port("IN", Direction.INPUT, 0, 16, 13, 16)
        port("OUT", Direction.OUTPUT, 48, 16, 33, 16)
        if (enable != null) port(enable, Direction.INPUT, 24, 0, 24, 11)
    }

    /** creates the VCC symbol */
    @JvmStatic
    fun vcc(): QSymbol = QSymbol("VCC", 32, 16).apply {
        text("VCC", 7, 5, 6)
        line(8, 8, 24, 8)
        port("1", Direction.OUTPUT, 16, 16, 16, 8)
    }

    /** creates the GND symbol */
    @JvmStatic
    fun gnd(): QSymbol = QSymbol("GND", 32, 24).apply {
        text("GND", 8, 21, 6)
        line(8, 8, 24, 8)
        line(11, 11, 21, 11)
        line(14, 14, 18, 14)
        port("1", Direction.OUTPUT, 16, 0, 16, 8)
    }

    /**
     * Creates a flip-flop. The active low preset and clear inputs (PRN, CLRN, ...)
     * are drawn at the top and at the bottom.
     */
    @JvmStatic
    fun flipFlop(name: String, inputs: List<String>, clocks: Set<String>, outputs: List<String>): QSymbol {
        val top = inputs.filter { it.uppercase() in setOf("PRN", "PRE") }
        val bottom = inputs.filter { it.uppercase() in setOf("CLRN", "CLR") }
        val left = inputs.filter { it !in top && it !in bottom }
        val boxBottom = 24 + STEP * (maxOf(left.size, outputs.size) - 1) + 12
        val h = (boxBottom + 12 + 7) / 8 * 8
        return QSymbol(name, 64, h).apply {
            typeText = QSymbol.createText(name, 1, 3, 6)
            instanceText = QSymbol.createText("inst", 54, h - 4)
            rect(12, 12, 52, boxBottom)
            left.forEachIndexed { i, n ->
                val y = 24 + STEP * i
                if (n in clocks) {
                    line(12, y - 6, 20, y)
                    line(20, y, 12, y + 6)
                    port(n, Direction.INPUT, 0, y, 12, y, label = QSymbol.createText(n, 22, y, 6))
                } else
                    port(n, Direction.INPUT, 0, y, 12, y, label = QSymbol.createText(n, 14, y, 6))
            }
            for (n in top) {
                if (n.endsWith("N", true)) {
                    circle(32, 8, 4)
                    port(n, Direction.INPUT, 32, 0, 32, 4, label = QSymbol.createText(n, 24, 18, 6))
                } else
                    port(n, Direction.INPUT, 32, 0, 32, 12, label = QSymbol.createText(n, 24, 18, 6))
            }
            for (n in bottom) {
                if (n.endsWith("N", true)) {
                    circle(32, boxBottom + 4, 4)
                    port(n, Direction.INPUT, 32, h, 32, boxBottom + 8, label = QSymbol.createText(n, 21, boxBottom - 6, 6))
                } else
                    port(n, Direction.INPUT, 32, h, 32, boxBottom, label = QSymbol.createText(n, 21, boxBottom - 6, 6))
            }
            outputs.forEachIndexed { i, n ->
                val y = 24 + STEP * i
                port(n, Direction.OUTPUT, 64, y, 52, y, label = QSymbol.createText(n, 52 - 2 - n.length * 6, y, 6))
            }
        }
    }

    /** creates the shape of an input pin as drawn by Quartus, the connection point is at (0,0) */
    @JvmStatic
    fun inputPinShape(name: String, key: String): CustomShapeDescription =
        ShapeBuilder(Vector(0, 0)).addSymbol(inputPin(name, null), -168, -8) { key }.build()

    /** creates the shape of an output pin as drawn by Quartus, the connection point is at (0,0) */
    @JvmStatic
    fun outputPinShape(name: String, key: String): CustomShapeDescription =
        ShapeBuilder(Vector(0, 0)).addSymbol(outputPin(name), 0, -8) { key }.build()
}
