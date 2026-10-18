/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib

/**
 * The 74xx macrofunctions of the Quartus library "others/maxplus2".
 * The port names follow the Quartus/TI conventions, active low ports end with "N".
 */
internal object Macrofunctions {
    private const val CAT = "others/maxplus2"

    private val SEVEN_SEG = intArrayOf(
        0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011, 0b1011011, 0b0011111, 0b1110000,
        0b1111111, 0b1110011, 0b0001101, 0b0011001, 0b0100011, 0b1001011, 0b0001111, 0b0000000)

    private fun part(name: String, descr: String) = QuartusPart(name, descr, CAT)

    /** reads n single bit inputs starting at index a, the first one is the lsb */
    private fun word(i: LongArray, a: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = v or ((i[a + k] and 1) shl k)
        return v
    }

    /** writes n single bit outputs starting at index a, the first one is the lsb */
    private fun setWord(o: LongArray, a: Int, n: Int, v: Long) {
        for (k in 0 until n) o[a + k] = (v ushr k) and 1
    }

    private fun names(prefix: String, from: Int, to: Int, suffix: String = "") =
        (from..to).map { "$prefix$it$suffix" }.toTypedArray()

    private fun letters(prefix: String, n: Int, suffix: String = "") =
        (0 until n).map { "$prefix${'A' + it}$suffix" }.toTypedArray()

    fun create(add: (QuartusPart) -> Unit) {
        createGates(add)
        createDecoders(add)
        createArithmetic(add)
        createFlipFlops(add)
        createCounters(add)
        createShiftRegisters(add)
        createBusDrivers(add)
    }

    // --------------------------------------------------------------------------------------
    // gate packages
    // --------------------------------------------------------------------------------------

    private fun createGates(add: (QuartusPart) -> Unit) {
        gatePackage(add, "7400", "Quad 2-input NAND gate", 4, 2) { v, n -> (v != mask(n)).toBit() }
        gatePackage(add, "7402", "Quad 2-input NOR gate", 4, 2) { v, _ -> (v == 0L).toBit() }
        gatePackage(add, "7404", "Hex inverter", 6, 1) { v, _ -> v xor 1 }
        gatePackage(add, "7408", "Quad 2-input AND gate", 4, 2) { v, n -> (v == mask(n)).toBit() }
        gatePackage(add, "7410", "Triple 3-input NAND gate", 3, 3) { v, n -> (v != mask(n)).toBit() }
        gatePackage(add, "7411", "Triple 3-input AND gate", 3, 3) { v, n -> (v == mask(n)).toBit() }
        gatePackage(add, "7414", "Hex Schmitt trigger inverter", 6, 1) { v, _ -> v xor 1 }
        gatePackage(add, "7420", "Dual 4-input NAND gate", 2, 4) { v, n -> (v != mask(n)).toBit() }
        gatePackage(add, "7421", "Dual 4-input AND gate", 2, 4) { v, n -> (v == mask(n)).toBit() }
        gatePackage(add, "7427", "Triple 3-input NOR gate", 3, 3) { v, _ -> (v == 0L).toBit() }
        gatePackage(add, "7432", "Quad 2-input OR gate", 4, 2) { v, _ -> (v != 0L).toBit() }
        gatePackage(add, "7486", "Quad 2-input exclusive OR gate", 4, 2) { v, _ -> java.lang.Long.bitCount(v).toLong() and 1 }
        gatePackage(add, "74266", "Quad 2-input exclusive NOR gate", 4, 2) { v, _ -> (java.lang.Long.bitCount(v).toLong() and 1) xor 1 }
        add(part("7430", "8-input NAND gate").inputs(*letters("", 8)).output("Y")
            .comb { i, o -> o[0] = (word(i, 0, 8) != 0xFFL).toBit() })
        add(part("74133", "13-input NAND gate").inputs(*letters("", 13)).output("Y")
            .comb { i, o -> o[0] = (word(i, 0, 13) != 0x1FFFL).toBit() })
    }

    /* a package of gates with the ports 1A, 1B, ..., 1Y, 2A, ... */
    private fun gatePackage(add: (QuartusPart) -> Unit, name: String, descr: String, gates: Int, inputs: Int, f: (Long, Int) -> Long) {
        val p = part(name, descr)
        for (g in 1..gates) p.inputs(*letters("$g", inputs))
        for (g in 1..gates) p.output("${g}Y")
        add(p.comb { i, o ->
            for (g in 0 until gates) o[g] = f(word(i, g * inputs, inputs), inputs)
        })
    }

