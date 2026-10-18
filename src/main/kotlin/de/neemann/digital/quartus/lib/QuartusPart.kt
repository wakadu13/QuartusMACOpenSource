/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib

import de.neemann.digital.core.element.ElementAttributes
import de.neemann.digital.core.element.ElementTypeDescription
import de.neemann.digital.core.element.Keys
import de.neemann.digital.core.element.PinDescription
import de.neemann.digital.core.element.PinDescriptions
import de.neemann.digital.core.element.PinInfo
import de.neemann.digital.draw.graphics.Vector
import de.neemann.digital.draw.shapes.QuartusShape
import de.neemann.digital.draw.shapes.Shape
import de.neemann.digital.quartus.QSymbol
import de.neemann.digital.quartus.ShapeBuilder
import de.neemann.digital.quartus.SymbolFactory

/**
 * The specification of a Quartus component: its ports, its behaviour and its symbol.
 * From this specification a Digital element type is created.
 *
 * @param quartusName the name used by Quartus
 * @param description a short description
 * @param category    the folder in the Quartus library, e.g. "primitives/storage"
 */
class QuartusPart(val quartusName: String, val description: String, val category: String) {
    /** specification of a port */
    class PinSpec internal constructor(
        val name: String,
        private val width: (Params) -> Int,
        /** the default value used if the input is not connected */
        val default: Long,
        /** true if this is a clock input */
        val isClock: Boolean
    ) {
        /** returns the width of this pin */
        fun getWidth(p: Params): Int = width(p).coerceIn(1, 64)
    }

    /** the input specifications */
    val inputs = ArrayList<PinSpec>()

    /** the output specifications */
    val outputs = ArrayList<PinSpec>()

    /** the default parameters */
    var defaultParams = ""
        private set

    private var behavior: (Params) -> Behavior = { Behavior() }
    private var symbol: ((Params) -> QSymbol)? = null

    /** the name of the Digital element */
    val digitalName: String get() = PREFIX + quartusName.uppercase()

    /** adds an input */
    fun input(name: String, default: Long = 0, width: (Params) -> Int = ONE) = apply { inputs.add(PinSpec(name, width, default, false)) }

    /** adds some single bit inputs */
    fun inputs(vararg names: String) = apply { names.forEach { input(it) } }

    /** adds a clock input */
    fun clock(name: String) = apply { inputs.add(PinSpec(name, ONE, 0, true)) }

    /** adds an output */
    fun output(name: String, width: (Params) -> Int = ONE) = apply { outputs.add(PinSpec(name, width, 0, false)) }

    /** adds some single bit outputs */
    fun outputs(vararg names: String) = apply { names.forEach { output(it) } }

    /** sets the behaviour */
    fun behavior(b: (Params) -> Behavior) = apply { behavior = b }

    /** sets a combinatorial behaviour */
    fun comb(f: (LongArray, LongArray) -> Unit) = apply { behavior = { Behavior(f) } }

    /** sets the symbol */
    fun symbol(s: (Params) -> QSymbol) = apply { symbol = s }

    /** sets the default parameters */
    fun defaults(p: String) = apply { defaultParams = p }

    /** returns the input with the given name, case insensitive */
    fun getInput(name: String): PinSpec? = inputs.firstOrNull { it.name.equals(name, true) }

    /** returns the output with the given name, case insensitive */
    fun getOutput(name: String): PinSpec? = outputs.firstOrNull { it.name.equals(name, true) }

    internal fun createBehavior(p: Params): Behavior = behavior(p)

    /** returns the parameters of an element including the defaults */
    fun getParams(attr: ElementAttributes): Params {
        val p = Params(defaultParams)
        p.map.putAll(Params(attr).map)
        return p
    }

    /** returns the symbol of this part, only the used ports are included */
    fun getSymbol(p: Params): QSymbol = symbol?.invoke(p) ?: SymbolFactory.block(quartusName,
        inputs.filter { p.isPortUsed(it.name) }.map { SymbolFactory.Port(it.name, it.name, it.getWidth(p) > 1) },
        outputs.filter { p.isPortUsed(it.name) }.map { SymbolFactory.Port(it.name, it.name, it.getWidth(p) > 1) })

    /** the Digital element type description */
    val typeDescription: ElementTypeDescription by lazy {
        object : ElementTypeDescription(digitalName, { attr -> QuartusNode(this, attr) }) {
            override fun getInputDescription(elementAttributes: ElementAttributes): PinDescriptions {
                val p = getParams(elementAttributes)
                val l = inputs.filter { p.isPortUsed(it.name) }.map {
                    val pi = PinInfo.input(it.name, it.name)
                    if (it.isClock) pi.setClock()
                    pi as PinDescription
                }
                return PinDescriptions(*l.toTypedArray())
            }
        }.addAttribute(Keys.LABEL).addAttribute(Keys.QUARTUS_PARAMS).setShortName(quartusName)
    }

    /** creates the shape used if the element has no individual drawing */
    fun createShape(attr: ElementAttributes, ins: PinDescriptions, outs: PinDescriptions): Shape {
        val p = getParams(attr)
        val s = getSymbol(p)
        val sb = ShapeBuilder(Vector(0, 0))
        s.typeText?.let { sb.addText(it, 0, 0) }
        sb.addSymbol(s, 0, 0) { port ->
            when {
                getInput(port.name) != null && p.isPortUsed(port.name) -> ShapeBuilder.IN + getInput(port.name)!!.name
                getOutput(port.name) != null && p.isPortUsed(port.name) -> ShapeBuilder.OUT + getOutput(port.name)!!.name
                else -> null
            }
        }
        s.instanceText?.let { sb.setLabel(it.rect.x1, it.rect.y1) }
        return QuartusShape(sb.build(), null, ins, outs, attr.label)
    }

    override fun toString() = quartusName

    companion object {
        /** prefix of the Digital element names */
        const val PREFIX = "Quartus_"
        private val ONE: (Params) -> Int = { 1 }

        /** a width given by a parameter */
        @JvmStatic
        fun w(param: String, def: Int): (Params) -> Int = { it.getInt(param, def) }
    }
}
