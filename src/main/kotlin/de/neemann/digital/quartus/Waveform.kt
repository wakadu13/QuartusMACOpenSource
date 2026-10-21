/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.Model
import de.neemann.digital.core.ObservableValue
import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.draw.library.ElementLibrary
import de.neemann.digital.draw.model.ModelCreator
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The waveforms of the waveform editor, the equivalent of a Quartus vector waveform file (vwf).
 * The inputs are given by a value for every grid step, the outputs are calculated by a simulation.
 * Like in Quartus the simulation is either functional (no delays) or a timing simulation in which every
 * gate, flip-flop or block of the circuit needs the given propagation delay.
 * All times are given in ns.
 *
 * @param endTime  the end of the simulation ("End Time" in Quartus)
 * @param gridSize the duration of a grid step ("Grid Size" in Quartus)
 */
class Waveform(endTime: Double = 1000.0, gridSize: Double = 10.0) {

    /**
     * A signal of the waveform.
     *
     * @param name  the name of the input or output
     * @param bits  the number of bits
     * @param input true if it is an input, which is given by the user
     * @param clock true if it is a clock input
     */
    class Trace(val name: String, val bits: Int, val input: Boolean, val clock: Boolean = false) {
        /** the values of an input, one for every grid step */
        var steps = LongArray(0)

        /** the result of the simulation: the times at which the value changes and the new value, -1 means undefined */
        val result = ArrayList<Pair<Double, Long>>()

        /** the mask of the valid bits */
        val mask: Long = if (bits >= 64) -1L else (1L shl bits) - 1

        /** the value of an input at the given step */
        fun step(i: Int): Long = if (i < steps.size) steps[i] else steps.lastOrNull() ?: 0

        /** the simulated value at the given time, -1 if undefined */
        fun resultAt(t: Double): Long {
            var v = -1L
            for ((time, value) in result) {
                if (time > t) break
                v = value
            }
            return v
        }

        /** sets the value of the steps from..to (exclusive) */
        fun set(from: Int, to: Int, value: Long) {
            for (i in maxOf(0, from) until minOf(to, steps.size)) steps[i] = value and mask
        }

        /** makes a clock: the value changes every halfPeriod steps, starting with startLevel */
        fun clock(from: Int, to: Int, halfPeriod: Int, startLevel: Long) {
            val h = maxOf(1, halfPeriod)
            for (i in maxOf(0, from) until minOf(to, steps.size))
                steps[i] = if (((i - from) / h) % 2 == 0) startLevel and 1 else (startLevel xor 1) and 1
        }

        /** counts: the value is incremented every stepsPerCount steps */
        fun count(from: Int, to: Int, stepsPerCount: Int, start: Long) {
            val n = maxOf(1, stepsPerCount)
            for (i in maxOf(0, from) until minOf(to, steps.size))
                steps[i] = (start + (i - from) / n) and mask
        }
    }

    /** the end of the simulation */
    var endTime = endTime
        set(value) {
            field = value
            resize()
        }

    /** the duration of a grid step */
    var gridSize = gridSize
        set(value) {
            field = value
            resize()
        }

    /** the signals: inputs first, then outputs */
    val traces = ArrayList<Trace>()

    /** messages of the last simulation, e.g. signals not found in the circuit */
    val messages = ArrayList<String>()

    /** the number of grid steps */
    val stepCount: Int get() = maxOf(1, ceil(endTime / gridSize - 1e-9).toInt())

    /** adds a signal */
    fun add(t: Trace): Trace {
        t.steps = LongArray(stepCount)
        traces.add(t)
        return t
    }

    /** returns the signal with the given name */
    fun get(name: String): Trace? = traces.firstOrNull { it.name == name }

    private fun resize() {
        for (t in traces) if (t.steps.size != stepCount) t.steps = t.steps.copyOf(stepCount)
        clearResults()
    }

    /** removes the results of the last simulation */
    fun clearResults() {
        for (t in traces) t.result.clear()
    }

    /**
     * Simulates the circuit.
     *
     * @param circuit   the circuit
     * @param library   the library used to create the model
     * @param gateDelay the propagation delay of every gate in a timing simulation, 0 for a functional simulation
     */
    fun simulate(circuit: Circuit, library: ElementLibrary, gateDelay: Double) {
        val model = ModelCreator(circuit, library).createModel(false)
        try {
            simulate(model, gateDelay)
        } finally {
            model.close()
        }
    }