    // --------------------------------------------------------------------------------------
    // decoders, encoders and multiplexers
    // --------------------------------------------------------------------------------------

    private fun createDecoders(add: (QuartusPart) -> Unit) {
        add(part("7442", "BCD to decimal decoder").inputs("A", "B", "C", "D").outputs(*names("O", 0, 9, "N"))
            .comb { i, o ->
                val v = word(i, 0, 4)
                for (k in 0..9) o[k] = (v != k.toLong()).toBit()
            })
        for ((n, low) in listOf("7446" to true, "7447" to true, "7448" to false, "7449" to false))
            add(sevenSeg(n, low))
        add(part("74138", "3-to-8 line decoder with active low outputs")
            .inputs("A", "B", "C").input("G1", 1).inputs("G2AN", "G2BN").outputs(*names("Y", 0, 7, "N"))
            .comb { i, o ->
                val en = i[3].isSet() && !i[4].isSet() && !i[5].isSet()
                val sel = word(i, 0, 3)
                for (k in 0..7) o[k] = (!(en && k.toLong() == sel)).toBit()
            })
        add(part("74139", "Dual 2-to-4 line decoder")
            .inputs("1GN", "1A", "1B", "2GN", "2A", "2B").outputs(*names("1Y", 0, 3, "N")).outputs(*names("2Y", 0, 3, "N"))
            .comb { i, o ->
                for (g in 0..1) {
                    val sel = word(i, g * 3 + 1, 2)
                    for (k in 0..3) o[g * 4 + k] = (!(!i[g * 3].isSet() && k.toLong() == sel)).toBit()
                }
            })
        add(part("74154", "4-to-16 line decoder").inputs("A", "B", "C", "D", "G1N", "G2N").outputs(*names("O", 0, 15, "N"))
            .comb { i, o ->
                val en = !i[4].isSet() && !i[5].isSet()
                val sel = word(i, 0, 4)
                for (k in 0..15) o[k] = (!(en && k.toLong() == sel)).toBit()
            })
        add(part("74147", "10-to-4 line priority encoder").inputs(*names("", 1, 9, "N")).outputs("AN", "BN", "CN", "DN")
            .comb { i, o ->
                var v = 0
                for (k in 9 downTo 1) if (!i[k - 1].isSet()) {
                    v = k
                    break
                }
                setWord(o, 0, 4, v.toLong().inv())
            })
        add(part("74148", "8-to-3 line priority encoder").inputs(*names("", 0, 7, "N")).input("EIN")
            .outputs("A0N", "A1N", "A2N", "GSN", "EON")
            .comb { i, o ->
                if (i[8].isSet()) {
                    setWord(o, 0, 5, 0x1F)
                    return@comb
                }
                var v = -1
                for (k in 7 downTo 0) if (!i[k].isSet()) {
                    v = k
                    break
                }
                if (v < 0) {
                    setWord(o, 0, 3, 7)
                    o[3] = 1
                    o[4] = 0
                } else {
                    setWord(o, 0, 3, v.toLong().inv())
                    o[3] = 0
                    o[4] = 1
                }
            })
        add(part("74150", "16-to-1 multiplexer with inverted output").inputs(*names("E", 0, 15)).inputs("A", "B", "C", "D", "GN")
            .output("WN")
            .comb { i, o -> o[0] = if (i[20].isSet()) 1 else (i[word(i, 16, 4).toInt()] and 1) xor 1 })
        add(part("74151", "8-to-1 multiplexer").inputs("A", "B", "C", "GN").inputs(*names("D", 0, 7)).outputs("Y", "WN")
            .comb { i, o ->
                val y = if (i[3].isSet()) 0 else i[4 + word(i, 0, 3).toInt()] and 1
                o[0] = y
                o[1] = y xor 1
            })
        add(part("74153", "Dual 4-to-1 multiplexer").inputs("A", "B", "1GN", "1C0", "1C1", "1C2", "1C3", "2GN", "2C0", "2C1", "2C2", "2C3")
            .outputs("1Y", "2Y")
            .comb { i, o ->
                val sel = word(i, 0, 2).toInt()
                o[0] = if (i[2].isSet()) 0 else i[3 + sel] and 1
                o[1] = if (i[7].isSet()) 0 else i[8 + sel] and 1
            })
        for ((name, inv) in listOf("74157" to false, "74158" to true))
            add(part(name, "Quad 2-to-1 multiplexer" + if (inv) " with inverted outputs" else "")
                .inputs("SEL", "GN", "A1", "B1", "A2", "B2", "A3", "B3", "A4", "B4")
                .outputs(*(1..4).map { if (inv) "Y${it}N" else "Y$it" }.toTypedArray())
                .comb { i, o ->
                    for (k in 0..3) {
                        val y = if (i[1].isSet()) 0 else i[2 + k * 2 + (i[0] and 1).toInt()] and 1
                        o[k] = if (inv) y xor 1 else y
                    }
                })
        add(part("74180", "9-bit odd/even parity generator").inputs(*letters("", 8)).inputs("EVNI", "ODDI").outputs("EVNS", "ODDS")
            .comb { i, o ->
                val even = java.lang.Long.bitCount(word(i, 0, 8)) % 2 == 0
                val evnI = i[8].isSet()
                val oddI = i[9].isSet()
                when {
                    evnI && oddI -> setWord(o, 0, 2, 0)
                    !evnI && !oddI -> setWord(o, 0, 2, 3)
                    else -> {
                        val e = if (evnI) even else !even
                        o[0] = e.toBit()
                        o[1] = (!e).toBit()
                    }
                }
            })
        add(part("74280", "9-bit odd/even parity generator").inputs(*letters("", 9)).outputs("EVN", "ODD")
            .comb { i, o ->
                val odd = java.lang.Long.bitCount(word(i, 0, 9)) % 2 == 1
                o[0] = (!odd).toBit()
                o[1] = odd.toBit()
            })
    }

