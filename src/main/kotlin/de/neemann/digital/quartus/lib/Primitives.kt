/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib

import de.neemann.digital.quartus.SymbolFactory
import de.neemann.digital.quartus.SymbolFactory.Gate

/**
 * The primitives of the Quartus library: logic gates, buffers, constants and flip-flops.
 */
internal object Primitives {
    private val GATE_SIZES = intArrayOf(2, 3, 4, 6, 8, 12)
    private const val LOGIC = "primitives/logic"
    private const val BUFFER = "primitives/buffer"
    private const val STORAGE = "primitives/storage"
    private const val OTHER = "primitives/other"

    fun create(add: (QuartusPart) -> Unit) {
        createGates(add)
        createBuffers(add)
        createFlipFlops(add)
    }

    private fun createGates(add: (QuartusPart) -> Unit) {
        // name, gate, inverted output, inverted inputs, description
        val kinds = listOf(
            Triple("AND", Gate.AND, false to false), Triple("NAND", Gate.AND, true to false),
            Triple("OR", Gate.OR, false to false), Triple("NOR", Gate.OR, true to false),
            Triple("BAND", Gate.AND, false to true), Triple("BNAND", Gate.AND, true to true),
            Triple("BOR", Gate.OR, false to true), Triple("BNOR", Gate.OR, true to true))
        for ((base, gate, inv) in kinds) {
            val (invOut, invIn) = inv
            for (n in GATE_SIZES) {
                val name = "$base$n"
                val descr = (if (invIn) "$n-input ${base.substring(1)} gate with inverted inputs" else "$n-input $base gate")
                add(gate(name, descr, gate, n, invOut, invIn))
            }
        }
        add(gate("XOR", "2-input exclusive OR gate", Gate.XOR, 2, false, false))
        add(gate("XNOR", "2-input exclusive NOR gate", Gate.XOR, 2, true, false))
        add(QuartusPart("NOT", "Inverter", LOGIC).input("IN").output("OUT")
            .comb { i, o -> o[0] = i[0].inv() and 1 }
            .symbol { SymbolFactory.inverter() })
    }

    private fun gate(name: String, descr: String, gate: Gate, n: Int, invOut: Boolean, invIn: Boolean): QuartusPart {
        val p = QuartusPart(name, descr, LOGIC)
        for (i in 1..n) p.input("IN$i")
        return p.output("OUT")
            .comb { i, o ->
                var r = when (gate) {
                    Gate.AND -> 1L
                    else -> 0L
                }
                for (k in 0 until n) {
                    val v = if (invIn) i[k].inv() and 1 else i[k] and 1
                    r = when (gate) {
                        Gate.AND -> r and v
                        Gate.OR -> r or v
                        Gate.XOR -> r xor v
                    }
                }
                o[0] = if (invOut) r xor 1 else r
            }
            .symbol { SymbolFactory.gate(name, gate, n, invOut, invIn) }
    }

    private fun createBuffers(add: (QuartusPart) -> Unit) {
        for ((name, descr) in listOf(
            "WIRE" to "Wire buffer", "LCELL" to "Logic cell buffer", "GLOBAL" to "Global signal buffer",
            "SOFT" to "Soft buffer", "CARRY" to "Carry buffer", "CASCADE" to "Cascade buffer",
            "ROW_GLOBAL" to "Row global buffer", "ALT_INBUF" to "Input buffer", "ALT_OUTBUF" to "Output buffer",
            "CLKLOCK" to "Clock buffer")) {
            add(QuartusPart(name, descr, BUFFER).input("IN").output("OUT")
                .comb { i, o -> o[0] = i[0] and 1 }
                .symbol { SymbolFactory.buffer(name) })
        }
        add(QuartusPart("EXP", "Expander buffer (inverting)", BUFFER).input("IN").output("OUT")
            .comb { i, o -> o[0] = i[0].inv() and 1 }
            .symbol { SymbolFactory.inverter("EXP") })
        add(QuartusPart("TRI", "Tri-state buffer", BUFFER).input("IN").input("OE", 1).output("OUT")
            .behavior {
                object : Behavior() {
                    override fun eval(i: LongArray, o: LongArray) {
                        o[0] = i[0] and 1
                        highZ[0] = if (i[1].isSet()) 0 else 1
                    }
                }
            }
            .symbol { SymbolFactory.buffer("TRI", "OE") })
        add(QuartusPart("OPNDRN", "Open drain buffer", BUFFER).input("IN").output("OUT")
            .behavior {
                object : Behavior() {
                    override fun eval(i: LongArray, o: LongArray) {
                        o[0] = 0
                        highZ[0] = if (i[0].isSet()) 1 else 0
                    }
                }
            }
            .symbol { SymbolFactory.buffer("OPNDRN", null, true) })
        add(QuartusPart("VCC", "Constant one", OTHER).output("1").comb { _, o -> o[0] = 1 }.symbol { SymbolFactory.vcc() })
        add(QuartusPart("GND", "Constant zero", OTHER).output("1").comb { _, o -> o[0] = 0 }.symbol { SymbolFactory.gnd() })
    }

