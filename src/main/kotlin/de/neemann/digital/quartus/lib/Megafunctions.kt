/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib

import de.neemann.digital.core.Bits
import de.neemann.digital.quartus.MemoryFile
import de.neemann.digital.quartus.lib.QuartusPart.Companion.w
import java.io.File
import java.io.IOException

/**
 * The megafunctions of the Quartus library (LPM and Altera specific functions).
 */
internal object Megafunctions {
    private const val ARITH = "megafunctions/arithmetic"
    private const val GATES = "megafunctions/gates"
    private const val STORAGE = "megafunctions/storage"
    private const val IO = "megafunctions/IO"
    private const val LPM_WIDTH = "LPM_WIDTH"
    private val width = w(LPM_WIDTH, 8)

    fun create(add: (QuartusPart) -> Unit) {
        createRegisters(add)
        createArithmetic(add)
        createGates(add)
        createMemories(add)
        createIO(add)
    }

    private fun createRegisters(add: (QuartusPart) -> Unit) {
        add(QuartusPart("LPM_COUNTER", "Binary counter", ARITH)
            .input("data", 0, width).clock("clock").input("clk_en", 1).input("cnt_en", 1).input("updown", 1)
            .input("cin", 1).inputs("aclr", "aset", "aload", "sclr", "sset", "sload")
            .output("q", width).output("cout")
            .defaults("LPM_WIDTH=8;PORTS=clock,cnt_en,sclr,q,cout")
            .behavior { p ->
                object : Behavior() {
                    private val mask = mask(width(p))
                    private val modulus = p.getLong("LPM_MODULUS", 0)
                    private val max = if (modulus > 0) modulus - 1 else mask
                    private val dir = p.get("LPM_DIRECTION", "UNUSED").uppercase()
                    private val avalue = p.getLong("LPM_AVALUE", mask)
                    private val svalue = p.getLong("LPM_SVALUE", mask)
                    private val edge = Edge()
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val up = dir == "UP" || (dir != "DOWN" && i[4].isSet())
                        val rising = edge.rising(i[1])
                        when {
                            i[6].isSet() -> q = 0
                            i[7].isSet() -> q = avalue and mask
                            i[8].isSet() -> q = i[0] and mask
                            rising && i[2].isSet() -> when {
                                i[9].isSet() -> q = 0
                                i[10].isSet() -> q = svalue and mask
                                i[11].isSet() -> q = i[0] and mask
                                i[3].isSet() && i[5].isSet() -> q = if (up) (if (q >= max) 0 else q + 1) else (if (q == 0L) max else q - 1)
                            }
                        }
                        o[0] = q
                        o[1] = (i[5].isSet() && if (up) q == max else q == 0L).toBit()
                    }
                }
            })

