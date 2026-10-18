/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib

import de.neemann.digital.core.element.ElementAttributes
import de.neemann.digital.core.element.Keys
import de.neemann.digital.quartus.Expression

/**
 * The parameters of a Quartus component.
 * Stored as a string like "LPM_WIDTH=8;LPM_DIRECTION=UP" in the attribute [Keys.QUARTUS_PARAMS].
 * The special parameter PORTS contains the list of the used ports.
 */
class Params() {
    /** the parameter map, keys in upper case */
    val map = LinkedHashMap<String, String>()

    /** creates parameters from a string */
    constructor(s: String?) : this() {
        s?.split(";")?.forEach {
            val e = it.indexOf('=')
            if (e > 0) map[it.substring(0, e).trim().uppercase()] = it.substring(e + 1).trim()
        }
    }

    /** creates parameters from the attributes */
    constructor(attr: ElementAttributes) : this(attr.get(Keys.QUARTUS_PARAMS))

    /** sets a parameter */
    fun set(key: String, value: String): Params {
        map[key.uppercase()] = value
        return this
    }

    /** returns a parameter or the default value */
    fun get(key: String, def: String): String = map[key.uppercase()]?.takeIf { it.isNotEmpty() } ?: def

    /** returns an integer parameter */
    fun getLong(key: String, def: Long): Long {
        val t = map[key.uppercase()]?.trim()?.takeIf { it.isNotEmpty() } ?: return def
        try {
            if (t.matches(Regex("(?i)[0-9a-f]+h"))) return t.dropLast(1).toLong(16)
            if (t.matches(Regex("(?i)h\"[0-9a-f]+\""))) return t.substring(2, t.length - 1).toLong(16)
            if (t.matches(Regex("(?i)b\"[01]+\""))) return t.substring(2, t.length - 1).toLong(2)
        } catch (e: NumberFormatException) {
            return def
        }
        return Expression.eval(t, null, def)
    }

    /** returns an integer parameter */
    fun getInt(key: String, def: Int): Int = getLong(key, def.toLong()).toInt()

    /** returns true if the given port is used. If no port list is given, all ports are used. */
    fun isPortUsed(port: String): Boolean {
        val ports = map[PORTS] ?: return true
        return ports.split(",").any { it.trim().equals(port, ignoreCase = true) }
    }

    override fun toString() = map.entries.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        /** name of the parameter which holds the list of the used ports */
        const val PORTS = "PORTS"
    }
}