    private fun sevenSeg(name: String, activeLow: Boolean) =
        part(name, "BCD to 7-segment decoder" + if (activeLow) " with active low outputs" else "")
            .inputs("A", "B", "C", "D").input("LTN", 1).input("RBIN", 1).input("BIN", 1)
            .outputs("OA", "OB", "OC", "OD", "OE", "OF", "OG", "RBON")
            .comb { i, o ->
                val v = word(i, 0, 4).toInt()
                var rbo = 1L
                val seg = when {
                    !i[6].isSet() -> 0
                    !i[4].isSet() -> 0x7F
                    v == 0 && !i[5].isSet() -> {
                        rbo = 0
                        0
                    }
                    else -> SEVEN_SEG[v]
                }
                for (k in 0..6) {
                    val s = (seg ushr (6 - k)).toLong() and 1
                    o[k] = if (activeLow) s xor 1 else s
                }
                o[7] = rbo
            }

    // --------------------------------------------------------------------------------------
    // arithmetic
    // --------------------------------------------------------------------------------------

    private fun createArithmetic(add: (QuartusPart) -> Unit) {
        for ((name, cin, cout) in listOf(Triple("74283", "CIN", "COUT"), Triple("7483", "C0", "C4")))
            add(part(name, "4-bit binary full adder").inputs(*names("A", 1, 4)).inputs(*names("B", 1, 4)).input(cin)
                .outputs(*names("SUM", 1, 4)).output(cout)
                .comb { i, o ->
                    val s = word(i, 0, 4) + word(i, 4, 4) + (i[8] and 1)
                    setWord(o, 0, 4, s)
                    o[4] = (s ushr 4) and 1
                })
        add(part("7485", "4-bit magnitude comparator").inputs(*names("A", 0, 3)).inputs(*names("B", 0, 3))
            .input("ALBI").input("AEBI", 1).input("AGBI").outputs("ALBO", "AEBO", "AGBO")
            .comb { i, o ->
                val a = word(i, 0, 4)
                val b = word(i, 4, 4)
                when {
                    a < b -> setWord(o, 0, 3, 1)
                    a > b -> setWord(o, 0, 3, 4)
                    else -> {
                        o[0] = i[8] and 1
                        o[1] = i[9] and 1
                        o[2] = i[10] and 1
                    }
                }
            })
        add(part("74688", "8-bit identity comparator").inputs(*names("P", 0, 7)).inputs(*names("Q", 0, 7)).input("GN").output("PEQN")
            .comb { i, o -> o[0] = (i[16].isSet() || word(i, 0, 8) != word(i, 8, 8)).toBit() })
        add(part("74181", "4-bit arithmetic logic unit (active high data)")
            .inputs(*names("A", 0, 3)).inputs(*names("B", 0, 3)).inputs(*names("S", 0, 3)).input("M").input("CN", 1)
            .outputs(*names("F", 0, 3)).outputs("AEQB", "CN4", "PN", "GN")
            .comb { i, o ->
                val a = word(i, 0, 4)
                val b = word(i, 4, 4)
                val s = word(i, 8, 4).toInt()
                val logic = i[12].isSet()
                val carry = if (i[13].isSet()) 0L else 1L // CN is active low
                val nb = b.inv() and 0xF
                if (logic) {
                    val r = when (s) {
                        0 -> a.inv(); 1 -> (a or b).inv(); 2 -> a.inv() and b; 3 -> 0
                        4 -> (a and b).inv(); 5 -> nb; 6 -> a xor b; 7 -> a and nb
                        8 -> a.inv() or b; 9 -> (a xor b).inv(); 10 -> b; 11 -> a and b
                        12 -> 0xF; 13 -> a or nb; 14 -> a or b; else -> a
                    } and 0xF
                    setWord(o, 0, 4, r)
                    o[4] = (r == 0xFL).toBit()
                    o[5] = 1
                } else {
                    // the arithmetic functions are calculated as x plus y plus carry, like the hardware does
                    val (x, y) = when (s) {
                        0 -> a to 0L; 1 -> (a or b) to 0L; 2 -> (a or nb) to 0L; 3 -> 0L to 0xFL
                        4 -> a to (a and nb); 5 -> (a or b) to (a and nb); 6 -> a to nb; 7 -> (a and nb) to 0xFL
                        8 -> a to (a and b); 9 -> a to b; 10 -> (a or nb) to (a and b); 11 -> (a and b) to 0xFL
                        12 -> a to a; 13 -> (a or b) to a; 14 -> (a or nb) to a; else -> a to 0xFL
                    }
                    val sum = (x and 0xF) + (y and 0xF) + carry
                    setWord(o, 0, 4, sum)
                    o[4] = ((sum and 0xF) == 0xFL).toBit()
                    o[5] = ((sum ushr 4) and 1) xor 1
                }
                o[6] = 1
                o[7] = 1
            })
    }