    /**
     * Simulates the given model.
     *
     * @param model     the model, not initialized yet
     * @param gateDelay the propagation delay of every gate, 0 for a functional simulation
     */
    fun simulate(model: Model, gateDelay: Double) {
        clearResults()
        messages.clear()
        val signals = HashMap<String, ObservableValue>()
        for (s in model.inputs) signals[s.name] = s.value
        for (c in model.clocks) signals[c.label] = c.clockOutput
        val outs = HashMap<String, ObservableValue>()
        for (s in model.outputs) outs[s.name] = s.value

        val ins = ArrayList<Pair<Trace, ObservableValue>>()
        val res = ArrayList<Pair<Trace, ObservableValue>>()
        for (t in traces) {
            val v = if (t.input) signals[t.name] else outs[t.name]
            if (v == null) messages.add(t.name)
            else if (t.input) ins.add(t to v) else res.add(t to v)
        }
        for ((t, v) in ins) v.value = t.step(0)
        model.init(false)

        var longDelay = false
        for (i in 0 until stepCount) {
            val t0 = i * gridSize
            for ((t, v) in ins) v.value = t.step(i)
            if (gateDelay <= 0) {
                model.doStep()
                record(res, t0)
            } else {
                var k = 0
                record(res, t0)
                while (model.needsUpdate()) {
                    if (++k > MAX_MICRO_STEPS) throw IOException("the circuit oscillates at t = ${format(t0)}")
                    model.doMicroStep(false)
                    record(res, t0 + k * gateDelay)
                }
                if (k * gateDelay > gridSize) longDelay = true
            }
        }
        if (longDelay) messages.add(LONG_DELAY)
    }

    private fun record(res: List<Pair<Trace, ObservableValue>>, time: Double) {
        if (time > endTime) return
        for ((t, v) in res) {
            val value = if (v.isHighZ) -1L else v.value and t.mask
            if (t.result.isEmpty() || t.result.last().second != value) {
                if (t.result.isNotEmpty() && t.result.last().first == time) t.result[t.result.size - 1] = time to value
                else t.result.add(time to value)
            }
        }
    }

    /**
     * Creates the content of a Digital test case: a row for every grid step with the inputs and the values of the
     * outputs at the end of the step.
     */
    fun createTestData(): String {
        val sb = StringBuilder()
        sb.append(traces.joinToString(" ") { it.name }).append('\n')
        for (i in 0 until stepCount) {
            val end = (i + 1) * gridSize - gridSize * 0.01
            sb.append(traces.joinToString(" ") {
                if (it.input) it.step(i).toString()
                else it.resultAt(end).let { v -> if (v < 0) "X" else v.toString() }
            }).append('\n')
        }
        return sb.toString()
    }

    /** writes the waveforms as a Quartus vector waveform file */
    fun writeVwf(f: File) = f.writeText(toVwf(), Charsets.ISO_8859_1)

    /** creates the content of a Quartus vector waveform file */
    fun toVwf(): String {
        val sb = StringBuilder()
        sb.append("/*\nWaveform file created by QuartusMACOpenSource.\n*/\n\n")
        sb.append("HEADER\n{\n\tVERSION = 1;\n\tTIME_UNIT = ns;\n\tDATA_OFFSET = 0.0;\n")
        sb.append("\tDATA_DURATION = ").append(format(endTime)).append(";\n")
        sb.append("\tSIMULATION_TIME = 0.0;\n\tGRID_PHASE = 0.0;\n")
        sb.append("\tGRID_PERIOD = ").append(format(gridSize)).append(";\n\tGRID_DUTY_CYCLE = 50;\n}\n\n")
        for (t in traces) {
            if (t.bits > 1) {
                signal(sb, t.name, t, true, "")
                for (b in t.bits - 1 downTo 0) signal(sb, "${t.name}[$b]", t, false, t.name)
            } else signal(sb, t.name, t, false, "")
        }
        for (t in traces) {
            if (t.bits > 1) for (b in t.bits - 1 downTo 0) transitions(sb, "${t.name}[$b]", levels(t, b))
            else transitions(sb, t.name, levels(t, 0))
        }
        var index = 0
        for (t in traces) {
            val parent = index
            displayLine(sb, t.name, if (t.bits > 1) "Hexadecimal" else "Binary", index++, 0, null)
            if (t.bits > 1) for (b in t.bits - 1 downTo 0) displayLine(sb, "${t.name}[$b]", "Binary", index++, 1, parent)
        }
        sb.append("TIME_BAR\n{\n\tTIME = 0;\n\tMASTER = TRUE;\n}\n;\n")
        return sb.toString()
    }

