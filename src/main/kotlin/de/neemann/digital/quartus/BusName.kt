/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

/**
 * A Quartus node or bus name like `a`, `a[7..0]`, `a[3]`, `d[1..0][7..0]` or `a[3..0],b,GND`.
 * A name is converted to a list of bit atoms, the first atom is the most significant bit.
 * Quartus names are case insensitive, so all atoms are lower case.
 */
class BusName private constructor(
    /** the original name */
    val name: String,
    /** the bit atoms, the first atom is the msb */
    val atoms: List<String>,
    /** the base name, e.g. "a" for "a[7..0]" */
    val base: String,
    /** true if this is a simple bus like a[7..0] */
    val isSimpleBus: Boolean
) {
    /** the number of bits */
    val bits: Int get() = maxOf(1, atoms.size)

    /** true if all atoms are constants */
    val isConstant: Boolean get() = atoms.isNotEmpty() && atoms.all { it == VCC || it == GND }

    /** a canonical key representing the bits of this name */
    val key: String get() = atoms.joinToString(",")

    override fun toString() = name

    companion object {
        /** atom representing a constant one */
        const val VCC = "vcc"

        /** atom representing a constant zero */
        const val GND = "gnd"

        private val cache = object : LinkedHashMap<String, BusName>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BusName>?) = size > 4096
        }

        /**
         * Parses the given name. If the name is not parsable it is used as a single atom.
         */
        @JvmStatic
        fun parse(name: String): BusName = synchronized(cache) { cache.getOrPut(name) { create(name) } }

        private fun create(name: String): BusName {
            val n = name.trim()
            val parts = splitTopLevel(n)
            val atoms = ArrayList<String>()
            var base: String? = null
            var simpleBus = false
            for (part in parts.map { it.trim() }.filter { it.isNotEmpty() }) {
                val b = part.indexOf('[')
                if (b > 0 && part.endsWith("]")) {
                    val bn = part.substring(0, b).trim()
                    val ranges = parseRanges(part.substring(b))
                    if (ranges == null) {
                        atoms.add(part.lowercase())
                    } else {
                        expand(bn.lowercase(), ranges, 0, atoms)
                        if (parts.size == 1) {
                            base = bn
                            simpleBus = part.contains("..")
                        }
                    }
                } else {
                    atoms.add(part.lowercase())
                    if (parts.size == 1) base = part
                }
            }
            return BusName(n, atoms, base ?: n, simpleBus)
        }

        /* two dimensional busses like d[1..0][7..0] are flattened, the first index is the most significant */
        private fun expand(prefix: String, ranges: List<IntProgression>, level: Int, atoms: MutableList<String>) {
            if (level == ranges.size) {
                atoms.add(prefix)
                return
            }
            for (i in ranges[level]) expand("$prefix[$i]", ranges, level + 1, atoms)
        }

        private fun parseRanges(s: String): List<IntProgression>? {
            val result = ArrayList<IntProgression>()
            var rest = s
            while (rest.isNotEmpty()) {
                if (!rest.startsWith("[")) return null
                val e = rest.indexOf(']')
                if (e < 0) return null
                result.add(parseRange(rest.substring(1, e)) ?: return null)
                rest = rest.substring(e + 1).trim()
            }
            return result
        }

        private fun parseRange(range: String): IntProgression? {
            val p = range.indexOf("..")
            if (p < 0) {
                val v = range.trim().toIntOrNull() ?: return null
                return v..v
            }
            val a = range.substring(0, p).trim().toIntOrNull() ?: return null
            val b = range.substring(p + 2).trim().toIntOrNull() ?: return null
            return if (a >= b) a downTo b else a..b
        }

        private fun splitTopLevel(n: String): List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            var start = 0
            n.forEachIndexed { i, c ->
                when (c) {
                    '[' -> depth++
                    ']' -> depth--
                    ',' -> if (depth == 0) {
                        parts.add(n.substring(start, i))
                        start = i + 1
                    }
                }
            }
            parts.add(n.substring(start))
            return parts
        }
    }
}
