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
import de.neemann.digital.data.Value
import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.draw.elements.VisualElement
import de.neemann.digital.draw.graphics.Vector
import de.neemann.digital.draw.library.ElementLibrary
import de.neemann.digital.draw.model.ModelCreator
import de.neemann.digital.draw.shapes.ShapeFactory
import de.neemann.digital.gui.components.data.DummyElement
import de.neemann.digital.lang.Lang
import de.neemann.digital.testing.MatchedValue
import de.neemann.digital.testing.TestCaseDescription
import de.neemann.digital.testing.TestCaseElement
import de.neemann.digital.testing.TestExecutor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A lab which grades itself: the teacher makes a circuit with test cases and creates the lab from it.
 * The test cases are removed from the circuit and stored hidden in it, optionally the solution is removed
 * so only the inputs and outputs remain. The student draws the circuit and checks it with the hidden tests.
 * The tests are hidden from a casual look, they are not encrypted.
 *
 * @param title the title of the lab
 * @param text  the instructions shown to the student
 * @param hints true if the student is shown the first wrong row of a failed test
 * @param tests the tests: name and test data
 */
class Lab(val title: String, val text: String, val hints: Boolean, val tests: List<Pair<String, String>>) {

    /**
     * The result of a test.
     *
     * @param name   the name of the test
     * @param passed true if the test passed
     * @param rows   the number of rows tested
     * @param hint   the first wrong row, if hints are enabled
     * @param error  an error which stopped the test
     */
    class TestResult(val name: String, val passed: Boolean, val rows: Int, val hint: String?, val error: String?)

    /** encodes the lab to store it in the circuit */
    fun encode(): String {
        val p = Properties()
        p["title"] = title
        p["text"] = text
        p["hints"] = hints.toString()
        p["tests"] = tests.size.toString()
        tests.forEachIndexed { i, (n, d) ->
            p["test.$i.name"] = n
            p["test.$i.data"] = d
        }
        val w = StringWriter()
        p.store(w, null)
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(w.toString().toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(scramble(bytes.toByteArray()))
    }

    /**
     * Checks a circuit with the hidden tests.
     *
     * @param circuit the circuit of the student
     * @param library the library
     * @return the result of every test
     */
    fun check(circuit: Circuit, library: ElementLibrary): List<TestResult> = tests.map { (name, data) ->
        try {
            val model = ModelCreator(circuit, library).createModel(false)
            val r = TestExecutor(name, TestCaseDescription(data), model).execute()
            var hint: String? = null
            if (!r.allPassed() && hints) {
                val table = r.valueTable
                for (row in table) {
                    val values = row.values
                    if (values.any { it.state == Value.State.FAIL }) {
                        // the inputs, then the wrong outputs with the expected and the found value
                        val ins = (0 until table.columns).filter { values[it] !is MatchedValue }
                            .joinToString("  ") { c -> table.getColumnName(c) + "=" + values[c] }
                        val outs = (0 until table.columns).filter { values[it].state == Value.State.FAIL }
                            .joinToString(", ") { c ->
                                val m = values[c] as MatchedValue
                                Lang.get("msg_labExpectedFound_NNN", table.getColumnName(c), m.expected, Value(m).toString())
                            }
                        hint = "$ins → $outs"
                        break
                    }
                }
            }
            TestResult(name, r.allPassed(), r.rowsTested, hint, null)
        } catch (e: Exception) {
            TestResult(name, false, 0, null, e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private val KEY = "QuartusMACOpenSource-lab".toByteArray(Charsets.UTF_8)

        private fun scramble(b: ByteArray): ByteArray = ByteArray(b.size) { i -> (b[i].toInt() xor KEY[i % KEY.size].toInt()).toByte() }

        /**
         * Reads the lab stored in a circuit.
         *
         * @param circuit the circuit
         * @return the lab or null if the circuit is not a lab
         */
        @JvmStatic
        fun of(circuit: Circuit): Lab? {
            val data = circuit.attributes.get(Keys.LAB_DATA)
            if (data.isEmpty()) return null
            return try {
                decode(data)
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /** decodes a lab */
        @JvmStatic
        @Throws(IOException::class)
        fun decode(data: String): Lab {
            val gz = scramble(Base64.getDecoder().decode(data))
            val text = GZIPInputStream(ByteArrayInputStream(gz)).use { String(it.readBytes(), Charsets.UTF_8) }
            val p = Properties()
            p.load(StringReader(text))
            val n = p.getProperty("tests", "0").toInt()
            val tests = (0 until n).map { p.getProperty("test.$it.name", "") to p.getProperty("test.$it.data", "") }
            return Lab(p.getProperty("title", ""), p.getProperty("text", ""), p.getProperty("hints") == "true", tests)
        }

        /**
         * The test cases of a circuit: name and test data.
         */
        @JvmStatic
        fun testsOf(circuit: Circuit): List<Pair<String, String>> = circuit.testCases.mapIndexed { i, t ->
            (t.label.ifBlank { "Test ${i + 1}" }) to t.testCaseDescription.dataString
        }

        /**
         * Creates the circuit given to the students.
         *
         * @param circuit        the circuit of the teacher with test cases
         * @param lab            the lab
         * @param removeSolution if true only the inputs, outputs and texts are kept
         * @param shapeFactory   the shape factory used by the added text
         * @return the new circuit
         */
        @JvmStatic
        fun createLabCircuit(circuit: Circuit, lab: Lab, removeSolution: Boolean, shapeFactory: ShapeFactory): Circuit {
            val c = circuit.createDeepCopy()
            val keep = setOf(In.DESCRIPTION.name, Out.DESCRIPTION.name, Clock.DESCRIPTION.name, DummyElement.TEXTDESCRIPTION.name)
            for (ve in ArrayList(c.elements)) {
                if (ve.elementName == TestCaseElement.DESCRIPTION.name || (removeSolution && ve.elementName !in keep))
                    c.delete(ve)
            }
            // deleting a wire merges the remaining ones, so the list is read again after every deletion
            if (removeSolution) {
                var n = 0
                while (c.wires.isNotEmpty() && n++ < 100000) c.delete(c.wires[0])
            }
            val minY = c.elements.minOfOrNull { it.pos.y } ?: 0
            val minX = c.elements.minOfOrNull { it.pos.x } ?: 0
            val lines = (lab.title + "\n" + lab.text).trim().lines().size
            val t = VisualElement(DummyElement.TEXTDESCRIPTION.name)
            t.elementAttributes.set(Keys.DESCRIPTION, (lab.title + "\n\n" + lab.text).trim())
            t.setPos(Vector(minX - 60, minY - 80 - lines * 40)).setShapeFactory(shapeFactory)
            c.add(t)
            c.attributes.set(Keys.LAB_DATA, lab.encode())
            return c
        }
    }
}
