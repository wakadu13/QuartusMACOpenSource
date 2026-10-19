/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.element.ElementAttributes
import de.neemann.digital.core.element.Keys
import de.neemann.digital.core.io.Button
import de.neemann.digital.core.io.CommonConnectionType
import de.neemann.digital.core.io.DipSwitch
import de.neemann.digital.core.io.In
import de.neemann.digital.core.io.Out
import de.neemann.digital.core.io.VDD
import de.neemann.digital.core.wiring.Clock
import de.neemann.digital.core.wiring.Splitter
import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.draw.elements.Tunnel
import de.neemann.digital.draw.elements.VisualElement
import de.neemann.digital.draw.elements.Wire
import de.neemann.digital.draw.graphics.Vector
import de.neemann.digital.draw.library.ElementLibrary
import de.neemann.digital.draw.shapes.ShapeFactory
import de.neemann.digital.gui.components.data.DummyElement
import java.awt.Color
import java.io.File

/**
 * A development board: the FPGA pins of its displays, LEDs, switches, buttons and clocks.
 *
 * @param name    the name of the board
 * @param devices the FPGA devices used on the board
 */
class Board(
    val name: String,
    private val devices: Set<String>,
    /** the clock inputs and their frequency */
    val clocks: Map<String, Int>,
    /** the seven segment displays, segments a..g and the decimal point; the segments are active low */
    val hex: List<List<String>>,
    /** the LEDs, active high */
    val leds: List<String>,
    /** the slide switches */
    val switches: List<String>,
    /** the push buttons, active low */
    val buttons: List<String>
) {
    /** a resource of the board connected to a FPGA pin */
    sealed class Resource {
        /** a clock oscillator */
        class ClockIn(val index: Int, val frequency: Int) : Resource()

        /** a segment of a seven segment display, 0..6 is a..g, 7 is the decimal point */
        class Segment(val display: Int, val segment: Int) : Resource()

        /** a LED */
        class Led(val index: Int) : Resource()

        /** a slide switch */
        class Switch(val index: Int) : Resource()

        /** a push button */
        class PushButton(val index: Int) : Resource()

        /** true if the resource drives a FPGA input */
        val isInput: Boolean get() = this is ClockIn || this is Switch || this is PushButton
    }

    private val resources = HashMap<String, Resource>().apply {
        clocks.entries.forEachIndexed { i, (p, f) -> put(p, Resource.ClockIn(i, f)) }
        hex.forEachIndexed { d, segs -> segs.forEachIndexed { s, p -> put(p, Resource.Segment(d, s)) } }
        leds.forEachIndexed { i, p -> put(p, Resource.Led(i)) }
        switches.forEachIndexed { i, p -> put(p, Resource.Switch(i)) }
        buttons.forEachIndexed { i, p -> put(p, Resource.PushButton(i)) }
    }

    /** returns the resource connected to the given FPGA pin (like "G21") or null */
    fun getResource(pin: String): Resource? = resources[pin.uppercase().removePrefix("PIN_")]

    /** true if the given device is used on this board */
    fun usesDevice(device: String) = device.uppercase() in devices

    companion object {
        /** the Terasic DE0 board with a Cyclone III EP3C16F484C6 */
        @JvmField
        val DE0 = Board(
            "DE0", setOf("EP3C16F484C6"),
            linkedMapOf("G21" to 50_000_000, "B12" to 50_000_000),
            listOf(
                listOf("E11", "F11", "H12", "H13", "G12", "F12", "F13", "D13"),
                listOf("A13", "B13", "C13", "A14", "B14", "E14", "A15", "B15"),
                listOf("D15", "A16", "B16", "E15", "A17", "B17", "F14", "A18"),
                listOf("B18", "F15", "A19", "B19", "C19", "D19", "G15", "G16")
            ),
            listOf("J1", "J2", "J3", "H1", "F2", "E1", "C1", "C2", "B2", "B1"),
            listOf("J6", "H5", "H6", "G4", "G5", "J7", "H7", "E3", "E4", "D2"),
            listOf("H2", "G3", "F1")
        )

        /** all known boards */
        @JvmField
        val ALL = listOf(DE0)

        /**
         * Finds the board of a project: the board using the device of the project
         * or the board most of the assigned pins belong to.
         */
        @JvmStatic
        fun find(device: String, pins: Collection<String>): Board? {
            ALL.firstOrNull { it.usesDevice(device) }?.let { return it }
            if (device.isNotBlank()) return null
            return ALL.maxByOrNull { b -> pins.count { b.getResource(it) != null } }
                ?.takeIf { b -> pins.count { b.getResource(it) != null } >= 3 }
        }
    }
}

