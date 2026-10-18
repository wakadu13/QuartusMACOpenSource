/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import java.io.File
import java.io.IOException

/**
 * A VHDL entity or a Verilog module found in a HDL file.
 * Only the interface is extracted. If the design unit is a wrapper of a
 * Quartus megafunction (as created by the MegaWizard plug-in manager),
 * the megafunction, its parameters and its port map are also extracted.
 */
class HdlEntity private constructor(
    /** the name of the entity or module */
    val name: String,
    val language: Language,
    /** the file containing the entity */
    val file: File?,
    val ports: List<Port>,
    /** the generics or parameters with their default values */
    val generics: Map<String, String>
) {
    /** the name of the wrapped megafunction in lower case or null */
    var megafunction: String? = null
        private set

    /** the parameters of the megafunction, keys in upper case */
    val megafunctionParams = LinkedHashMap<String, String>()

    /** the port map of the megafunction: megafunction port (lower case) to entity signal */
    val megafunctionPortMap = LinkedHashMap<String, String>()

    /** the language of the file */
    enum class Language { VHDL, VERILOG }

    /** a port of an entity */
    class Port(
        @JvmField val name: String,
        @JvmField val direction: BdfFile.Direction,
        @JvmField val bits: Int,
        /** true if the port is a vector, also if bits==1 */
        @JvmField val vector: Boolean,
        /** true if the default value is one */
        @JvmField val defaultHigh: Boolean
    ) {
        override fun toString() = "$name:$bits $direction"
    }

    override fun toString() = "$name$ports"

    companion object {
        private val VHDL_ENTITY = Regex("""\bentity\s+(\w+)\s+is\b""", RegexOption.IGNORE_CASE)
        private val VERILOG_MODULE = Regex("""\bmodule\s+(\w+)\s*(#\s*\((.*?)\)\s*)?\((.*?)\)\s*;(.*?)\bendmodule\b""", RegexOption.DOT_MATCHES_ALL)
        private val VHDL_INST = Regex(
            """(\w+)\s*:\s*(?:component\s+)?(?:entity\s+\w+\.)?(\w+)\s*(generic\s+map\s*\((.*?)\)\s*)?port\s+map\s*\((.*?)\)\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val VERILOG_INST = Regex("""\b(\w+)\s+(?:#\s*\((.*?)\)\s*)?(\w+)\s*\((\s*\..*?)\)\s*;""", RegexOption.DOT_MATCHES_ALL)
        private val VERILOG_DEFPARAM = Regex("""\bdefparam\s+(\w+)\.(\w+)\s*=\s*(.*?);""", RegexOption.DOT_MATCHES_ALL)
        private val VERILOG_TYPES = Regex("""\b(wire|reg|logic|signed|unsigned|tri|var)\b""")

        /** reads all entities or modules found in the given file */
        @JvmStatic
        @Throws(IOException::class)
        fun read(f: File): List<HdlEntity> {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            val n = f.name.lowercase()
            return if (n.endsWith(".v") || n.endsWith(".sv") || n.endsWith(".vh") || n.endsWith(".svh"))
                parseVerilog(text, f) else parseVHDL(text, f)
        }

        /** parses VHDL code */
        @JvmStatic
        fun parseVHDL(text: String, f: File?): List<HdlEntity> {
            val code = text.replace(Regex("--[^\n]*"), " ")
            return VHDL_ENTITY.findAll(code).map { m ->
                val name = m.groupValues[1]
                val start = m.range.last + 1
                val body = code.substring(start, findVhdlEntityEnd(code, start, name))
                val generics = LinkedHashMap<String, String>()
                extractParenBlock(body, "generic")?.let { gen ->
                    for (decl in splitTop(gen, ';')) {
                        val c = decl.indexOf(':')
                        if (c < 0) continue
                        val rest = decl.substring(c + 1)
                        val d = rest.indexOf(":=")
                        val def = if (d < 0) "" else rest.substring(d + 2).trim()
                        decl.substring(0, c).split(",").forEach { generics[it.trim().lowercase()] = def }
                    }
                }
                val ports = ArrayList<Port>()
                extractParenBlock(body, "port")?.let { block ->
                    for (decl in splitTop(block, ';')) {
                        val c = decl.indexOf(':')
                        if (c < 0) continue
                        val w = decl.substring(c + 1).trim().split(Regex("\\s+"), 2)
                        val dir = when (w[0].lowercase()) {
                            "out", "buffer" -> BdfFile.Direction.OUTPUT
                            "inout" -> BdfFile.Direction.BIDIR
                            else -> BdfFile.Direction.INPUT
                        }
                        var type = w.getOrElse(1) { "" }
                        var def = ""
                        val d = type.indexOf(":=")
                        if (d >= 0) {
                            def = type.substring(d + 2).trim()
                            type = type.substring(0, d)
                        }
                        var bits = 1
                        var vector = false
                        val p = type.indexOf('(')
                        val q = type.lastIndexOf(')')
                        if (p >= 0 && q > p) {
                            bits = vhdlRangeWidth(type.substring(p + 1, q), generics)
                            vector = true
                        } else if (type.contains("integer", true) || type.contains("natural", true)) {
                            bits = integerRangeWidth(type, generics)
                            vector = true
                        }
                        val defHigh = def.replace(" ", "").let { it.contains("'1'") }
                        decl.substring(0, c).split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { ports.add(Port(it, dir, bits, vector, defHigh)) }
                    }
                }
                HdlEntity(name, Language.VHDL, f, ports, generics).also { findVhdlMegafunction(code, it) }
            }.toList()
        }

        private fun findVhdlEntityEnd(code: String, start: Int, name: String): Int {
            val p = Regex("""\bend\s*(entity)?\s*(${Regex.escape(name)})?\s*;""", RegexOption.IGNORE_CASE)
            var pos = start
            while (true) {
                val m = p.find(code, pos) ?: return code.length
                if (balanced(code.substring(start, m.range.first))) return m.range.first
                pos = m.range.last + 1
            }
        }

        private fun balanced(s: String) = s.count { it == '(' } == s.count { it == ')' }

        private fun findVhdlMegafunction(code: String, e: HdlEntity) {
            for (m in VHDL_INST.findAll(code)) {
                val comp = m.groupValues[2].lowercase()
                if (QuartusLibrary.isMegafunction(comp)) {
                    e.megafunction = comp
                    if (m.groups[4] != null)
                        splitTop(m.groupValues[4], ',').forEach { addAssoc(it, e.megafunctionParams, true) }
                    splitTop(m.groupValues[5], ',').forEach { addAssoc(it, e.megafunctionPortMap, false) }
                    resolveSubWires(code, e.megafunctionPortMap, true)
                    return
                }
            }
        }

        private fun addAssoc(a: String, map: MutableMap<String, String>, upperKey: Boolean) {
            val p = a.indexOf("=>")
            if (p < 0) return
            val k = a.substring(0, p).trim().lowercase()
            map[if (upperKey) k.uppercase() else k] = unquote(a.substring(p + 2).trim())
        }

        /*
         * The MegaWizard connects the megafunction ports via signals called sub_wireN.
         * The sub wires are replaced by the entity ports they are connected to.
         */
        private fun resolveSubWires(code: String, portMap: MutableMap<String, String>, vhdl: Boolean) {
            val assign = HashMap<String, String>()
            val ap = if (vhdl) Regex("""(\w+)\s*(\([^;]*?\))?\s*<=\s*(\w+)\s*(\([^;]*?\))?\s*;""")
            else Regex("""\bassign\s+(\w+)\s*(\[[^;]*?])?\s*=\s*(\w+)\s*(\[[^;]*?])?\s*;""")
            for (m in ap.findAll(code)) {
                val lhs = m.groupValues[1]
                val rhs = m.groupValues[3]
                assign[rhs.lowercase()] = lhs
                assign[lhs.lowercase()] = rhs
            }
            for (en in portMap.entries) {
                val base = en.value.replace(Regex("[(\\[].*"), "").trim()
                if (base.lowercase().startsWith("sub_wire"))
                    assign[base.lowercase()]?.let { en.setValue(it) }
            }
        }

        /** parses Verilog code */
        @JvmStatic
        fun parseVerilog(text: String, f: File?): List<HdlEntity> {
            val code = text.replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("//[^\n]*"), " ")
            return VERILOG_MODULE.findAll(code).map { m ->
                val name = m.groupValues[1]
                val params = LinkedHashMap<String, String>()
                if (m.groups[3] != null) splitTop(m.groupValues[3], ',').forEach { addVerilogParam(it, params) }
                val body = m.groupValues[5]
                Regex("""\b(?:parameter|localparam)\b([^;]*);""").findAll(body)
                    .forEach { pm -> splitTop(pm.groupValues[1], ',').forEach { addVerilogParam(it, params) } }

                val ports = ArrayList<Port>()
                val headerItems = splitTop(m.groupValues[4], ',')
                val ansi = headerItems.any { it.trim().matches(Regex("(?s)^(input|output|inout)\\b.*")) }
                if (ansi) {
                    var dir = BdfFile.Direction.INPUT
                    var range: String? = null
                    for (h in headerItems) {
                        var s = h.trim()
                        Regex("(?s)^(input|output|inout)\\b(.*)").matchEntire(s)?.let {
                            dir = verilogDir(it.groupValues[1])
                            s = it.groupValues[2].trim()
                            range = null
                        }
                        s = s.replace(VERILOG_TYPES, " ").trim()
                        Regex("(?s)^\\[(.*?)]\\s*(.*)").matchEntire(s)?.let {
                            range = it.groupValues[1]
                            s = it.groupValues[2].trim()
                        }
                        val pn = s.replace(Regex("[=\\[].*"), "").trim()
                        if (pn.isNotEmpty()) ports.add(Port(pn, dir, verilogWidth(range, params), range != null, false))
                    }
                } else {
                    val decl = HashMap<String, Port>()
                    for (dm in Regex("""\b(input|output|inout)\b([^;]*);""").findAll(body)) {
                        val dir = verilogDir(dm.groupValues[1])
                        var s = dm.groupValues[2].replace(VERILOG_TYPES, " ").trim()
                        var range: String? = null
                        Regex("(?s)^\\[(.*?)]\\s*(.*)").matchEntire(s)?.let {
                            range = it.groupValues[1]
                            s = it.groupValues[2]
                        }
                        for (pn in s.split(",")) {
                            val n = pn.replace(Regex("=.*"), "").trim()
                            if (n.isNotEmpty()) decl[n] = Port(n, dir, verilogWidth(range, params), range != null, false)
                        }
                    }
                    headerItems.mapNotNullTo(ports) { decl[it.trim()] }
                }
                HdlEntity(name, Language.VERILOG, f, ports, params).also { findVerilogMegafunction(body, it) }
            }.toList()
        }

        private fun findVerilogMegafunction(body: String, e: HdlEntity) {
            for (m in VERILOG_INST.findAll(body)) {
                val comp = m.groupValues[1].lowercase()
                if (!QuartusLibrary.isMegafunction(comp)) continue
                e.megafunction = comp
                val inst = m.groupValues[3]
                if (m.groups[2] != null) splitTop(m.groupValues[2], ',').forEach { addNamedAssoc(it, e.megafunctionParams, true) }
                splitTop(m.groupValues[4], ',').forEach { addNamedAssoc(it, e.megafunctionPortMap, false) }
                for (dm in VERILOG_DEFPARAM.findAll(body))
                    if (dm.groupValues[1] == inst) e.megafunctionParams[dm.groupValues[2].uppercase()] = unquote(dm.groupValues[3].trim())
                resolveSubWires(body, e.megafunctionPortMap, false)
                return
            }
        }

        private fun addNamedAssoc(a: String, map: MutableMap<String, String>, upperKey: Boolean) {
            Regex("""(?s)\s*\.(\w+)\s*\((.*)\)\s*""").matchEntire(a)?.let {
                val k = it.groupValues[1].lowercase()
                map[if (upperKey) k.uppercase() else k] = unquote(it.groupValues[2].trim())
            }
        }

        private fun unquote(v: String) = if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v.substring(1, v.length - 1) else v

        private fun addVerilogParam(p: String, params: MutableMap<String, String>) {
            val s = p.replace(Regex("\\b(parameter|localparam|integer|signed)\\b"), " ").replace(Regex("\\[.*?]"), " ").trim()
            val e = s.indexOf('=')
            if (e > 0) params[s.substring(0, e).trim().lowercase()] = s.substring(e + 1).trim()
        }

        private fun verilogDir(d: String) = when (d) {
            "output" -> BdfFile.Direction.OUTPUT
            "inout" -> BdfFile.Direction.BIDIR
            else -> BdfFile.Direction.INPUT
        }

        private fun verilogWidth(range: String?, params: Map<String, String>): Int {
            if (range == null) return 1
            val c = range.indexOf(':')
            if (c < 0) return 1
            val a = Expression.eval(range.substring(0, c), params, 0)
            val b = Expression.eval(range.substring(c + 1), params, 0)
            return maxOf(1, (Math.abs(a - b) + 1).toInt())
        }

        private fun vhdlRangeWidth(range: String, generics: Map<String, String>): Int {
            val m = Regex("(?is)(.*)\\b(downto|to)\\b(.*)").matchEntire(range.trim()) ?: return 1
            val a = Expression.eval(m.groupValues[1], generics, 0)
            val b = Expression.eval(m.groupValues[3], generics, 0)
            return maxOf(1, (Math.abs(a - b) + 1).toInt())
        }

        private fun integerRangeWidth(type: String, generics: Map<String, String>): Int {
            val m = Regex("(?is).*\\brange\\b(.*)\\b(to|downto)\\b(.*)").matchEntire(type) ?: return 32
            val max = maxOf(Math.abs(Expression.eval(m.groupValues[1], generics, 0)), Math.abs(Expression.eval(m.groupValues[3], generics, 0)))
            var bits = 1
            while ((1L shl bits) <= max && bits < 63) bits++
            return bits
        }

        private fun extractParenBlock(body: String, keyword: String): String? {
            val m = Regex("(?i)\\b$keyword\\s*\\(").find(body) ?: return null
            var depth = 1
            for (i in m.range.last + 1 until body.length) {
                when (body[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return body.substring(m.range.last + 1, i)
                }
            }
            return null
        }

        /** splits the string at the separator, but not inside brackets or strings */
        @JvmStatic
        fun splitTop(s: String, sep: Char): List<String> {
            val l = ArrayList<String>()
            var depth = 0
            var start = 0
            var inString = false
            s.forEachIndexed { i, c ->
                if (c == '"') inString = !inString
                if (!inString) when (c) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    sep -> if (depth == 0) {
                        l.add(s.substring(start, i))
                        start = i + 1
                    }
                }
            }
            if (start < s.length && s.substring(start).isNotBlank()) l.add(s.substring(start))
            return l
        }
    }
}
