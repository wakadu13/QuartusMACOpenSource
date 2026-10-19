/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import java.io.File
import java.io.IOException

/**
 * A Quartus project: the project file (qpf), the settings file (qsf) and the
 * IP files (qip). Provides the top level entity, the design files, the waveform
 * files and the pin assignments.
 *
 * @param projectDir the folder of the project
 */
class QuartusProject(val projectDir: File) {
    /** the name of the project */
    var name: String = projectDir.name
        private set

    /** the top level entity */
    var topLevelEntity: String = ""

    /** the device family */
    var family: String = ""
        private set

    /** the device */
    var device: String = ""
        private set

    /** the design files listed in the project */
    val designFiles = ArrayList<File>()

    /** the waveform files (vwf) listed in the project */
    val waveformFiles = ArrayList<File>()

    /** the search paths */
    val searchPaths = ArrayList<File>()

    private val pinLocations = LinkedHashMap<String, String>()
    private val readFiles = HashSet<File>()

    /** adds a search path */
    fun addSearchPath(dir: File) {
        if (dir !in searchPaths) searchPaths.add(dir)
    }

    /** returns the pin location (like "G21") of the given node or null */
    fun getPinLocation(node: String): String? = pinLocations[node.lowercase()]

    /** all assigned pin locations */
    val allPinLocations: Collection<String> get() = pinLocations.values

    /** reads a settings file (qsf) or an ip file (qip) */
    @Throws(IOException::class)
    fun readSettings(file: File) {
        val f = file.absoluteFile
        if (!readFiles.add(f)) return
        val base = f.parentFile
        for (l in String(f.readBytes(), Charsets.ISO_8859_1).lines()) {
            var line = l.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            QIP_PATH.find(line)?.let { line = line.substring(0, it.range.first) + it.groupValues[1].trim() + line.substring(it.range.last + 1) }
            val tok = tokenize(line)
            if (tok.isEmpty()) continue
            val n = option(tok, "-name")
            when {
                tok[0] == "set_global_assignment" && n != null -> lastValue(tok)?.let { handleGlobal(n.uppercase(), it, base) }
                tok[0] == "set_location_assignment" -> {
                    val to = option(tok, "-to")
                    if (to != null && tok.size > 1)
                        pinLocations[to.lowercase()] = tok[1].removePrefix("PIN_").removePrefix("pin_")
                }
            }
        }
    }

    private fun handleGlobal(n: String, value: String, base: File) {
        when (n) {
            "TOP_LEVEL_ENTITY" -> topLevelEntity = value
            "FAMILY" -> family = value
            "DEVICE" -> device = value
            "BDF_FILE", "VHDL_FILE", "VERILOG_FILE", "SYSTEMVERILOG_FILE", "AHDL_FILE", "GDF_FILE",
            "SOURCE_FILE", "MISC_FILE", "EDIF_FILE", "BSF_FILE", "MIF_FILE", "HEX_FILE" -> {
                val df = resolve(base, value)
                if (df !in designFiles) designFiles.add(df)
            }
            "QIP_FILE", "SIP_FILE" -> {
                val qip = resolve(base, value)
                if (qip.exists()) readSettings(qip)
                else {
                    // the qip is missing, but the HDL file may be present
                    for (ext in listOf(".vhd", ".v", ".bdf")) {
                        val h = File(qip.parentFile, stem(qip.name) + ext)
                        if (h.exists() && h !in designFiles) designFiles.add(h)
                    }
                }
            }
            "SEARCH_PATH", "USER_LIBRARIES" ->
                value.split(";").filter { it.isNotBlank() }.forEach { addSearchPath(resolve(base, it.trim())) }
        }
        if (value.lowercase().endsWith(".vwf")) {
            val w = resolve(base, value)
            if (w !in waveformFiles) waveformFiles.add(w)
        }
    }

    companion object {
        private val QIP_PATH = Regex("""\[\s*file\s+join\s+\$::quartus\(qip_path\)\s+(.*?)]""")

        /**
         * Opens a Quartus project.
         *
         * @param file a qpf or a qsf file
         */
        @JvmStatic
        @Throws(IOException::class)
        fun open(file: File): QuartusProject {
            val f = file.absoluteFile
            val dir = f.parentFile
            val p = QuartusProject(dir)
            val stem = stem(f.name)
            p.name = stem
            if (f.name.lowercase().endsWith(".qpf")) {
                val qsf = readRevisions(f).map { File(dir, "$it.qsf") }.firstOrNull { it.exists() }
                    ?: File(dir, "$stem.qsf").takeIf { it.exists() }
                    ?: dir.listFiles { _, n -> n.lowercase().endsWith(".qsf") }?.firstOrNull()
                qsf?.let { p.readSettings(it) }
            } else
                p.readSettings(f)
            if (p.topLevelEntity.isEmpty()) p.topLevelEntity = stem
            return p
        }

        private fun readRevisions(qpf: File): List<String> =
            qpf.readLines(Charsets.ISO_8859_1).map { it.trim() }.filter { it.startsWith("PROJECT_REVISION") && it.contains('=') }
                .map { unquote(it.substringAfter('=').trim()) }

        private fun resolve(base: File, value: String): File {
            val v = value.replace('\\', '/')
            val f = File(v).let { if (it.isAbsolute) it else File(base, v) }
            return try {
                f.canonicalFile
            } catch (e: IOException) {
                f.absoluteFile
            }
        }

        private fun option(tok: List<String>, opt: String): String? {
            for (i in 0 until tok.size - 1) if (tok[i] == opt) return tok[i + 1]
            return null
        }

        /* the value of an assignment is the first token which is not an option or an option value */
        private fun lastValue(tok: List<String>): String? {
            var i = 1
            while (i < tok.size) {
                val t = tok[i]
                if (t.length > 1 && t[0] == '-' && t[1].isLetter()) i += 2 else return t
            }
            return null
        }

        /** splits a tcl like line into tokens */
        @JvmStatic
        fun tokenize(line: String): List<String> {
            val tok = ArrayList<String>()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c.isWhitespace() -> i++
                    c == '"' -> {
                        val sb = StringBuilder()
                        var e = i + 1
                        while (e < line.length && line[e] != '"') {
                            if (line[e] == '\\' && e + 1 < line.length) e++
                            sb.append(line[e++])
                        }
                        tok.add(sb.toString())
                        i = e + 1
                    }
                    c == '{' -> {
                        val e = line.indexOf('}', i).let { if (it < 0) line.length else it }
                        tok.add(line.substring(i + 1, e))
                        i = e + 1
                    }
                    else -> {
                        var e = i
                        while (e < line.length && !line[e].isWhitespace()) e++
                        tok.add(line.substring(i, e))
                        i = e
                    }
                }
            }
            return tok
        }

        private fun unquote(s: String) = if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

        /** returns the name of a file without its extension */
        @JvmStatic
        fun stem(name: String): String {
            val p = name.lastIndexOf('.')
            return if (p > 0) name.substring(0, p) else name
        }
    }
}
