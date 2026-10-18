/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

/**
 * The interface of a design unit: the ports seen from the outside.
 * Every Quartus port gets a Digital pin name. Usually this is the base name
 * of the port, so "c[1..7]" becomes "c".
 */
class DesignInterface(
    /** the name of the design unit */
    val name: String,
    val kind: Kind,
    ports: List<Port>
) {
    /** the kind of the design unit */
    enum class Kind {
        /** a block design file */
        BDF,

        /** a VHDL or Verilog design simulated by an external simulator */
        HDL,

        /** a wrapper of a natively supported megafunction */
        MEGAFUNCTION,

        /** the source is missing or not supported */
        BLACKBOX
    }

    /** a port of the design unit */
    class Port(
        /** the name used by Quartus */
        val quartusName: String,
        val direction: BdfFile.Direction,
        val bits: Int,
        /** true if the default value of an unconnected input is one */
        val isDefaultHigh: Boolean
    ) {
        /** the name of the Digital pin */
        lateinit var label: String
            internal set

        /** true if this port is an output */
        val isOutput: Boolean get() = direction == BdfFile.Direction.OUTPUT

        override fun toString() = "$quartusName->$label:$bits"
    }

    /** the ports */
    val ports: List<Port> = ports

    /** additional information shown to the user */
    var info: String = ""
        private set

    private val byKey = HashMap<String, Port>()

    init {
        val baseCount = ports.groupingBy { key(BusName.parse(it.quartusName).base) }.eachCount()
        val used = HashSet<String>()
        for (p in ports) {
            val base = BusName.parse(p.quartusName).base
            val l = sanitize(if (baseCount[key(base)] == 1) base else p.quartusName)
            var unique = l
            var n = 1
            while (!used.add(key(unique))) unique = l + "_" + n++
            p.label = unique
            byKey[key(p.quartusName)] = p
            byKey.putIfAbsent(key(base), p)
            byKey.putIfAbsent(key(unique), p)
        }
    }

    /** sets additional information */
    fun setInfo(info: String): DesignInterface {
        this.info = info
        return this
    }

    /** finds the port matching the given name of a symbol port */
    fun find(quartusName: String): Port? =
        byKey[key(quartusName)] ?: byKey[key(BusName.parse(quartusName).base)]

    override fun toString() = "$name($kind)$ports"

    companion object {
        private fun key(s: String) = s.trim().lowercase().replace(" ", "")

        /** creates a valid Digital pin name */
        @JvmStatic
        fun sanitize(n: String): String {
            val s = n.trim().replace("..", "_").replace('[', '_').replace(']', ' ').trim().replace(',', '_')
                .replace(Regex("[^A-Za-z0-9_~/\\-+]"), "_")
            return s.ifEmpty { "p" }
        }
    }
}
