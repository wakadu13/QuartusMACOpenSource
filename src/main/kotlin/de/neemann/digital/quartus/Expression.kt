/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

/**
 * A tiny evaluator for the integer expressions found in HDL ranges and
 * in Quartus parameters, like `WIDTH-1`, `2**N` or `4'hA`.
 */
class Expression private constructor(
    private val s: String,
    private val vars: Map<String, String>?,
    private val depth: Int
) {
    private var pos = 0

    companion object {
        private const val MAX_DEPTH = 20

        /**
         * Evaluates the given expression.
         *
         * @param expr the expression
         * @param vars the variables, keys are lower case, values are expressions
         * @param def  the value returned if the expression is not valid
         */
        @JvmStatic
        fun eval(expr: String?, vars: Map<String, String>?, def: Long): Long = eval(expr, vars, def, 0)

        private fun eval(expr: String?, vars: Map<String, String>?, def: Long, depth: Int): Long {
            if (expr == null || depth > MAX_DEPTH) return def
            return try {
                val e = Expression(expr.trim(), vars, depth)
                val v = e.parseSum()
                e.skip()
                if (e.pos == e.s.length) v else def
            } catch (e: RuntimeException) {
                def
            }
        }

        private fun parseNumber(n: String): Long {
            val q = n.indexOf('\'')
            if (q < 0) return n.toLong()
            var r = n.substring(q + 1).lowercase()
            if (r.startsWith("s")) r = r.substring(1)
            return when (r[0]) {
                'h' -> r.substring(1).toLong(16)
                'b' -> r.substring(1).toLong(2)
                'o' -> r.substring(1).toLong(8)
                else -> r.substring(1).toLong()
            }
        }
    }

    private fun skip() {
        while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    private fun accept(t: String): Boolean {
        skip()
        if (s.startsWith(t, pos)) {
            pos += t.length
            return true
        }
        return false
    }

    private fun parseSum(): Long {
        var v = parseProduct()
        while (true) {
            v = when {
                accept("+") -> v + parseProduct()
                accept("-") -> v - parseProduct()
                else -> return v
            }
        }
    }

    private fun parseProduct(): Long {
        var v = parsePower()
        while (true) {
            skip()
            v = when {
                s.startsWith("**", pos) -> return v
                accept("*") -> v * parsePower()
                accept("/") -> v / parsePower()
                accept("mod") -> v % parsePower()
                else -> return v
            }
        }
    }

    private fun parsePower(): Long {
        val v = parseUnary()
        if (accept("**")) {
            val e = parsePower()
            var r = 1L
            repeat(e.toInt()) { r *= v }
            return r
        }
        return v
    }

    private fun parseUnary(): Long = when {
        accept("-") -> -parseUnary()
        accept("+") -> parseUnary()
        else -> parseAtom()
    }

    private fun parseAtom(): Long {
        skip()
        if (accept("(")) {
            val v = parseSum()
            if (!accept(")")) throw IllegalArgumentException("missing )")
            return v
        }
        val start = pos
        if (pos < s.length && s[pos].isDigit()) {
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '\'')) pos++
            return parseNumber(s.substring(start, pos).replace("_", ""))
        }
        while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '$')) pos++
        val id = s.substring(start, pos).lowercase()
        if (id.isEmpty()) throw IllegalArgumentException("unexpected character")
        if (id == "\$clog2" || id == "log2") {
            val v = parseAtom()
            var b = 0
            while ((1L shl b) < v) b++
            return b.toLong()
        }
        val value = vars?.get(id) ?: throw IllegalArgumentException("unknown identifier $id")
        return eval(value, vars, 0, depth + 1)
    }
}