/**
 * Creates a circuit which shows a design on a board: the top level design is embedded and its pins
 * are connected to seven segment displays, LEDs, switches, buttons and clocks according to the
 * pin assignments of the project. The other pins become the pins of the created circuit.
 */
internal class BoardCircuitBuilder(private val board: Board, private val outDir: File, private val topName: String) {
    /** a port of the top level design */
    class Port(val label: String, val input: Boolean, val bits: Int, val pins: List<String?>)

    private val circuit = Circuit()

    fun build(ports: List<Port>): Circuit {
        val library = ElementLibrary()
        library.setRootFilePath(outDir)
        val sf = ShapeFactory(library)

        // the top level design, every pin gets a tunnel
        val top = VisualElement(topName).setShapeFactory(sf).setPos(Vector(0, 0))
        circuit.add(top)
        val mm0 = top.getMinMax(false)
        // all positions are on the grid
        val min = Vector(grid(mm0.min.x), grid(mm0.min.y))
        val max = Vector(grid(mm0.max.x + SIZE - 1), grid(mm0.max.y + SIZE - 1))
        val center = (min.x + max.x) / 2
        for (p in top.pins) {
            val left = p.pos.x <= center
            val end = p.pos.add(if (left) -SIZE * 2 else SIZE * 2, 0)
            circuit.wires.add(Wire(p.pos, end))
            circuit.add(tunnel(p.name, end, if (left) 2 else 0))
        }

        // the pins without a board resource
        var yIn = min.y
        var yOut = min.y
        val xIn = min.x - SIZE * 12
        val xOut = max.x + SIZE * 12
        for (p in ports) {
            if (p.pins.any { it != null && resourceFor(p, it) != null }) continue
            if (p.input) {
                val pos = Vector(xIn, yIn)
                circuit.add(element(In.DESCRIPTION.name, pos).also { attr(it).set(Keys.LABEL, p.label).setBits(p.bits) })
                circuit.wires.add(Wire(pos, pos.add(SIZE * 2, 0)))
                circuit.add(tunnel(p.label, pos.add(SIZE * 2, 0), 0))
                yIn += SIZE * 3
            } else {
                val pos = Vector(xOut, yOut)
                circuit.add(element(Out.DESCRIPTION.name, pos).also { attr(it).set(Keys.LABEL, p.label).setBits(p.bits) })
                circuit.wires.add(Wire(pos.add(-SIZE * 2, 0), pos))
                circuit.add(tunnel(p.label, pos.add(-SIZE * 2, 0), 2))
                yOut += SIZE * 3
            }
        }

        // the bits of the busses
        val bitName = HashMap<Pair<String, Int>, String>()
        var ySplit = maxOf(yIn, yOut, max.y) + SIZE * 4
        val xSplit = min.x
        for (p in ports) {
            if (p.pins.none { it != null && resourceFor(p, it) != null }) continue
            if (p.bits == 1) {
                bitName[p.label to 0] = p.label
                continue
            }
            for (b in 0 until p.bits) bitName[p.label to b] = "${p.label}_$b"
            val pos = Vector(xSplit, ySplit)
            val sp = element(Splitter.DESCRIPTION.name, pos)
            circuit.add(sp)
            if (p.input) {
                // the single bits are merged
                attr(sp).set(Keys.INPUT_SPLIT, List(p.bits) { "1" }.joinToString(",")).set(Keys.OUTPUT_SPLIT, "${p.bits}")
                for (b in 0 until p.bits) {
                    val pin = pos.add(0, b * SIZE)
                    circuit.wires.add(Wire(pin.add(-SIZE * 2, 0), pin))
                    val r = p.pins.getOrNull(b)?.let { resourceFor(p, it) }
                    if (r != null) circuit.add(tunnel(bitName[p.label to b]!!, pin.add(-SIZE * 2, 0), 2))
                    else circuit.add(element("Ground", pin.add(-SIZE * 2, 0)))
                }
                circuit.wires.add(Wire(pos.add(SIZE, 0), pos.add(SIZE * 3, 0)))
                circuit.add(tunnel(p.label, pos.add(SIZE * 3, 0), 0))
            } else {
                attr(sp).set(Keys.INPUT_SPLIT, "${p.bits}").set(Keys.OUTPUT_SPLIT, List(p.bits) { "1" }.joinToString(","))
                circuit.wires.add(Wire(pos.add(-SIZE * 2, 0), pos))
                circuit.add(tunnel(p.label, pos.add(-SIZE * 2, 0), 2))
                for (b in 0 until p.bits) {
                    val pin = pos.add(SIZE, b * SIZE)
                    circuit.wires.add(Wire(pin, pin.add(SIZE * 2, 0)))
                    circuit.add(tunnel(bitName[p.label to b]!!, pin.add(SIZE * 2, 0), 0))
                }
            }
            ySplit += SIZE * (p.bits + 2)
        }

        // the resources of the board and the bits connected to them
        val used = HashMap<Board.Resource, String>()
        val usedKeys = HashSet<String>()
        for (p in ports) p.pins.forEachIndexed { b, pin ->
            val r = pin?.let { resourceFor(p, it) } ?: return@forEachIndexed
            val k = key(r)
            if (usedKeys.add(k)) used[r] = bitName[p.label to b]!!
        }
        drawBoard(Vector(max.x + SIZE * 24, min.y), used)
        return circuit
    }