    private fun signal(sb: StringBuilder, name: String, t: Trace, bus: Boolean, parent: String) {
        sb.append("SIGNAL(\"").append(name).append("\")\n{\n\tVALUE_TYPE = NINE_LEVEL_BIT;\n")
        sb.append("\tSIGNAL_TYPE = ").append(if (bus) "BUS" else "SINGLE_BIT").append(";\n")
        sb.append("\tWIDTH = ").append(if (bus) t.bits else 1).append(";\n")
        sb.append("\tLSB_INDEX = ").append(if (bus) 0 else -1).append(";\n")
        sb.append("\tDIRECTION = ").append(if (t.input) "INPUT" else "OUTPUT").append(";\n")
        sb.append("\tPARENT = \"").append(parent).append("\";\n}\n\n")
    }

    /* the levels of a bit: value and duration */
    private fun levels(t: Trace, bit: Int): List<Pair<String, Double>> {
        val changes = ArrayList<Pair<Double, String>>()
        if (t.input) {
            for (i in 0 until stepCount) changes.add(i * gridSize to ((t.step(i) shr bit) and 1).toString())
        } else {
            if (t.result.isEmpty() || t.result[0].first > 0) changes.add(0.0 to "X")
            for ((time, v) in t.result) changes.add(time to if (v < 0) "X" else ((v shr bit) and 1).toString())
        }
        val levels = ArrayList<Pair<String, Double>>()
        for (k in changes.indices) {
            val (time, v) = changes[k]
            if (time >= endTime) break
            val end = if (k + 1 < changes.size) minOf(changes[k + 1].first, endTime) else endTime
            if (levels.isNotEmpty() && levels.last().first == v) levels[levels.size - 1] = v to levels.last().second + end - time
            else if (end > time) levels.add(v to end - time)
        }
        return levels
    }

    private fun transitions(sb: StringBuilder, name: String, levels: List<Pair<String, Double>>) {
        sb.append("TRANSITION_LIST(\"").append(name).append("\")\n{\n\tNODE\n\t{\n\t\tREPEAT = 1;\n")
        for ((v, d) in levels) sb.append("\t\tLEVEL ").append(v).append(" FOR ").append(format(d)).append(";\n")
        sb.append("\t}\n}\n\n")
    }

    private fun displayLine(sb: StringBuilder, name: String, radix: String, index: Int, level: Int, parent: Int?) {
        sb.append("DISPLAY_LINE\n{\n\tCHANNEL = \"").append(name).append("\";\n\tEXPAND_STATUS = COLLAPSED;\n")
        sb.append("\tRADIX = ").append(radix).append(";\n\tTREE_INDEX = ").append(index).append(";\n")
        sb.append("\tTREE_LEVEL = ").append(level).append(";\n")
        if (parent != null) sb.append("\tPARENT = ").append(parent).append(";\n")
        sb.append("}\n\n")
    }

    /**
     * Takes the input waveforms of a Quartus vector waveform file. The inputs are matched by name,
     * the end time and the grid size are taken from the file.
     *
     * @param vwf the waveform file
     * @return the names of the inputs which were not found in the file
     */
    fun readInputs(vwf: VwfFile): List<String> {
        if (vwf.duration > 0) endTime = vwf.duration
        vwf.gridPeriod?.let { if (it > 0) gridSize = it }
        val byName = vwf.signals.values.filter { it.levels.isNotEmpty() }
            .associateBy { VwfFile.simpleName(it.name).lowercase() }
        val missing = ArrayList<String>()
        for (t in traces.filter { it.input }) {
            val bitSignals = if (t.bits == 1) listOf(byName[t.name.lowercase()])
            else (0 until t.bits).map { b -> byName["${t.name.lowercase()}[$b]"] }
            if (bitSignals.all { it == null }) {
                missing.add(t.name)
                continue
            }
            for (i in 0 until stepCount) {
                val time = i * gridSize + gridSize / 2
                var v = 0L
                bitSignals.forEachIndexed { b, s ->
                    if (s != null && s.valueAt(time).uppercase().let { it == "1" || it == "H" }) v = v or (1L shl b)
                }
                t.steps[i] = v
            }
        }
        clearResults()
        return missing
    }

