/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import java.io.File
import java.io.IOException

/**
 * A Quartus vector waveform file (vwf) as used by the Quartus simulator.
 * The input waveforms are converted to a Digital test case. The output values
 * stored in the file (the result of a previous Quartus simulation) are used as
 * the expected values, unknown values are not checked.
 */
class VwfFile private constructor() {
    /** a signal of the waveform file */
    class Signal(val name: String) {
        var isBus = false
        var width = 1
        var direction = "INPUT"
        var parent = ""

        /** the levels: value and duration */
        val levels = ArrayList<Pair<String, Double>>()

        /** returns the value at the given time */
        fun valueAt(t: Double): String {
            var time = 0.0
            for ((v, d) in levels) {
                if (t < time + d) return v
                time += d
            }
            return levels.lastOrNull()?.first ?: "X"
        }

        /** the times at which the value changes */
        fun changes(): List<Double> {
            val l = ArrayList<Double>()
            var time = 0.0
            var last: String? = null
            for ((v, d) in levels) {
                if (v != last) l.add(time)
                last = v
                time += d
            }
            return l
        }
    }

    /** all signals in the order of the file */
    val signals = LinkedHashMap<String, Signal>()

    /** the duration of the simulation */
    var duration = 0.0
        private set

    /** the time unit */
    var timeUnit = "ns"
        private set

    companion object {
        /** the maximum number of rows created */
        const val MAX_ROWS = 5000

        /** reads a vwf file */
        @JvmStatic
        @Throws(IOException::class)
        fun read(f: File): VwfFile = parse(String(f.readBytes(), Charsets.ISO_8859_1))

        /** parses a vwf file */
        @JvmStatic
        @Throws(IOException::class)
        fun parse(text: String): VwfFile {
            val vwf = VwfFile()
            val t = Tokenizer(text.replace(Regex("(?s)/\\*.*?\\*/"), " "))
            while (t.hasNext()) {
                val kw = t.next().uppercase()
                when (kw) {
                    "HEADER" -> t.block { k, v ->
                        when (k) {
                            "DATA_DURATION" -> vwf.duration = v.toDoubleOrNull() ?: 0.0
                            "TIME_UNIT" -> vwf.timeUnit = v
                        }
                    }
                    "SIGNAL" -> {
                        val name = t.paren()
                        val s = vwf.signals.getOrPut(name) { Signal(name) }
                        t.block { k, v ->
                            when (k) {
                                "SIGNAL_TYPE" -> s.isBus = v.equals("BUS", true)
                                "WIDTH" -> s.width = v.toIntOrNull() ?: 1
                                "DIRECTION" -> s.direction = v.uppercase()
                                "PARENT" -> s.parent = v
                            }
                        }
                    }
                    "TRANSITION_LIST" -> {
                        val name = t.paren()
                        val s = vwf.signals.getOrPut(name) { Signal(name) }
                        t.expect("{")
                        while (t.peek() != "}") readNode(t, s.levels)
                        t.expect("}")
                    }
                    else -> if (t.peek() == "(") {
                        t.paren()
                        t.skipBlock()
                    } else t.skipBlock()
                }
            }
            if (vwf.duration == 0.0)
                vwf.duration = vwf.signals.values.maxOfOrNull { s -> s.levels.sumOf { it.second } } ?: 0.0
            return vwf
        }

        /* reads a NODE { REPEAT = n; LEVEL x FOR t; NODE { ... } } */
        private fun readNode(t: Tokenizer, out: MutableList<Pair<String, Double>>) {
            val kw = t.next().uppercase()
            if (kw != "NODE") throw IOException("NODE expected but found $kw")
            t.expect("{")
            var repeat = 1
            val body = ArrayList<Pair<String, Double>>()
            while (t.peek() != "}") {
                when (t.peek().uppercase()) {
                    "REPEAT" -> {
                        t.next()
                        t.expect("=")
                        repeat = t.next().toIntOrNull() ?: 1
                        t.expect(";")
                    }
                    "LEVEL" -> {
                        t.next()
                        val v = t.next()
                        if (!t.next().equals("FOR", true)) throw IOException("FOR expected")
                        val d = t.next().toDoubleOrNull() ?: 0.0
                        t.expect(";")
                        body.add(v to d)
                    }
                    "NODE" -> readNode(t, body)
                    else -> t.next()
                }
            }
            t.expect("}")
            repeat(repeat) { out.addAll(body) }
        }

        /* the name of a signal without the hierarchy, e.g. "|top|clk" -> "clk" */
        internal fun simpleName(name: String) = name.substringAfterLast('|').trim()
    }

