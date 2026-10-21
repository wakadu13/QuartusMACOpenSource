/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.draw.library.ElementLibrary
import de.neemann.digital.draw.model.ModelCreator
import de.neemann.digital.draw.shapes.ShapeFactory
import de.neemann.digital.lang.Lang
import de.neemann.digital.testing.TestExecutor
import java.io.File
import java.nio.file.Files

/**
 * Test bench for real projects: imports every Quartus project (.qpf) and block diagram (.bdf) found in a folder,
 * builds the simulation model of the result and runs the tests created from the waveform files (.vwf).
 * The circuits are written to a temporary folder, the checked folder is not changed.
 * Used to find the blocks of a library or the student projects which are not converted correctly.
 */
class ProjectChecker {

    /** the result of a file */
    enum class Status { OK, WARNING, ERROR }

    /**
     * The result of the check of one file.
     *
     * @param file        the checked file
     * @param status      the overall status
     * @param warnings    the warnings of the import
     * @param error       the error which stopped the import or the simulation, null if none
     * @param testsPassed the number of passed tests
     * @param tests       the number of tests
     */
    class Result(val file: File, val status: Status, val warnings: List<String>, val error: String?,
                 val testsPassed: Int, val tests: Int)

    /** the results in the order the files were checked */
    val results = ArrayList<Result>()

    /**
     * Checks all Quartus projects and block diagrams below the given folder.
     *
     * @param dir the folder, or a single file
     * @return this for chained calls
     */
    fun check(dir: File): ProjectChecker {
        if (dir.isFile) results.add(checkFile(dir))
        else findFiles(dir).forEach { results.add(checkFile(it)) }
        return this
    }

    /** the number of files with the given status */
    fun count(status: Status): Int = results.count { it.status == status }

    private fun findFiles(dir: File): List<File> {
        val found = ArrayList<File>()
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            if (f.isDirectory) {
                if (!SKIPPED_DIRS.contains(f.name) && !f.name.startsWith(".")) found.addAll(findFiles(f))
            } else if (f.extension.equals("qpf", true) || f.extension.equals("bdf", true)) found.add(f)
        }
        return found
    }

    private fun checkFile(file: File): Result {
        val out = Files.createTempDirectory("check").toFile()
        try {
            val imp = try {
                QuartusImporter.importFile(file, out)
            } catch (e: Exception) {
                return Result(file, Status.ERROR, emptyList(), "import: ${message(e)}", 0, 0)
            }
            var passed = 0
            var tests = 0
            for (dig in listOfNotNull(imp.topFile, imp.boardFile)) {
                try {
                    val library = ElementLibrary()
                    library.setRootFilePath(out)
                    val circuit = Circuit.loadCircuit(dig, ShapeFactory(library))
                    ModelCreator(circuit, library).createModel(false).close()
                    for (t in circuit.testCases) {
                        tests++
                        if (TestExecutor(t, circuit, library).execute().allPassed()) passed++
                    }
                } catch (e: Exception) {
                    return Result(file, Status.ERROR, imp.warnings, "${dig.name}: ${message(e)}", passed, tests)
                }
            }
            val status = when {
                passed < tests -> Status.ERROR
                imp.warnings.isNotEmpty() -> Status.WARNING
                else -> Status.OK
            }
            return Result(file, status, imp.warnings, if (passed < tests) "${tests - passed} test(s) failed" else null,
                passed, tests)
        } finally {
            out.deleteRecursively()
        }
    }

    /**
     * Creates a report in Markdown.
     *
     * @param base the folder the file names are given relative to
     * @return the report
     */
    fun createReport(base: File): String {
        val sb = StringBuilder()
        sb.append("| Fichier | Résultat | Tests | Remarques |\n|---|---|---|---|\n")
        for (r in results) {
            val name = r.file.relativeToOrSelf(base.absoluteFile).path
            val state = when (r.status) {
                Status.OK -> "✅ OK"
                Status.WARNING -> "⚠️ avertissements"
                Status.ERROR -> "❌ erreur"
            }
            val tests = if (r.tests == 0) "" else "${r.testsPassed}/${r.tests}"
            val notes = (listOfNotNull(r.error) + r.warnings).joinToString("<br>") { it.replace("|", "\\|") }
            sb.append("| $name | $state | $tests | $notes |\n")
        }
        sb.append("\n**${results.size}** fichiers : ${count(Status.OK)} OK, ${count(Status.WARNING)} avec avertissements, ")
            .append("${count(Status.ERROR)} en erreur.\n")
        return sb.toString()
    }

    /**
     * Creates a report in HTML, shown in the application.
     *
     * @param base the folder the file names are given relative to
     * @return the report
     */
    fun createHtmlReport(base: File): String {
        val sb = StringBuilder("<html><body><table border='1' cellspacing='0' cellpadding='3'>")
        sb.append("<tr><th>").append(Lang.get("msg_checkFile")).append("</th><th>").append(Lang.get("msg_checkResult"))
            .append("</th><th>").append(Lang.get("msg_checkTests")).append("</th><th>").append(Lang.get("msg_checkNotes"))
            .append("</th></tr>")
        for (r in results) {
            val color = when (r.status) {
                Status.OK -> "#2e7d32"
                Status.WARNING -> "#b26a00"
                Status.ERROR -> "#c62828"
            }
            val state = when (r.status) {
                Status.OK -> Lang.get("msg_checkOk")
                Status.WARNING -> Lang.get("msg_checkWarning")
                Status.ERROR -> Lang.get("msg_checkError")
            }
            sb.append("<tr><td>").append(esc(r.file.relativeToOrSelf(base.absoluteFile).path))
                .append("</td><td><font color='").append(color).append("'><b>").append(state).append("</b></font></td><td>")
                .append(if (r.tests == 0) "" else "${r.testsPassed}/${r.tests}").append("</td><td>")
                .append((listOfNotNull(r.error) + r.warnings).joinToString("<br>") { esc(it) }).append("</td></tr>")
        }
        sb.append("</table><p>").append(Lang.get("msg_checkSummary_NNNN", results.size, count(Status.OK),
            count(Status.WARNING), count(Status.ERROR))).append("</p></body></html>")
        return sb.toString()
    }

    private companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val SKIPPED_DIRS = setOf("digital", "db", "incremental_db", "output_files", "simulation", "target")

        fun message(e: Exception): String {
            var t: Throwable = e
            val sb = StringBuilder(t.message ?: t.javaClass.simpleName)
            while (t.cause != null && t.cause !== t) {
                t = t.cause!!
                if (t.message != null) sb.append(": ").append(t.message)
            }
            return sb.toString().replace('\n', ' ')
        }
    }
}