        add(QuartusPart("LPM_SHIFTREG", "Shift register", STORAGE)
            .input("data", 0, width).clock("clock").input("enable", 1).input("shiftin", 1)
            .inputs("load", "aclr", "aset", "sclr", "sset")
            .output("q", width).output("shiftout")
            .defaults("LPM_WIDTH=8;PORTS=data,clock,shiftin,load,q,shiftout")
            .behavior { p ->
                object : Behavior() {
                    private val bits = width(p)
                    private val mask = mask(bits)
                    private val right = p.get("LPM_DIRECTION", "LEFT").equals("RIGHT", true)
                    private val avalue = p.getLong("LPM_AVALUE", mask)
                    private val svalue = p.getLong("LPM_SVALUE", mask)
                    private val edge = Edge()
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val rising = edge.rising(i[1])
                        when {
                            i[5].isSet() -> q = 0
                            i[6].isSet() -> q = avalue and mask
                            rising && i[2].isSet() -> q = when {
                                i[7].isSet() -> 0
                                i[8].isSet() -> svalue and mask
                                i[4].isSet() -> i[0] and mask
                                right -> (q ushr 1) or ((i[3] and 1) shl (bits - 1))
                                else -> ((q shl 1) or (i[3] and 1)) and mask
                            }
                        }
                        o[0] = q
                        o[1] = if (right) q and 1 else (q ushr (bits - 1)) and 1
                    }
                }
            })

        add(QuartusPart("LPM_FF", "D or T flip-flop register", STORAGE)
            .input("data", 0, width).clock("clock").input("enable", 1)
            .inputs("aclr", "aset", "aload", "sclr", "sset", "sload")
            .output("q", width)
            .defaults("LPM_WIDTH=8;PORTS=data,clock,q")
            .behavior { p ->
                object : Behavior() {
                    private val mask = mask(width(p))
                    private val tff = p.get("LPM_FFTYPE", "DFF").equals("TFF", true)
                    private val avalue = p.getLong("LPM_AVALUE", mask)
                    private val svalue = p.getLong("LPM_SVALUE", mask)
                    private val edge = Edge()
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val rising = edge.rising(i[1])
                        when {
                            i[3].isSet() -> q = 0
                            i[4].isSet() -> q = avalue and mask
                            i[5].isSet() -> q = i[0] and mask
                            rising && i[2].isSet() -> q = when {
                                i[6].isSet() -> 0
                                i[7].isSet() -> svalue and mask
                                i[8].isSet() -> i[0] and mask
                                tff -> (q xor i[0]) and mask
                                else -> i[0] and mask
                            }
                        }
                        o[0] = q
                    }
                }
            })

        add(QuartusPart("LPM_LATCH", "Latch", STORAGE)
            .input("data", 0, width).inputs("gate", "aclr", "aset")
            .output("q", width)
            .defaults("LPM_WIDTH=8")
            .behavior { p ->
                object : Behavior() {
                    private val mask = mask(width(p))
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        when {
                            i[2].isSet() -> q = 0
                            i[3].isSet() -> q = mask
                            i[1].isSet() -> q = i[0] and mask
                        }
                        o[0] = q
                    }
                }
            })
    }

    private fun createArithmetic(add: (QuartusPart) -> Unit) {
        add(QuartusPart("LPM_ADD_SUB", "Adder/Subtractor", ARITH)
            .input("dataa", 0, width).input("datab", 0, width).input("cin", 2).input("add_sub", 1)
            .output("result", width).output("cout").output("overflow")
            .defaults("LPM_WIDTH=8")
            .behavior { p ->
                val bits = width(p)
                val mask = mask(bits)
                val dir = p.get("LPM_DIRECTION", "UNUSED").uppercase()
                Behavior { i, o ->
                    val add = dir == "ADD" || (dir != "SUB" && i[3].isSet())
                    val a = i[0] and mask
                    val b = i[1] and mask
                    val cin = if (i[2] == 2L) (if (add) 0L else 1L) else i[2] and 1
                    val r = if (add) a + b + cin else a + (b.inv() and mask) + cin
                    o[0] = r and mask
                    o[1] = (r ushr bits) and 1
                    val sa = Bits.signExtend(a, bits)
                    val sb = Bits.signExtend(b, bits)
                    val sr = if (add) sa + sb + cin else sa - sb - (1 - cin)
                    o[2] = (sr < -(1L shl (bits - 1)) || sr > (1L shl (bits - 1)) - 1).toBit()
                }
            })

        add(QuartusPart("LPM_COMPARE", "Comparator", ARITH)
            .input("dataa", 0, width).input("datab", 0, width)
            .outputs("alb", "aeb", "agb", "ageb", "aneb", "aleb")
            .defaults("LPM_WIDTH=8")
            .behavior { p ->
                val bits = width(p)
                val signed = p.get("LPM_REPRESENTATION", "UNSIGNED").equals("SIGNED", true)
                Behavior { i, o ->
                    val c = if (signed) Bits.signExtend(i[0], bits).compareTo(Bits.signExtend(i[1], bits))
                    else java.lang.Long.compareUnsigned(i[0], i[1])
                    o[0] = (c < 0).toBit()
                    o[1] = (c == 0).toBit()
                    o[2] = (c > 0).toBit()
                    o[3] = (c >= 0).toBit()
                    o[4] = (c != 0).toBit()
                    o[5] = (c <= 0).toBit()
                }
            })

        add(QuartusPart("LPM_MULT", "Multiplier", ARITH)
            .input("dataa", 0, w("LPM_WIDTHA", 8)).input("datab", 0, w("LPM_WIDTHB", 8))
            .output("result", w("LPM_WIDTHP", 16))
            .defaults("LPM_WIDTHA=8;LPM_WIDTHB=8;LPM_WIDTHP=16")
            .behavior { p ->
                val wa = p.getInt("LPM_WIDTHA", 8)
                val wb = p.getInt("LPM_WIDTHB", 8)
                val m = mask(p.getInt("LPM_WIDTHP", 16))
                val signed = p.get("LPM_REPRESENTATION", "UNSIGNED").equals("SIGNED", true)
                Behavior { i, o ->
                    o[0] = if (signed) (Bits.signExtend(i[0], wa) * Bits.signExtend(i[1], wb)) and m else (i[0] * i[1]) and m
                }
            })

        add(QuartusPart("LPM_DIVIDE", "Divider", ARITH)
            .input("numer", 0, w("LPM_WIDTHN", 8)).input("denom", 1, w("LPM_WIDTHD", 8))
            .output("quotient", w("LPM_WIDTHN", 8)).output("remain", w("LPM_WIDTHD", 8))
            .defaults("LPM_WIDTHN=8;LPM_WIDTHD=8")
            .behavior { p ->
                val wn = p.getInt("LPM_WIDTHN", 8)
                val wd = p.getInt("LPM_WIDTHD", 8)
                val signed = p.get("LPM_NREPRESENTATION", "UNSIGNED").equals("SIGNED", true)
                Behavior { i, o ->
                    val n = if (signed) Bits.signExtend(i[0], wn) else i[0] and mask(wn)
                    val d = if (signed) Bits.signExtend(i[1], wd) else i[1] and mask(wd)
                    if (d == 0L) {
                        o[0] = mask(wn)
                        o[1] = n and mask(wd)
                    } else {
                        o[0] = (n / d) and mask(wn)
                        o[1] = (n % d) and mask(wd)
                    }
                }
            })

        add(QuartusPart("LPM_ABS", "Absolute value", ARITH)
            .input("data", 0, width).output("result", width).output("overflow")
            .defaults("LPM_WIDTH=8")
            .behavior { p ->
                val bits = width(p)
                Behavior { i, o ->
                    val v = Bits.signExtend(i[0], bits)
                    o[0] = Math.abs(v) and mask(bits)
                    o[1] = (v == -(1L shl (bits - 1))).toBit()
                }
            })

        add(QuartusPart("LPM_CLSHIFT", "Combinatorial shifter", ARITH)
            .input("data", 0, width).input("distance", 0, w("LPM_WIDTHDIST", 3)).input("direction")
            .output("result", width).output("overflow").output("underflow")
            .defaults("LPM_WIDTH=8;LPM_WIDTHDIST=3")
            .behavior { p ->
                val bits = width(p)
                val m = mask(bits)
                val type = p.get("LPM_SHIFTTYPE", "LOGICAL").uppercase()
                Behavior { i, o ->
                    val d = (i[1] % bits).toInt()
                    val right = i[2].isSet()
                    val v = i[0] and m
                    o[0] = when {
                        type == "ROTATE" && right -> ((v ushr d) or (v shl (bits - d))) and m
                        type == "ROTATE" -> ((v shl d) or (v ushr (bits - d))) and m
                        type == "ARITHMETIC" && right -> (Bits.signExtend(v, bits) shr i[1].toInt().coerceAtMost(63)) and m
                        right -> if (i[1] >= bits) 0 else v ushr i[1].toInt()
                        else -> if (i[1] >= bits) 0 else (v shl i[1].toInt()) and m
                    }
                    o[1] = (!right && type != "ROTATE" && i[1] > 0 && (v ushr (bits - minOf(i[1], bits.toLong()).toInt())) != 0L).toBit()
                    o[2] = (right && type != "ROTATE" && i[1] > 0 && (v and mask(minOf(i[1], bits.toLong()).toInt())) != 0L).toBit()
                }
            })
    }

    private fun createGates(add: (QuartusPart) -> Unit) {
        val size = w("LPM_SIZE", 2)
        val flat: (Params) -> Int = { width(it) * size(it) }
        for ((name, op) in listOf("LPM_AND" to 0, "LPM_OR" to 1, "LPM_XOR" to 2)) {
            add(QuartusPart(name, "Bitwise " + name.substring(4) + " of LPM_SIZE words", GATES)
                .input("data", 0, flat).output("result", width)
                .defaults("LPM_WIDTH=8;LPM_SIZE=2")
                .behavior { p ->
                    val bits = width(p)
                    val n = size(p)
                    Behavior { i, o ->
                        var r = if (op == 0) mask(bits) else 0L
                        for (k in 0 until n) {
                            val word = (i[0] ushr (k * bits)) and mask(bits)
                            r = when (op) {
                                0 -> r and word
                                1 -> r or word
                                else -> r xor word
                            }
                        }
                        o[0] = r
                    }
                })
        }

        add(QuartusPart("LPM_INV", "Inverter", GATES)
            .input("data", 0, width).output("result", width)
            .defaults("LPM_WIDTH=8")
            .behavior { p ->
                val m = mask(width(p))
                Behavior { i, o -> o[0] = i[0].inv() and m }
            })

        add(QuartusPart("LPM_CONSTANT", "Constant value", GATES)
            .output("result", width)
            .defaults("LPM_WIDTH=8;LPM_CVALUE=0")
            .behavior { p ->
                val v = p.getLong("LPM_CVALUE", 0) and mask(width(p))
                Behavior { _, o -> o[0] = v }
            })

        add(QuartusPart("LPM_DECODE", "Decoder", GATES)
            .input("data", 0, width).input("enable", 1)
            .output("eq", w("LPM_DECODES", 4))
            .defaults("LPM_WIDTH=2;LPM_DECODES=4")
            .comb { i, o -> o[0] = if (i[1].isSet() && i[0] < 64) 1L shl i[0].toInt() else 0 })

        // the data input is either a flattened 2D bus "data" or the single words data0x, data1x, ...
        val mux = QuartusPart("LPM_MUX", "Multiplexer", GATES)
            .input("data", 0, flat).input("sel", 0, w("LPM_WIDTHS", 1))
        for (k in 0 until 16) mux.input("data${k}x", 0, width)
        add(mux.output("result", width)
            .defaults("LPM_WIDTH=8;LPM_SIZE=2;LPM_WIDTHS=1;PORTS=data,sel,result")
            .behavior { p ->
                val bits = width(p)
                val n = size(p)
                val words = !p.isPortUsed("data")
                Behavior { i, o ->
                    val s = i[1].toInt()
                    o[0] = when {
                        s >= n -> 0
                        words -> if (s < 16) i[2 + s] else 0
                        else -> (i[0] ushr (s * bits)) and mask(bits)
                    }
                }
            })

        add(QuartusPart("BUSMUX", "Bus multiplexer", GATES)
            .input("dataa", 0, w("WIDTH", 8)).input("datab", 0, w("WIDTH", 8)).input("sel")
            .output("result", w("WIDTH", 8))
            .defaults("WIDTH=8")
            .comb { i, o -> o[0] = if (i[2].isSet()) i[1] else i[0] })

        add(QuartusPart("LPM_BUSTRI", "Tri-state bus buffer", GATES)
            .input("data", 0, width).input("enabledt")
            .output("tridata", width)
            .defaults("LPM_WIDTH=8")
            .behavior { p ->
                val m = mask(width(p))
                object : Behavior() {
                    override fun eval(i: LongArray, o: LongArray) {
                        o[0] = i[0]
                        highZ[0] = if (i[1].isSet()) 0 else m
                    }
                }
            })
    }

    /** a memory whose content is read from a mif or hex file */
    private abstract class MemoryBehavior(p: Params, fileParam: String, private val dataWidth: Int, private val depth: Int) : Behavior() {
        private val fileName = p.get(fileParam, "")
        protected var mem = LongArray(depth)

        override fun init(root: File?) {
            if (fileName.isEmpty() || fileName.equals("UNUSED", true)) return
            var f = File(fileName)
            if (!f.isAbsolute && root != null) f = File(root, fileName)
            if (!f.exists() && root != null) f = File(root.parentFile, fileName)
            try {
                mem = MemoryFile.read(f, dataWidth, depth).copyOf(depth)
                val m = mask(dataWidth)
                for (k in mem.indices) mem[k] = mem[k] and m
            } catch (e: IOException) {
                throw RuntimeException("could not read memory initialization file $f", e)
            }
        }

        protected fun read(addr: Long) = if (addr in 0 until depth) mem[addr.toInt()] else 0L

        protected fun write(addr: Long, v: Long) {
            if (addr in 0 until depth) mem[addr.toInt()] = v and mask(dataWidth)
        }
    }

    private fun depth(p: Params, widthAd: String, numWords: String): Int =
        p.getLong(numWords, 1L shl p.getInt(widthAd, 8)).coerceIn(1, 1 shl 24).toInt()

    private fun createMemories(add: (QuartusPart) -> Unit) {
        add(QuartusPart("LPM_ROM", "Read only memory, initialized by LPM_FILE (mif or hex)", STORAGE)
            .input("address", 0, w("LPM_WIDTHAD", 8)).clock("inclock").clock("outclock").input("memenab", 1)
            .output("q", width)
            .defaults("LPM_WIDTH=8;LPM_WIDTHAD=8;LPM_FILE=;PORTS=address,inclock,q")
            .behavior { p ->
                object : MemoryBehavior(p, "LPM_FILE", width(p), depth(p, "LPM_WIDTHAD", "LPM_NUMWORDS")) {
                    private val regAddr = p.isPortUsed("inclock") && !p.get("LPM_ADDRESS_CONTROL", "REGISTERED").equals("UNREGISTERED", true)
                    private val regOut = p.isPortUsed("outclock") && !p.get("LPM_OUTDATA", "REGISTERED").equals("UNREGISTERED", true)
                    private val inEdge = Edge()
                    private val outEdge = Edge()
                    private var addr = 0L
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        if (!regAddr || inEdge.rising(i[1])) addr = i[0]
                        if (!regOut || outEdge.rising(i[2])) q = read(addr)
                        o[0] = q
                        highZ[0] = if (i[3].isSet()) 0 else mask(width(p))
                    }
                }
            })

        add(QuartusPart("LPM_RAM_DQ", "Random access memory with separate data input and output", STORAGE)
            .input("data", 0, width).input("address", 0, w("LPM_WIDTHAD", 8)).input("we")
            .clock("inclock").clock("outclock")
            .output("q", width)
            .defaults("LPM_WIDTH=8;LPM_WIDTHAD=8;LPM_FILE=;PORTS=data,address,we,inclock,q")
            .behavior { p ->
                object : MemoryBehavior(p, "LPM_FILE", width(p), depth(p, "LPM_WIDTHAD", "LPM_NUMWORDS")) {
                    private val sync = p.isPortUsed("inclock")
                    private val regOut = p.isPortUsed("outclock") && !p.get("LPM_OUTDATA", "REGISTERED").equals("UNREGISTERED", true)
                    private val inEdge = Edge()
                    private val outEdge = Edge()
                    private var addr = 0L
                    private var q = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        if (sync) {
                            if (inEdge.rising(i[3])) {
                                addr = i[1]
                                if (i[2].isSet()) write(addr, i[0])
                            }
                        } else {
                            addr = i[1]
                            if (i[2].isSet()) write(addr, i[0])
                        }
                        if (!regOut || outEdge.rising(i[4])) q = read(addr)
                        o[0] = q
                    }
                }
            })

        val wa = w("WIDTH_A", 8)
        val wb: (Params) -> Int = { it.getInt("WIDTH_B", it.getInt("WIDTH_A", 8)) }
        add(QuartusPart("ALTSYNCRAM", "Synchronous RAM/ROM (single or dual port), initialized by INIT_FILE", STORAGE)
            .input("address_a", 0, w("WIDTHAD_A", 8)).input("address_b", 0, { it.getInt("WIDTHAD_B", it.getInt("WIDTHAD_A", 8)) })
            .input("data_a", 0, wa).input("data_b", 0, wb).input("wren_a").input("wren_b")
            .input("rden_a", 1).input("rden_b", 1).clock("clock0").clock("clock1").input("clocken0", 1).input("aclr0")
            .output("q_a", wa).output("q_b", wb)
            .defaults("WIDTH_A=8;WIDTHAD_A=8;OPERATION_MODE=SINGLE_PORT;INIT_FILE=;PORTS=address_a,data_a,wren_a,clock0,q_a")
            .behavior { p ->
                object : MemoryBehavior(p, "INIT_FILE", wa(p), depth(p, "WIDTHAD_A", "NUMWORDS_A")) {
                    private val regA = !p.get("OUTDATA_REG_A", "UNREGISTERED").equals("UNREGISTERED", true)
                    private val regB = !p.get("OUTDATA_REG_B", "UNREGISTERED").equals("UNREGISTERED", true)
                    private val clockB = if (p.isPortUsed("clock1")) 9 else 8
                    private val e0 = Edge()
                    private val e1 = Edge()
                    private var ra = 0L
                    private var rb = 0L
                    private var qa = 0L
                    private var qb = 0L
                    override fun eval(i: LongArray, o: LongArray) {
                        val r0 = e0.rising(i[8])
                        val rB = if (clockB == 9) e1.rising(i[9]) else r0
                        if (i[11].isSet()) {
                            qa = 0
                            qb = 0
                        }
                        if (r0 && i[10].isSet()) {
                            if (regA) qa = ra
                            if (i[6].isSet()) ra = read(i[0])
                            if (i[4].isSet()) write(i[0], i[2])
                            if (!regA) qa = ra
                        }
                        if (rB) {
                            if (regB) qb = rb
                            if (i[7].isSet()) rb = read(i[1])
                            if (i[5].isSet()) write(i[1], i[3])
                            if (!regB) qb = rb
                        }
                        o[0] = qa
                        o[1] = qb
                    }
                }
            })

        val fifoWidth = w(LPM_WIDTH, 8)
        val usedw = w("LPM_WIDTHU", 4)
        for (name in listOf("LPM_FIFO", "SCFIFO")) {
            add(QuartusPart(name, "First in first out memory", STORAGE)
                .input("data", 0, fifoWidth).clock("clock").inputs("wrreq", "rdreq", "sclr", "aclr")
                .output("q", fifoWidth).outputs("empty", "full").output("usedw", usedw)
                .defaults("LPM_WIDTH=8;LPM_NUMWORDS=16;LPM_WIDTHU=4;LPM_SHOWAHEAD=OFF;PORTS=data,clock,wrreq,rdreq,q,empty,full")
                .behavior { p ->
                    object : Behavior() {
                        private val depth = p.getInt("LPM_NUMWORDS", 16).coerceIn(1, 1 shl 20)
                        private val showAhead = p.get("LPM_SHOWAHEAD", "OFF").equals("ON", true)
                        private val fifo = ArrayDeque<Long>()
                        private val edge = Edge()
                        private var q = 0L
                        override fun eval(i: LongArray, o: LongArray) {
                            val rising = edge.rising(i[1])
                            if (i[5].isSet()) {
                                fifo.clear()
                                q = 0
                            } else if (rising) {
                                if (i[4].isSet()) fifo.clear()
                                else {
                                    val read = i[3].isSet() && fifo.isNotEmpty()
                                    val write = i[2].isSet() && (fifo.size < depth || read)
                                    if (read) {
                                        val v = fifo.removeFirst()
                                        if (!showAhead) q = v
                                    }
                                    if (write) fifo.addLast(i[0])
                                }
                            }
                            o[0] = if (showAhead) fifo.firstOrNull() ?: 0 else q
                            o[1] = fifo.isEmpty().toBit()
                            o[2] = (fifo.size >= depth).toBit()
                            o[3] = fifo.size.toLong() and mask(usedw(p))
                        }
                    }
                })
        }
    }

    private fun createIO(add: (QuartusPart) -> Unit) {
        add(QuartusPart("ALTPLL", "Phase locked loop. Simulated as a clock buffer, the frequency is not changed.", IO)
            .clock("inclk0").input("areset").input("pllena", 1)
            .outputs("c0", "c1", "c2", "c3", "c4", "locked")
            .defaults("PORTS=inclk0,c0,locked")
            .comb { i, o ->
                val run = !i[1].isSet() && i[2].isSet()
                val c = if (run) i[0] and 1 else 0
                for (k in 0..4) o[k] = c
                o[5] = run.toBit()
            })
    }
}
