/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.basic.And
import de.neemann.digital.core.basic.NAnd
import de.neemann.digital.core.basic.NOr
import de.neemann.digital.core.basic.Not
import de.neemann.digital.core.basic.Or
import de.neemann.digital.core.basic.XNOr
import de.neemann.digital.core.basic.XOr
import de.neemann.digital.core.element.ElementAttributes
import de.neemann.digital.core.element.Keys
import de.neemann.digital.core.io.Const
import de.neemann.digital.core.wiring.Driver
import de.neemann.digital.core.wiring.Splitter
import de.neemann.digital.quartus.lib.QuartusPart
import de.neemann.digital.quartus.lib.QuartusParts

/**
 * Knows the Quartus primitives and how they are mapped to Digital elements.
 * Logic gates are mapped to the Digital gates (so they can be exported to VHDL/Verilog),
 * all other components to the natively simulated [QuartusPart]s.
 */
object QuartusLibrary {
    private val GATE = Regex("(B?)(AND|NAND|OR|NOR)(\\d+)")
    private val BUFFERS = setOf("WIRE", "LCELL", "GLOBAL", "SOFT", "CARRY", "CASCADE", "ROW_GLOBAL",
        "ALT_INBUF", "ALT_OUTBUF", "ALT_IOBUF", "CLKLOCK")

    /** the mapping of a Quartus primitive to a Digital element */
    class Mapping internal constructor(
        /** the name of the Digital element */
        val element: String,
        /** the native Quartus part or null if mapped to a Digital element */
        val part: QuartusPart?
    ) {
        /** the attributes of the Digital element */
        val attributes = ElementAttributes()

        /** the port map: Quartus port (upper case) to Digital pin key */
        val ports = HashMap<String, String>()

        /** true if this is a constant (VCC or GND) */
        var isConstant = false
            internal set

        internal fun port(quartus: String, key: String) = apply { ports[quartus.uppercase()] = key }

        /** returns the Digital pin key (including the direction prefix) of the given Quartus port or null */
        fun getPinKey(quartusPort: String): String? {
            val n = quartusPort.trim().uppercase()
            // a port name may contain the bus range, e.g. "q[7..0]"
            return ports[n] ?: ports[n.substringBefore('[').trim()]
        }
    }

    /** true if the given name is a megafunction which is simulated natively */
    @JvmStatic
    fun isMegafunction(name: String) = QuartusParts.isMegafunction(name)

    /** returns the mapping of a Quartus primitive or of a natively simulated component, null if unknown */
    @JvmStatic
    fun map(symbolName: String): Mapping? {
        val n = symbolName.trim().uppercase()
        GATE.matchEntire(n)?.let { m ->
            val inputs = m.groupValues[3].toInt()
            if (inputs !in 2..16) return null
            val inverted = m.groupValues[1].isNotEmpty()
            val elem = when (m.groupValues[2]) {
                "AND" -> if (inverted) NOr.DESCRIPTION else And.DESCRIPTION
                "NAND" -> if (inverted) Or.DESCRIPTION else NAnd.DESCRIPTION
                "OR" -> if (inverted) NAnd.DESCRIPTION else Or.DESCRIPTION
                else -> if (inverted) And.DESCRIPTION else NOr.DESCRIPTION
            }
            return gate(elem.name, inputs)
        }
        when (n) {
            "XOR" -> return gate(XOr.DESCRIPTION.name, 2)
            "XNOR" -> return gate(XNOr.DESCRIPTION.name, 2)
            "NOT", "EXP" -> return Mapping(Not.DESCRIPTION.name, null)
                .port("IN", ShapeBuilder.IN + "in").port("OUT", ShapeBuilder.OUT + "out")
            "TRI" -> return Mapping(Driver.DESCRIPTION.name, null)
                .port("IN", ShapeBuilder.IN + "in").port("OE", ShapeBuilder.IN + "sel").port("OUT", ShapeBuilder.OUT + "out")
            "VCC", "GND" -> return Mapping(Const.DESCRIPTION.name, null).also {
                it.isConstant = true
                it.attributes.set(Keys.VALUE, if (n == "VCC") 1L else 0L)
            }
        }
        if (n in BUFFERS) return Mapping(Splitter.DESCRIPTION.name, null)
            .port("IN", ShapeBuilder.IN + "0").port("OUT", ShapeBuilder.OUT + "0").also {
                it.attributes.set(Keys.INPUT_SPLIT, "1").set(Keys.OUTPUT_SPLIT, "1")
            }

        val part = QuartusParts.get(n) ?: return null
        val m = Mapping(part.digitalName, part)
        part.inputs.forEach { m.port(it.name, ShapeBuilder.IN + it.name) }
        part.outputs.forEach { m.port(it.name, ShapeBuilder.OUT + it.name) }
        return m
    }

    private fun gate(elem: String, inputs: Int): Mapping {
        val m = Mapping(elem, null)
        m.attributes.set(Keys.INPUT_COUNT, inputs)
        for (i in 1..inputs) m.port("IN$i", ShapeBuilder.IN + "In_$i")
        return m.port("OUT", ShapeBuilder.OUT + "out")
    }
}