    companion object {
        /** the message added if a propagation takes longer than a grid step */
        const val LONG_DELAY = "#longDelay"

        private const val MAX_MICRO_STEPS = 100000

        /** the maximum number of grid steps */
        const val MAX_STEPS = 100000

        private val TIME = Regex("""^\s*([0-9]+(?:[.,][0-9]*)?)\s*(ps|ns|us|µs|ms|s)?\s*$""", RegexOption.IGNORE_CASE)

        /**
         * Parses a time like "10", "2.5 us" or "1 ms".
         *
         * @param s the text
         * @return the time in ns or null if the text is not a time
         */
        @JvmStatic
        fun parseTime(s: String): Double? {
            val m = TIME.find(s) ?: return null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return v * when (m.groupValues[2].lowercase()) {
                "ps" -> 0.001
                "us", "µs" -> 1e3
                "ms" -> 1e6
                "s" -> 1e9
                else -> 1.0
            }
        }

        /** formats a time given in ns with a suitable unit, e.g. "2.5 µs" */
        @JvmStatic
        fun formatTime(ns: Double): String {
            val (v, unit) = when {
                ns >= 1e9 -> ns / 1e9 to "s"
                ns >= 1e6 -> ns / 1e6 to "ms"
                ns >= 1e3 -> ns / 1e3 to "µs"
                else -> ns to "ns"
            }
            val r = BigDecimal((v * 1000).roundToLong()).movePointLeft(3).stripTrailingZeros()
            return (if (r.scale() < 0) r.setScale(0) else r).toPlainString() + " " + unit
        }

        /** formats a time in ns as used in a vwf file */
        @JvmStatic
        fun format(t: Double): String {
            val r = BigDecimal((t * 1000).roundToLong()).movePointLeft(3).stripTrailingZeros()
            return if (r.scale() <= 0) r.setScale(1).toPlainString() else r.toPlainString()
        }

        /**
         * Creates the waveforms for the inputs and outputs of a circuit.
         * Clock inputs get a clock with a period of two grid steps.
         *
         * @param circuit  the circuit
         * @param library  the library
         * @param endTime  the end time
         * @param gridSize the grid size
         */
        @JvmStatic
        fun create(circuit: Circuit, library: ElementLibrary, endTime: Double, gridSize: Double): Waveform {
            val w = Waveform(endTime, gridSize)
            val model = ModelCreator(circuit, library).createModel(false)
            try {
                for (s in model.inputs) if (s.name.isNotEmpty()) w.add(Trace(s.name, s.value.bits, true))
                for (c in model.clocks)
                    if (c.label.isNotEmpty()) w.add(Trace(c.label, 1, true, true)).also { it.clock(0, w.stepCount, 1, 0) }
                for (s in model.outputs) if (s.name.isNotEmpty()) w.add(Trace(s.name, s.value.bits, false))
            } finally {
                model.close()
            }
            return w
        }
    }

    /**
     * Takes the inputs and outputs of the given waveform which are also present in this one,
     * used if the circuit was modified.
     */
    fun takeValuesFrom(old: Waveform) {
        for (t in traces) {
            val o = old.get(t.name) ?: continue
            if (o.input && t.input && o.bits == t.bits) t.steps = o.steps.copyOf(stepCount)
        }
    }

    /** used to convert a value to a string */
    enum class Radix { BINARY, HEX, UNSIGNED, SIGNED }

    /** a value as text, -1 is shown as X */
    fun text(t: Trace, v: Long, radix: Radix): String {
        if (v < 0) return "X"
        return when (radix) {
            Radix.BINARY -> java.lang.Long.toBinaryString(v).padStart(t.bits, '0')
            Radix.HEX -> java.lang.Long.toHexString(v).uppercase().padStart((t.bits + 3) / 4, '0')
            Radix.UNSIGNED -> v.toString()
            Radix.SIGNED -> (if (t.bits < 64 && (v shr (t.bits - 1)) and 1L == 1L) v - (1L shl t.bits) else v).toString()
        }
    }
}
