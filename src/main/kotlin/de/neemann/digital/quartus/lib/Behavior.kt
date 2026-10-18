/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib

import java.io.File

/**
 * The behaviour of a Quartus component.
 * [eval] is called every time an input changes. The inputs and outputs are
 * given in the order of the specification of the part.
 *
 * @param f the function used to evaluate a combinatorial component
 */
open class Behavior(private val f: ((LongArray, LongArray) -> Unit)? = null) {
    /** the high-Z mask of every output, set it to make an output high-Z */
    var highZ: LongArray = LongArray(0)
        internal set

    /** called before the simulation starts, root is the folder of the circuit */
    open fun init(root: File?) {}

    /** calculates the new outputs */
    open fun eval(i: LongArray, o: LongArray) {
        f?.invoke(i, o)
    }
}

/** detects rising and falling edges */
class Edge {
    private var last = false

    /** true if the value changed from zero to one */
    fun rising(v: Long): Boolean {
        val n = (v and 1L) != 0L
        val r = n && !last
        last = n
        return r
    }

    /** true if the value changed from one to zero */
    fun falling(v: Long): Boolean {
        val n = (v and 1L) != 0L
        val r = !n && last
        last = n
        return r
    }
}

/** true if bit 0 is set */
fun Long.isSet(): Boolean = (this and 1L) != 0L

/** one or zero */
fun Boolean.toBit(): Long = if (this) 1L else 0L

/** a mask with the given number of bits */
fun mask(bits: Int): Long = if (bits >= 64) -1L else (1L shl bits) - 1