    private enum class FFKind { D, T, JK, SR }

    private fun createFlipFlops(add: (QuartusPart) -> Unit) {
        add(ff("DFF", "D flip-flop with asynchronous clear and preset", FFKind.D))
        add(ff("DFFE", "D flip-flop with clock enable", FFKind.D, ena = true))
        add(ff("DFFEA", "D flip-flop with clock enable and asynchronous load", FFKind.D, ena = true, aload = true))
        add(ff("DFFEAS", "D flip-flop with clock enable, asynchronous and synchronous load and clear", FFKind.D,
            ena = true, aload = true, sync = true))
        add(ff("TFF", "T flip-flop with asynchronous clear and preset", FFKind.T))
        add(ff("TFFE", "T flip-flop with clock enable", FFKind.T, ena = true))
        add(ff("JKFF", "JK flip-flop with asynchronous clear and preset", FFKind.JK))
        add(ff("JKFFE", "JK flip-flop with clock enable", FFKind.JK, ena = true))
        add(ff("SRFF", "SR flip-flop with asynchronous clear and preset", FFKind.SR))
        add(ff("SRFFE", "SR flip-flop with clock enable", FFKind.SR, ena = true))
        add(QuartusPart("LATCH", "Level sensitive D latch", STORAGE).input("D").input("ENA", 1)
            .input("PRE").input("CLR").output("Q")
            .behavior {
                object : Behavior() {
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        when {
                            i[3].isSet() -> q = 0
                            i[2].isSet() -> q = 1
                            i[1].isSet() -> q = i[0] and 1
                        }
                        o[0] = q
                    }
                }
            }
            .symbol { p -> SymbolFactory.flipFlop("LATCH", listOf("D", "ENA", "PRE", "CLR").filter { p.isPortUsed(it) }, emptySet(), listOf("Q")) })
    }

    private fun ff(name: String, descr: String, kind: FFKind, ena: Boolean = false, aload: Boolean = false, sync: Boolean = false): QuartusPart {
        val p = QuartusPart(name, descr, STORAGE)
        val data = when (kind) {
            FFKind.D -> listOf("D")
            FFKind.T -> listOf("T")
            FFKind.JK -> listOf("J", "K")
            FFKind.SR -> listOf("S", "R")
        }
        data.forEach { p.input(it) }
        p.clock("CLK").input("CLRN", 1).input("PRN", 1)
        if (ena) p.input("ENA", 1)
        if (aload) p.input(if (sync) "ASDATA" else "ADATA").input("ALOAD")
        if (sync) p.input("SCLR").input("SLOAD")
        p.output("Q")
        val clk = data.size
        val names = p.inputs.map { it.name }
        return p.behavior {
            object : Behavior() {
                private val edge = Edge()
                private var q = 0L
                override fun eval(i: LongArray, o: LongArray) {
                    val rising = edge.rising(i[clk])
                    when {
                        !i[clk + 1].isSet() -> q = 0
                        !i[clk + 2].isSet() -> q = 1
                        aload && i[clk + 5].isSet() -> q = i[clk + 4] and 1
                        rising && (!ena || i[clk + 3].isSet()) -> q = when {
                            sync && i[clk + 6].isSet() -> 0
                            sync && i[clk + 7].isSet() -> i[clk + 4] and 1
                            else -> next(kind, q, i)
                        }
                    }
                    o[0] = q
                }
            }
        }.symbol { prm -> SymbolFactory.flipFlop(name, names.filter { prm.isPortUsed(it) }, setOf("CLK"), listOf("Q")) }
    }

    private fun next(kind: FFKind, q: Long, i: LongArray): Long = when (kind) {
        FFKind.D -> i[0] and 1
        FFKind.T -> if (i[0].isSet()) q xor 1 else q
        FFKind.JK -> when {
            i[0].isSet() && i[1].isSet() -> q xor 1
            i[0].isSet() -> 1
            i[1].isSet() -> 0
            else -> q
        }
        FFKind.SR -> when {
            i[0].isSet() -> 1
            i[1].isSet() -> 0
            else -> q
        }
    }
}