    private fun resourceFor(p: Port, pin: String): Board.Resource? =
        board.getResource(pin)?.takeIf { it.isInput == p.input }

    private fun key(r: Board.Resource) = when (r) {
        is Board.Resource.ClockIn -> "clk${r.index}"
        is Board.Resource.Segment -> "hex${r.display}.${r.segment}"
        is Board.Resource.Led -> "led${r.index}"
        is Board.Resource.Switch -> "sw${r.index}"
        is Board.Resource.PushButton -> "key${r.index}"
    }

    private fun drawBoard(origin: Vector, used: Map<Board.Resource, String>) {
        circuit.add(text("${board.name} board", origin.add(0, -SIZE * 2)))
        val byKey = used.entries.associate { key(it.key) to it.value }

        // the seven segment displays, the leftmost is the display with the highest number
        var y = origin.y + SIZE * 2
        val displays = board.hex.indices.filter { d -> (0..7).any { byKey.containsKey("hex$d.$it") } }
        if (displays.isNotEmpty()) {
            board.hex.indices.reversed().forEachIndexed { col, d ->
                val pos = Vector(origin.x + col * SIZE * 8, y + SIZE * 3)
                circuit.add(text("HEX$d", pos.add(0, -SIZE * 3)))
                val ve = element("Seven-Seg", pos)
                attr(ve).set(Keys.COMMON_CONNECTION, true).set(Keys.COMMON_CONNECTION_TYPE, CommonConnectionType.anode)
                    .set(Keys.COLOR, Color.RED)
                circuit.add(ve)
                for (s in 0..7) {
                    val top = s < 4
                    val pin = pos.add((s % 4) * SIZE, if (top) 0 else SIZE * 7)
                    val end = pin.add(0, if (top) -SIZE else SIZE)
                    val name = byKey["hex$d.$s"]
                    circuit.wires.add(Wire(pin, end))
                    // a segment which is not used is off
                    if (name != null) circuit.add(tunnel(name, end, if (top) 1 else 3))
                    else circuit.add(element(VDD.DESCRIPTION.name, end))
                }
                val ca = pos.add(SIZE * 4, SIZE * 7)
                circuit.wires.add(Wire(ca, ca.add(SIZE, 0)))
                circuit.add(element(VDD.DESCRIPTION.name, ca.add(SIZE, 0)))
            }
            y += SIZE * 14
        }

        // LEDs, switches and buttons: a row each
        fun row(title: String, count: Int, prefix: String, create: (Int, Vector, String) -> Unit) {
            if ((0 until count).none { byKey.containsKey("$prefix$it") }) return
            circuit.add(text(title, Vector(origin.x, y)))
            y += SIZE * 3
            // the element with the highest number is the leftmost, as on the board
            for (i in count - 1 downTo 0) {
                val name = byKey["$prefix$i"] ?: continue
                create(i, Vector(origin.x + SIZE * 3 + (count - 1 - i) * SIZE * 5, y), name)
            }
            y += SIZE * 5
        }
        row("LEDG", board.leds.size, "led") { i, pos, name ->
            val led = element("LED", pos)
            attr(led).set(Keys.LABEL, "LEDG$i").set(Keys.COLOR, Color.GREEN)
            circuit.add(led)
            circuit.wires.add(Wire(pos.add(0, SIZE * 2), pos))
            circuit.add(tunnel(name, pos.add(0, SIZE * 2), 3))
        }
        row("SW", board.switches.size, "sw") { i, pos, name ->
            val sw = element(DipSwitch.DESCRIPTION.name, pos)
            attr(sw).set(Keys.LABEL, "SW$i")
            circuit.add(sw)
            circuit.wires.add(Wire(pos, pos.add(0, SIZE * 2)))
            circuit.add(tunnel(name, pos.add(0, SIZE * 2), 3))
        }
        row("BUTTON", board.buttons.size, "key") { i, pos, name ->
            val bt = element(Button.DESCRIPTION.name, pos)
            attr(bt).set(Keys.LABEL, "BUTTON$i").set(Keys.ACTIVE_LOW, true)
            circuit.add(bt)
            circuit.wires.add(Wire(pos, pos.add(0, SIZE * 2)))
            circuit.add(tunnel(name, pos.add(0, SIZE * 2), 3))
        }
        for ((r, name) in used) if (r is Board.Resource.ClockIn) {
            val pos = Vector(origin.x, y + SIZE)
            val clk = element(Clock.DESCRIPTION.name, pos)
            attr(clk).set(Keys.LABEL, "CLOCK${r.frequency / 1_000_000}MHz").set(Keys.RUN_AT_REAL_TIME, true).set(Keys.FREQUENCY, r.frequency)
            circuit.add(clk)
            circuit.wires.add(Wire(pos, pos.add(SIZE * 2, 0)))
            circuit.add(tunnel(name, pos.add(SIZE * 2, 0), 0))
            y += SIZE * 3
        }
    }

    private fun element(name: String, pos: Vector) = VisualElement(name).setPos(pos)

    private fun attr(ve: VisualElement): ElementAttributes = ve.elementAttributes

    private fun tunnel(name: String, pos: Vector, rotation: Int): VisualElement {
        val t = element(Tunnel.DESCRIPTION.name, pos)
        t.elementAttributes.set(Keys.NETNAME, name)
        if (rotation != 0) t.elementAttributes.set(Keys.ROTATE, de.neemann.digital.core.element.Rotation(rotation))
        return t
    }

    private fun text(t: String, pos: Vector): VisualElement {
        val ve = element(DummyElement.TEXTDESCRIPTION.name, pos)
        ve.elementAttributes.set(Keys.DESCRIPTION, t)
        return ve
    }

    companion object {
        private const val SIZE = 20

        private fun grid(v: Int) = Math.floorDiv(v, SIZE) * SIZE
    }
}
