/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.element.Keys
import de.neemann.digital.core.io.In
import de.neemann.digital.core.io.Out
import de.neemann.digital.core.wiring.Clock
import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.draw.elements.VisualElement

/**
 * The pin assignments of the inputs and outputs of a circuit to the resources of a board, like the Pin Planner
 * of Quartus. The pins are stored in the attribute "pin number" of the inputs and outputs, separated by commas,
 * bit 0 first.
 *
 * @param board the board
 */
class PinPlanner(val board: Board) {

    /**
     * An input or output of the circuit.
     *
     * @param element the element of the circuit, null in tests
     * @param label   the label
     * @param input   true if it is an input
     * @param clock   true if it is a clock
     * @param pins    the pin of every bit, null if not assigned
     */
    class Signal(val element: VisualElement?, val label: String, val input: Boolean, val clock: Boolean,
                 val pins: Array<String?>) {
        /** the number of bits */
        val bits: Int get() = pins.size

        /** the value of the attribute "pin number" */
        val pinNumber: String
            get() {
                val last = pins.indexOfLast { it != null }
                return (0..last).joinToString(",") { pins[it] ?: "" }
            }
    }

    /** the result of an assignment */
    enum class Result { OK, WRONG_DIRECTION, NOT_ENOUGH_RESOURCES, NO_RESOURCE }

    /**
     * A group of resources of the same kind, used to assign the bits of a bus to consecutive resources.
     *
     * @param name  the name of the group
     * @param input true if the resources drive inputs of the FPGA
     * @param pins  the FPGA pins
     * @param names the names of the resources as printed on the board
     */
    class Group(val name: String, val input: Boolean, val pins: List<String>, val names: List<String>)

    /** the inputs and outputs */
    val signals = ArrayList<Signal>()

    /** the resources of the board */
    val groups: List<Group> = listOf(
        Group("SW", true, board.switches, board.switches.indices.map { "SW$it" }),
        Group("BUTTON", true, board.buttons, board.buttons.indices.map { "BUTTON$it" }),
        Group("CLOCK", true, board.clocks.keys.toList(),
            board.clocks.keys.mapIndexed { i, _ -> if (i == 0) "CLOCK_50" else "CLOCK_50_${i + 1}" }),
        Group("LEDG", false, board.leds, board.leds.indices.map { "LEDG$it" }),
        Group("HEX", false, board.hex.flatten(),
            board.hex.flatMapIndexed { d, segs -> segs.indices.map { s -> "HEX$d.${SEGMENTS[s]}" } })
    )

    /** returns the name of the resource of a pin like "SW0", or null */
    fun resourceName(pin: String): String? {
        val p = normalize(pin)
        for (g in groups) {
            val i = g.pins.indexOf(p)
            if (i >= 0) return g.names[i]
        }
        return null
    }

    /** returns the group of a pin */
    fun group(pin: String): Group? = normalize(pin).let { p -> groups.firstOrNull { p in it.pins } }

    /** returns the signal and the bit assigned to the given pin */
    fun owner(pin: String): Pair<Signal, Int>? {
        val p = normalize(pin)
        for (s in signals) {
            val b = s.pins.indexOfFirst { it != null && normalize(it) == p }
            if (b >= 0) return s to b
        }
        return null
    }

    /**
     * Assigns a resource to a signal. The bits of a bus are assigned to consecutive resources of the
     * same group, starting with bit 0 at the given resource. A resource used by another signal is taken from it.
     *
     * @param s   the signal
     * @param pin the pin of the resource the bit 0 is assigned to
     * @return the result
     */
    fun assign(s: Signal, pin: String): Result {
        val g = group(pin) ?: return Result.NO_RESOURCE
        if (g.input != s.input) return Result.WRONG_DIRECTION
        val start = g.pins.indexOf(normalize(pin))
        if (start + s.bits > g.pins.size) return Result.NOT_ENOUGH_RESOURCES
        for (b in 0 until s.bits) {
            val p = g.pins[start + b]
            owner(p)?.let { (o, ob) -> o.pins[ob] = null }
            s.pins[b] = p
        }
        return Result.OK
    }

    /** removes the pins of a signal */
    fun clear(s: Signal) = s.pins.fill(null)

    /**
     * Sets the pins of a signal from a text like "J6, H5", bit 0 first.
     */
    fun setPinNumber(s: Signal, text: String) {
        val l = text.split(',').map { it.trim() }
        for (b in 0 until s.bits) s.pins[b] = l.getOrNull(b)?.takeIf { it.isNotEmpty() }?.let { normalize(it) }
    }

    /** returns the names of the resources of a signal, like "SW0..SW3" or "LEDG0" */
    fun resources(s: Signal): String {
        val names = s.pins.map { p -> p?.let { resourceName(it) ?: it } }
        if (names.all { it == null }) return ""
        if (names.size > 1 && names.none { it == null }) {
            val g = group(s.pins[0]!!)
            if (g != null) {
                val idx = s.pins.map { g.pins.indexOf(normalize(it!!)) }
                if (idx.zipWithNext().all { (a, b) -> b == a + 1 }) return names.first() + ".." + names.last()
            }
        }
        return names.joinToString(", ") { it ?: "-" }
    }

    companion object {
        /** the names of the segments */
        val SEGMENTS = listOf("a", "b", "c", "d", "e", "f", "g", "dp")

        /** removes the prefix "PIN_" */
        @JvmStatic
        fun normalize(pin: String): String = pin.trim().uppercase().removePrefix("PIN_")

        /**
         * Creates the pin planner for the inputs and outputs of a circuit.
         *
         * @param circuit the circuit
         * @param board   the board
         */
        @JvmStatic
        fun create(circuit: Circuit, board: Board): PinPlanner {
            val pp = PinPlanner(board)
            for (ve in circuit.elements) {
                val clock = ve.elementName == Clock.DESCRIPTION.name
                val input = ve.elementName == In.DESCRIPTION.name || clock
                if (!input && ve.elementName != Out.DESCRIPTION.name) continue
                val a = ve.elementAttributes
                val label = a.label ?: continue
                if (label.isBlank()) continue
                val bits = if (clock) 1 else a.bits
                val s = Signal(ve, label, input, clock, arrayOfNulls(bits))
                pp.setPinNumber(s, a.get(Keys.PINNUMBER))
                pp.signals.add(s)
            }
            pp.signals.sortWith(compareBy({ !it.input }, { it.label.lowercase() }))
            return pp
        }
    }
}
