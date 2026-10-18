/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import java.io.IOException

/**
 * A node of a s-expression as used in the Quartus block design files (bdf)
 * and block symbol files (bsf), e.g. `(pt 168 8)`.
 * An argument is either a [String] (quoted string or atom) or a nested [SExpr].
 */
class SExpr private constructor(val name: String, val line: Int) {
    private val argList = ArrayList<Any>()

    /** all arguments */
    val args: List<Any> get() = argList

    /** all children which are nodes */
    val children: List<SExpr> get() = argList.filterIsInstance<SExpr>()

    /** the string arguments */
    val strings: List<String> get() = argList.filterIsInstance<String>()

    /** returns all children with the given name */
    fun getChildren(n: String): List<SExpr> = children.filter { it.name.equals(n, ignoreCase = true) }

    /** returns the first child with the given name or null */
    operator fun get(n: String): SExpr? = children.firstOrNull { it.name.equals(n, ignoreCase = true) }

    /** true if a child (or flag like `(input)`) with the given name exists */
    fun has(n: String): Boolean = get(n) != null

    /** returns the n-th string argument or null */
    fun getString(n: Int): String? = strings.getOrNull(n)

    /** returns the n-th string argument as an integer */
    fun getInt(n: Int, def: Int): Int {
        val s = getString(n)?.trim() ?: return def
        return s.toIntOrNull() ?: s.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: def
    }

    override fun toString(): String =
        "(" + name + argList.joinToString("") { if (it is String) " \"$it\"" else " $it" } + ")"

    companion object {
        /**
         * Parses the given text. C-style comments are skipped.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun parse(text: String): List<SExpr> = Parser(text).parseAll()
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private var line = 1

        fun parseAll(): List<SExpr> {
            val list = ArrayList<SExpr>()
            while (true) {
                skipWhite()
                if (pos >= text.length) return list
                val c = text[pos]
                if (c == '(') list.add(parseNode())
                else throw IOException("unexpected character '$c' in line $line")
            }
        }

        private fun parseNode(): SExpr {
            pos++ // skip '('
            skipWhite()
            val startLine = line
            val n = readAtom()
            if (n.isEmpty()) throw IOException("node name expected in line $line")
            val node = SExpr(n, startLine)
            while (true) {
                skipWhite()
                if (pos >= text.length)
                    throw IOException("unexpected end of file in node '$n' starting in line $startLine")
                when (val c = text[pos]) {
                    ')' -> {
                        pos++
                        return node
                    }
                    '(' -> node.argList.add(parseNode())
                    '"' -> node.argList.add(readString())
                    else -> {
                        val a = readAtom()
                        if (a.isEmpty()) throw IOException("unexpected character '$c' in line $line")
                        node.argList.add(a)
                    }
                }
            }
        }

        private fun readString(): String {
            pos++ // skip quote
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when {
                    c == '"' && pos < text.length && text[pos] == '"' -> {
                        // Quartus doubles a quote inside a string
                        sb.append('"')
                        pos++
                    }
                    c == '"' -> return sb.toString()
                    c == '\\' && pos < text.length && text[pos] == '"' -> {
                        sb.append('"')
                        pos++
                    }
                    else -> {
                        if (c == '\n') line++
                        sb.append(c)
                    }
                }
            }
            throw IOException("unterminated string in line $line")
        }

        private fun readAtom(): String {
            val start = pos
            while (pos < text.length) {
                val c = text[pos]
                if (c.isWhitespace() || c == '(' || c == ')' || c == '"') break
                pos++
            }
            return text.substring(start, pos)
        }

        private fun skipWhite() {
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c == '\n' -> {
                        line++
                        pos++
                    }
                    c.isWhitespace() || c == '﻿' -> pos++
                    text.startsWith("/*", pos) -> {
                        val e = text.indexOf("*/", pos + 2)
                        if (e < 0) throw IOException("unterminated comment in line $line")
                        line += text.substring(pos, e).count { it == '\n' }
                        pos = e + 2
                    }
                    text.startsWith("//", pos) -> while (pos < text.length && text[pos] != '\n') pos++
                    else -> return
                }
            }
        }
    }
}