    /**
     * Creates the content of a Digital test case.
     *
     * @param inputs  maps the lower case Quartus name of an input pin to the pin in the circuit
     * @param outputs maps the lower case Quartus name of an output pin to the pin in the circuit
     * @param warn    used to report problems
     */
    fun createTest(inputs: Map<String, Pin>, outputs: Map<String, Pin>, warn: (String) -> Unit): String? {
        val ins = mapSignals(inputs, true)
        val outs = mapSignals(outputs, false)
        if (ins.isEmpty()) return null
        val times = ins.flatMap { m -> m.bits.flatMap { it.second.changes() } }.filter { it < duration || duration == 0.0 }
            .distinct().sorted().toMutableList()
        if (times.isEmpty()) times.add(0.0)
        if (times.size > MAX_ROWS) {
            warn("the waveform has ${times.size} changes, only the first $MAX_ROWS are used")
            while (times.size > MAX_ROWS) times.removeAt(times.size - 1)
        }
        val sb = StringBuilder()
        sb.append("# created from a Quartus waveform file (vwf)\n")
        sb.append((ins + outs).joinToString(" ") { it.pin.label }).append('\n')
        for (k in times.indices) {
            val t = times[k]
            val end = if (k + 1 < times.size) times[k + 1] else maxOf(duration, t + 1)
            val sample = t + (end - t) * 0.99
            val row = ins.map { it.value(t, true) } + outs.map { it.value(sample, false) }
            sb.append(row.joinToString(" ")).append('\n')
        }
        return sb.toString()
    }

    /** a pin of the circuit */
    class Pin(val label: String, val quartusName: String, val bits: Int)

    /* the bits of a pin: bit index (0 is the lsb) and the signal */
    private class Mapped(val pin: Pin, val bits: List<Pair<Int, Signal>>) {
        fun value(t: Double, input: Boolean): String {
            var v = 0L
            for ((bit, s) in bits) {
                when (s.valueAt(t).uppercase()) {
                    "1", "H" -> v = v or (1L shl bit)
                    "0", "L" -> {}
                    "Z" -> if (input && pin.bits == 1) return "Z" else if (!input) return "X"
                    else -> if (!input) return "X"
                }
            }
            return v.toString()
        }
    }

    private fun mapSignals(pins: Map<String, Pin>, input: Boolean): List<Mapped> {
        val result = ArrayList<Mapped>()
        val byName = signals.values.groupBy { simpleName(it.name).lowercase() }
        for ((qname, pin) in pins) {
            val bn = BusName.parse(pin.quartusName)
            val direct = byName[qname]?.firstOrNull { it.levels.isNotEmpty() && (!it.isBus || pin.bits == 1) }
            val bits = ArrayList<Pair<Int, Signal>>()
            if (direct != null && pin.bits == 1) bits.add(0 to direct)
            else if (pin.bits > 1) {
                bn.atoms.forEachIndexed { i, atom ->
                    val s = byName[atom]?.firstOrNull { it.levels.isNotEmpty() }
                    if (s != null) bits.add(bn.bits - 1 - i to s)
                }
            }
            if (bits.isNotEmpty() && (!input || bits.size == pin.bits)) result.add(Mapped(pin, bits))
        }
        return result
    }

    private class Tokenizer(private val text: String) {
        private var pos = 0
        private var peeked: String? = null

        fun hasNext(): Boolean {
            if (peeked != null) return true
            skip()
            return pos < text.length
        }

        private fun skip() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun peek(): String {
            if (peeked == null) peeked = read()
            return peeked!!
        }

        fun next(): String {
            val p = peeked
            if (p != null) {
                peeked = null
                return p
            }
            return read()
        }

        private fun read(): String {
            skip()
            if (pos >= text.length) throw IOException("unexpected end of the vwf file")
            val c = text[pos]
            if (c in "{}();=,") {
                pos++
                return c.toString()
            }
            if (c == '"') {
                val e = text.indexOf('"', pos + 1)
                if (e < 0) throw IOException("unterminated string in vwf file")
                val s = text.substring(pos + 1, e)
                pos = e + 1
                return s
            }
            val start = pos
            while (pos < text.length && !text[pos].isWhitespace() && text[pos] !in "{}();=,\"") pos++
            return text.substring(start, pos)
        }

        fun expect(s: String) {
            val n = next()
            if (n != s) throw IOException("'$s' expected but found '$n' in vwf file")
        }

        fun paren(): String {
            expect("(")
            val sb = StringBuilder()
            while (peek() != ")") sb.append(next())
            expect(")")
            return sb.toString()
        }

        /* reads a block of assignments key = value; */
        fun block(assign: (String, String) -> Unit) {
            expect("{")
            while (peek() != "}") {
                val k = next().uppercase()
                if (peek() == "=") {
                    next()
                    val v = StringBuilder()
                    while (peek() != ";" && peek() != "}") v.append(next())
                    assign(k, v.toString())
                }
                if (peek() == ";") next()
            }
            expect("}")
        }

        fun skipBlock() {
            if (peek() != "{") {
                while (hasNext() && next() != ";") {
                    // skip the statement
                }
                return
            }
            var depth = 0
            do {
                when (next()) {
                    "{" -> depth++
                    "}" -> depth--
                }
            } while (depth > 0)
        }
    }
}
