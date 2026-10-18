/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import java.io.File
import java.io.IOException

/**
 * Reads the memory initialization files used by Quartus:
 * Memory Initialization Files (mif) and Intel HEX files (hex).
 */
object MemoryFile {

    /**
     * Reads a memory initialization file.
     *
     * @param f     the file
     * @param width the width of a word in bits
     * @param depth the number of words
     * @return the words
     */
    @JvmStatic
    @Throws(IOException::class)
    fun read(f: File, width: Int, depth: Int): LongArray {
        val text = String(f.readBytes(), Charsets.ISO_8859_1)
        return if (f.name.lowercase().endsWith(".hex")) parseHex(text, width, depth) else parseMif(text, depth)
    }

    /** parses a memory initialization file (mif) */
    @JvmStatic
    @Throws(IOException::class)
    fun parseMif(text: String, depth: Int): LongArray {
        val clean = text.replace(Regex("(?s)%.*?%"), " ").replace(Regex("--[^\n]*"), " ")
        var addrRadix = 16
        var dataRadix = 16
        var size = depth
        val header = clean.substringBefore("CONTENT", clean.substringBefore("content"))
        for (st in header.split(";")) {
            val e = st.indexOf('=')
            if (e < 0) continue
            val k = st.substring(0, e).trim().uppercase()
            val v = st.substring(e + 1).trim().uppercase()
            when (k) {
                "DEPTH" -> size = v.toIntOrNull() ?: size
                "ADDRESS_RADIX" -> addrRadix = radix(v)
                "DATA_RADIX" -> dataRadix = radix(v)
            }
        }
        val mem = LongArray(maxOf(size, depth))
        val begin = Regex("(?i)\\bBEGIN\\b").find(clean) ?: throw IOException("mif: CONTENT BEGIN missing")
        val end = Regex("(?i)\\bEND\\b").find(clean, begin.range.last) ?: throw IOException("mif: END missing")
        for (st in clean.substring(begin.range.last + 1, end.range.first).split(";")) {
            val c = st.indexOf(':')
            if (c < 0) continue
            val addr = st.substring(0, c).trim()
            val values = st.substring(c + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { parse(it, dataRadix) }
            if (values.isEmpty()) continue
            if (addr.startsWith("[")) {
                val r = addr.trim('[', ']').split("..")
                val from = parse(r[0], addrRadix).toInt()
                val to = parse(r[1], addrRadix).toInt()
                var i = 0
                for (a in from..to) if (a in mem.indices) mem[a] = values[i++ % values.size]
            } else {
                var a = parse(addr, addrRadix).toInt()
                for (v in values) if (a in mem.indices) mem[a++] = v
            }
        }
        return mem
    }

    private fun radix(v: String) = when (v) {
        "BIN" -> 2
        "OCT" -> 8
        "DEC", "UNS" -> 10
        else -> 16
    }

    private fun parse(s: String, radix: Int): Long {
        val t = s.trim()
        if (t.startsWith("-")) return -t.substring(1).toLong(radix)
        return java.lang.Long.parseUnsignedLong(t, radix)
    }

    /** parses an Intel HEX file, every record contains one or more words */
    @JvmStatic
    @Throws(IOException::class)
    fun parseHex(text: String, width: Int, depth: Int): LongArray {
        val mem = LongArray(depth)
        val bytesPerWord = (width + 7) / 8
        var upper = 0L
        for (l in text.lines()) {
            val line = l.trim()
            if (!line.startsWith(":") || line.length < 11) continue
            val len = line.substring(1, 3).toInt(16)
            val addr = line.substring(3, 7).toInt(16)
            when (line.substring(7, 9).toInt(16)) {
                0 -> {
                    var a = (upper + addr).toInt()
                    var i = 0
                    while (i + bytesPerWord <= len) {
                        var w = 0L
                        for (b in 0 until bytesPerWord) {
                            val p = 9 + (i + b) * 2
                            w = (w shl 8) or line.substring(p, p + 2).toLong(16)
                        }
                        if (a in mem.indices) mem[a] = w
                        a++
                        i += bytesPerWord
                    }
                }
                1 -> return mem
                2 -> upper = line.substring(9, 13).toLong(16) * 16
                4 -> upper = line.substring(9, 13).toLong(16) shl 16
            }
        }
        return mem
    }
}