    // --------------------------------------------------------------------------------------
    // flip-flops and latches
    // --------------------------------------------------------------------------------------

    private fun createFlipFlops(add: (QuartusPart) -> Unit) {
        add(part("7474", "Dual D flip-flop with preset and clear")
            .input("1CLRN", 1).input("1D").clock("1CLK").input("1PRN", 1)
            .input("2CLRN", 1).input("2D").clock("2CLK").input("2PRN", 1)
            .outputs("1Q", "1QN", "2Q", "2QN")
            .behavior {
                object : Behavior() {
                    private val e = arrayOf(Edge(), Edge())
                    private val q = LongArray(2)
                    override fun eval(i: LongArray, o: LongArray) {
                        for (g in 0..1) {
                            val b = g * 4
                            val r = e[g].rising(i[b + 2])
                            q[g] = when {
                                !i[b].isSet() -> 0
                                !i[b + 3].isSet() -> 1
                                r -> i[b + 1] and 1
                                else -> q[g]
                            }
                            o[g * 2] = q[g]
                            o[g * 2 + 1] = q[g] xor 1
                        }
                    }
                }
            })
        // dual JK flip-flops: name, falling edge, preset, J-K(bar)
        for ((name, falling, preset, kBar) in listOf(
            JK("7476", true, true, false), JK("74107", true, false, false),
            JK("74109", false, true, true), JK("74112", true, true, false))) {
            val p = part(name, "Dual JK flip-flop" + (if (kBar) " (J-K bar)" else "") +
                (if (falling) ", negative edge" else ", positive edge") + if (preset) ", preset and clear" else ", clear")
            for (g in 1..2) {
                p.input("${g}J").input(if (kBar) "${g}KN" else "${g}K").clock("${g}CLK").input("${g}CLRN", 1)
                p.input("${g}PRN", 1)
            }
            p.outputs("1Q", "1QN", "2Q", "2QN")
            add(p.behavior {
                object : Behavior() {
                    private val e = arrayOf(Edge(), Edge())
                    private val q = LongArray(2)
                    override fun eval(i: LongArray, o: LongArray) {
                        for (g in 0..1) {
                            val b = g * 5
                            val edge = if (falling) e[g].falling(i[b + 2]) else e[g].rising(i[b + 2])
                            val j = i[b].isSet()
                            val k = if (kBar) !i[b + 1].isSet() else i[b + 1].isSet()
                            q[g] = when {
                                !i[b + 3].isSet() -> 0
                                preset && !i[b + 4].isSet() -> 1
                                edge && j && k -> q[g] xor 1
                                edge && j -> 1
                                edge && k -> 0
                                else -> q[g]
                            }
                            o[g * 2] = q[g]
                            o[g * 2 + 1] = q[g] xor 1
                        }
                    }
                }
            })
        }
        add(part("7475", "Quad bistable latch").inputs("1D", "2D", "3D", "4D", "1C2C", "3C4C")
            .outputs("1Q", "1QN", "2Q", "2QN", "3Q", "3QN", "4Q", "4QN")
            .behavior {
                object : Behavior() {
                    private val q = LongArray(4)
                    override fun eval(i: LongArray, o: LongArray) {
                        for (k in 0..3) {
                            if (i[4 + k / 2].isSet()) q[k] = i[k] and 1
                            o[k * 2] = q[k]
                            o[k * 2 + 1] = q[k] xor 1
                        }
                    }
                }
            })
        add(register("74174", "Hex D flip-flop with clear", 6, clear = true))
        add(register("74175", "Quad D flip-flop with clear", 4, clear = true, inverted = true))
        add(register("74273", "Octal D flip-flop with clear", 8, clear = true, prefixData = "D", prefixQ = "Q", firstIndex = 1))
        add(register("74377", "Octal D flip-flop with enable", 8, enable = true, prefixData = "D", prefixQ = "Q", firstIndex = 1))
        add(register("74374", "Octal D flip-flop with tri-state outputs", 8, tristate = true, prefixData = "D", prefixQ = "Q", firstIndex = 1))
        add(part("74373", "Octal transparent latch with tri-state outputs").input("OEN").input("G").inputs(*names("D", 1, 8))
            .outputs(*names("Q", 1, 8))
            .behavior {
                object : Behavior() {
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        if (i[1].isSet()) q = word(i, 2, 8)
                        setWord(o, 0, 8, q)
                        for (k in 0..7) highZ[k] = i[0] and 1
                    }
                }
            })
    }

    private data class JK(val name: String, val falling: Boolean, val preset: Boolean, val kBar: Boolean)

    private fun register(
        name: String, descr: String, n: Int, clear: Boolean = false, enable: Boolean = false, tristate: Boolean = false,
        inverted: Boolean = false, prefixData: String = "", prefixQ: String = "", firstIndex: Int = 1
    ): QuartusPart {
        val p = part(name, descr)
        if (clear) p.input("CLRN", 1)
        if (enable) p.input("GN")
        if (tristate) p.input("OEN")
        p.clock("CLK")
        val data = (firstIndex until firstIndex + n).map { if (prefixData.isEmpty()) "${it}D" else "$prefixData$it" }
        p.inputs(*data.toTypedArray())
        for (k in firstIndex until firstIndex + n) {
            p.output(if (prefixQ.isEmpty()) "${k}Q" else "$prefixQ$k")
            if (inverted) p.output(if (prefixQ.isEmpty()) "${k}QN" else "$prefixQ${k}N")
        }
        val clk = p.inputs.indexOfFirst { it.name == "CLK" }
        return p.behavior {
            object : Behavior() {
                private val edge = Edge()
                private var q = 0L
                override fun eval(i: LongArray, o: LongArray) {
                    val r = edge.rising(i[clk])
                    if (clear && !i[0].isSet()) q = 0
                    else if (r && (!enable || !i[if (clear) 1 else 0].isSet())) q = word(i, clk + 1, n)
                    for (k in 0 until n) {
                        val b = (q ushr k) and 1
                        if (inverted) {
                            o[k * 2] = b
                            o[k * 2 + 1] = b xor 1
                        } else o[k] = b
                        if (tristate) highZ[k] = i[0] and 1
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------------------------------
    // counters
    // --------------------------------------------------------------------------------------

    private fun createCounters(add: (QuartusPart) -> Unit) {
        for ((name, sync, mod) in listOf(Triple("74160", false, 10), Triple("74161", false, 16), Triple("74162", true, 10), Triple("74163", true, 16)))
            add(part(name, (if (mod == 10) "Decade" else "4-bit binary") + " counter with " + (if (sync) "synchronous" else "asynchronous") + " clear")
                .input("CLRN", 1).input("LDN", 1).input("ENP", 1).input("ENT", 1).clock("CLK").inputs("A", "B", "C", "D")
                .outputs("QA", "QB", "QC", "QD", "RCO")
                .behavior {
                    object : Behavior() {
                        private val edge = Edge()
                        private var q = 0L
                        override fun eval(i: LongArray, o: LongArray) {
                            val rising = edge.rising(i[4])
                            if (!sync && !i[0].isSet()) q = 0
                            else if (rising) q = when {
                                !i[0].isSet() -> 0
                                !i[1].isSet() -> word(i, 5, 4)
                                i[2].isSet() && i[3].isSet() -> if (q >= mod - 1) 0 else q + 1
                                else -> q
                            }
                            setWord(o, 0, 4, q)
                            o[4] = (i[3].isSet() && q == mod - 1L).toBit()
                        }
                    }
                })
        for ((name, mod) in listOf("74168" to 10, "74169" to 16))
            add(part(name, (if (mod == 10) "Decade" else "4-bit binary") + " synchronous up/down counter")
                .input("LDN", 1).input("UDN", 1).input("ENPN").input("ENTN").clock("CLK").inputs("A", "B", "C", "D")
                .outputs("QA", "QB", "QC", "QD", "RCON")
                .behavior {
                    object : Behavior() {
                        private val edge = Edge()
                        private var q = 0L
                        override fun eval(i: LongArray, o: LongArray) {
                            val up = i[1].isSet()
                            if (edge.rising(i[4])) q = when {
                                !i[0].isSet() -> word(i, 5, 4)
                                !i[2].isSet() && !i[3].isSet() -> if (up) (if (q >= mod - 1) 0 else q + 1) else (if (q == 0L) mod - 1L else q - 1)
                                else -> q
                            }
                            setWord(o, 0, 4, q)
                            o[4] = (!(!i[3].isSet() && (if (up) q == mod - 1L else q == 0L))).toBit()
                        }
                    }
                })
        for ((name, mod) in listOf("74190" to 10, "74191" to 16))
            add(part(name, (if (mod == 10) "Decade" else "4-bit binary") + " up/down counter")
                .inputs("GN", "DNUP").input("LDN", 1).clock("CLK").inputs("A", "B", "C", "D")
                .outputs("QA", "QB", "QC", "QD", "MXMN", "RCON")
                .behavior {
                    object : Behavior() {
                        private val edge = Edge()
                        private var q = 0L
                        override fun eval(i: LongArray, o: LongArray) {
                            val rising = edge.rising(i[3])
                            val down = i[1].isSet()
                            if (!i[2].isSet()) q = word(i, 4, 4)
                            else if (rising && !i[0].isSet())
                                q = if (down) (if (q == 0L) mod - 1L else q - 1) else (if (q >= mod - 1) 0 else q + 1)
                            setWord(o, 0, 4, q)
                            val mxmn = if (down) q == 0L else q == mod - 1L
                            o[4] = mxmn.toBit()
                            o[5] = (!(mxmn && !i[0].isSet() && !i[3].isSet())).toBit()
                        }
                    }
                })
        for ((name, mod) in listOf("74192" to 10, "74193" to 16))
            add(part(name, (if (mod == 10) "Decade" else "4-bit binary") + " up/down counter with dual clock")
                .clock("UP").clock("DN").input("CLR").input("LDN", 1).inputs("A", "B", "C", "D")
                .outputs("QA", "QB", "QC", "QD", "CON", "BON")
                .behavior {
                    object : Behavior() {
                        private val up = Edge()
                        private val dn = Edge()
                        private var q = 0L
                        override fun eval(i: LongArray, o: LongArray) {
                            val ru = up.rising(i[0])
                            val rd = dn.rising(i[1])
                            q = when {
                                i[2].isSet() -> 0
                                !i[3].isSet() -> word(i, 4, 4)
                                ru && i[1].isSet() -> if (q >= mod - 1) 0 else q + 1
                                rd && i[0].isSet() -> if (q == 0L) mod - 1L else q - 1
                                else -> q
                            }
                            setWord(o, 0, 4, q)
                            o[4] = (!(q == mod - 1L && !i[0].isSet())).toBit()
                            o[5] = (!(q == 0L && !i[1].isSet())).toBit()
                        }
                    }
                })
        add(rippleCounter("7493", "4-bit binary ripple counter", 8, false))
        add(rippleCounter("7490", "Decade ripple counter", 5, true))
        add(part("7492", "Divide by twelve ripple counter").clock("CLKA").clock("CLKB").inputs("RO1", "RO2")
            .outputs("QA", "QB", "QC", "QD")
            .behavior {
                object : Behavior() {
                    private val ea = Edge()
                    private val eb = Edge()
                    private var qa = 0L
                    private var qb = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val fa = ea.falling(i[0])
                        val fb = eb.falling(i[1])
                        if (i[2].isSet() && i[3].isSet()) {
                            qa = 0
                            qb = 0
                        } else {
                            if (fa) qa = qa xor 1
                            if (fb) qb = (qb + 1) % 6
                        }
                        o[0] = qa
                        o[1] = qb % 3 and 1
                        o[2] = (qb % 3 ushr 1) and 1
                        o[3] = (qb / 3) and 1
                    }
                }
            })
        add(part("74393", "Dual 4-bit binary counter").clock("1CLK").input("1CLR").clock("2CLK").input("2CLR")
            .outputs("1QA", "1QB", "1QC", "1QD", "2QA", "2QB", "2QC", "2QD")
            .behavior {
                object : Behavior() {
                    private val e = arrayOf(Edge(), Edge())
                    private val q = LongArray(2)
                    override fun eval(i: LongArray, o: LongArray) {
                        for (g in 0..1) {
                            val f = e[g].falling(i[g * 2])
                            if (i[g * 2 + 1].isSet()) q[g] = 0 else if (f) q[g] = (q[g] + 1) and 0xF
                            setWord(o, g * 4, 4, q[g])
                        }
                    }
                }
            })
        add(part("74390", "Dual decade counter").clock("1CLKA").clock("1CLKB").input("1CLR").clock("2CLKA").clock("2CLKB").input("2CLR")
            .outputs("1QA", "1QB", "1QC", "1QD", "2QA", "2QB", "2QC", "2QD")
            .behavior {
                object : Behavior() {
                    private val ea = arrayOf(Edge(), Edge())
                    private val eb = arrayOf(Edge(), Edge())
                    private val qa = LongArray(2)
                    private val qb = LongArray(2)
                    override fun eval(i: LongArray, o: LongArray) {
                        for (g in 0..1) {
                            val fa = ea[g].falling(i[g * 3])
                            val fb = eb[g].falling(i[g * 3 + 1])
                            if (i[g * 3 + 2].isSet()) {
                                qa[g] = 0
                                qb[g] = 0
                            } else {
                                if (fa) qa[g] = qa[g] xor 1
                                if (fb) qb[g] = (qb[g] + 1) % 5
                            }
                            o[g * 4] = qa[g]
                            setWord(o, g * 4 + 1, 3, qb[g])
                        }
                    }
                }
            })
    }

    private fun rippleCounter(name: String, descr: String, modB: Int, setNine: Boolean): QuartusPart {
        val p = part(name, descr).clock("CLKA").clock("CLKB").inputs("RO1", "RO2")
        if (setNine) p.inputs("RS1", "RS2")
        return p.outputs("QA", "QB", "QC", "QD").behavior {
            object : Behavior() {
                private val ea = Edge()
                private val eb = Edge()
                private var qa = 0L
                private var qb = 0L
                override fun eval(i: LongArray, o: LongArray) {
                    val fa = ea.falling(i[0])
                    val fb = eb.falling(i[1])
                    when {
                        setNine && i[4].isSet() && i[5].isSet() -> {
                            qa = 1
                            qb = 4
                        }
                        i[2].isSet() && i[3].isSet() -> {
                            qa = 0
                            qb = 0
                        }
                        else -> {
                            if (fa) qa = qa xor 1
                            if (fb) qb = (qb + 1) % modB
                        }
                    }
                    o[0] = qa
                    setWord(o, 1, 3, qb)
                }
            }
        }
    }

    // --------------------------------------------------------------------------------------
    // shift registers
    // --------------------------------------------------------------------------------------

    private fun createShiftRegisters(add: (QuartusPart) -> Unit) {
        for ((name, n) in listOf("74194" to 4, "74198" to 8))
            add(part(name, "$n-bit bidirectional universal shift register")
                .input("CLRN", 1).inputs("S1", "S0", "SRSI", "SLSI").clock("CLK").inputs(*letters("", n))
                .outputs(*letters("Q", n))
                .behavior {
                    object : Behavior() {
                        private val edge = Edge()
                        private var q = 0L
                        override fun eval(i: LongArray, o: LongArray) {
                            val rising = edge.rising(i[5])
                            if (!i[0].isSet()) q = 0
                            else if (rising) q = when (word(i, 1, 2).let { ((it and 1) shl 1) or (it ushr 1) }.toInt()) {
                                1 -> ((q shl 1) or (i[3] and 1)) and mask(n)
                                2 -> (q ushr 1) or ((i[4] and 1) shl (n - 1))
                                3 -> word(i, 6, n)
                                else -> q
                            }
                            setWord(o, 0, n, q)
                        }
                    }
                })
        add(part("74195", "4-bit parallel access shift register").inputs("J", "KN", "SHLDN").input("CLRN", 1).clock("CLK")
            .inputs("A", "B", "C", "D").outputs("QA", "QB", "QC", "QD", "QDN")
            .behavior {
                object : Behavior() {
                    private val edge = Edge()
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val rising = edge.rising(i[4])
                        if (!i[3].isSet()) q = 0
                        else if (rising) q = if (!i[2].isSet()) word(i, 5, 4) else {
                            val qa = q and 1
                            val j = i[0].isSet()
                            val kn = i[1].isSet()
                            val nqa = when {
                                j && kn -> 1L
                                !j && !kn -> 0L
                                j -> qa xor 1
                                else -> qa
                            }
                            ((q shl 1) or nqa) and 0xF
                        }
                        setWord(o, 0, 4, q)
                        o[4] = ((q ushr 3) and 1) xor 1
                    }
                }
            })
        add(part("74164", "8-bit serial in, parallel out shift register").inputs("A", "B").input("CLRN", 1).clock("CLK")
            .outputs(*letters("Q", 8))
            .behavior {
                object : Behavior() {
                    private val edge = Edge()
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val rising = edge.rising(i[3])
                        if (!i[2].isSet()) q = 0 else if (rising) q = ((q shl 1) or (i[0] and i[1] and 1)) and 0xFF
                        setWord(o, 0, 8, q)
                    }
                }
            })
        add(part("74165", "8-bit parallel in, serial out shift register").input("SHLDN", 1).clock("CLK").inputs("CLKINH", "SER")
            .inputs(*letters("", 8)).outputs("QH", "QHN")
            .behavior {
                object : Behavior() {
                    private val edge = Edge()
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val rising = edge.rising(i[1])
                        if (!i[0].isSet()) q = word(i, 4, 8)
                        else if (rising && !i[2].isSet()) q = ((q shl 1) or (i[3] and 1)) and 0xFF
                        o[0] = (q ushr 7) and 1
                        o[1] = o[0] xor 1
                    }
                }
            })
        add(part("74595", "8-bit shift register with output latches").input("SER").clock("SRCLK").input("SRCLRN", 1).clock("RCLK").input("GN")
            .outputs(*letters("Q", 8)).output("QHS")
            .behavior {
                object : Behavior() {
                    private val sr = Edge()
                    private val rc = Edge()
                    private var shift = 0L
                    private var latch = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val rs = sr.rising(i[1])
                        val rr = rc.rising(i[3])
                        if (rr) latch = shift
                        if (!i[2].isSet()) shift = 0 else if (rs) shift = ((shift shl 1) or (i[0] and 1)) and 0xFF
                        setWord(o, 0, 8, latch)
                        for (k in 0..7) highZ[k] = i[4] and 1
                        o[8] = (shift ushr 7) and 1
                    }
                }
            })
    }

    // --------------------------------------------------------------------------------------
    // bus drivers
    // --------------------------------------------------------------------------------------

    private fun createBusDrivers(add: (QuartusPart) -> Unit) {
        for ((name, inv) in listOf("74244" to false, "74240" to true)) {
            add(part(name, "Octal tri-state buffer" + if (inv) " (inverting)" else "")
                .inputs("1GN", "2GN").inputs(*names("1A", 1, 4)).inputs(*names("2A", 1, 4))
                .outputs(*names("1Y", 1, 4)).outputs(*names("2Y", 1, 4))
                .behavior {
                    object : Behavior() {
                        override fun eval(i: LongArray, o: LongArray) {
                            for (k in 0..7) {
                                val v = i[2 + k] and 1
                                o[k] = if (inv) v xor 1 else v
                                highZ[k] = i[k / 4] and 1
                            }
                        }
                    }
                })
        }
        add(part("74541", "Octal buffer with tri-state outputs").inputs("G1N", "G2N").inputs(*names("A", 1, 8)).outputs(*names("Y", 1, 8))
            .behavior {
                object : Behavior() {
                    override fun eval(i: LongArray, o: LongArray) {
                        val z = (i[0] or i[1]) and 1
                        for (k in 0..7) {
                            o[k] = i[2 + k] and 1
                            highZ[k] = z
                        }
                    }
                }
            })
    }
}
