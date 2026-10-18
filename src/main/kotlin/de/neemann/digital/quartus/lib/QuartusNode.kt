/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib

import de.neemann.digital.core.BitsException
import de.neemann.digital.core.Model
import de.neemann.digital.core.Node
import de.neemann.digital.core.ObservableValue
import de.neemann.digital.core.ObservableValues
import de.neemann.digital.core.element.Element
import de.neemann.digital.core.element.ElementAttributes

/**
 * The simulation model of a [QuartusPart].
 * Only the used ports are created as pins, the unused inputs are set to their default values.
 */
class QuartusNode(private val part: QuartusPart, attr: ElementAttributes) : Node(true), Element {
    private val behavior: Behavior
    private val inIndex: IntArray
    private val inWidth: IntArray
    private val inValues: LongArray
    private val outValues: LongArray
    private val outIndex: IntArray
    private val outputs: Array<ObservableValue>
    private var inputs: ObservableValues? = null

    init {
        val p = part.getParams(attr)
        behavior = part.createBehavior(p)
        inValues = LongArray(part.inputs.size) { part.inputs[it].default }
        inIndex = part.inputs.indices.filter { p.isPortUsed(part.inputs[it].name) }.toIntArray()
        inWidth = IntArray(inIndex.size) { part.inputs[inIndex[it]].getWidth(p) }
        outValues = LongArray(part.outputs.size)
        behavior.highZ = LongArray(part.outputs.size)
        outIndex = part.outputs.indices.filter { p.isPortUsed(part.outputs[it].name) }.toIntArray()
        outputs = Array(outIndex.size) {
            val s = part.outputs[outIndex[it]]
            ObservableValue(s.name, s.getWidth(p)).setDescription(s.name)
        }
        behavior.eval(inValues, outValues)
        writeOutputs()
    }

    override fun init(model: Model) {
        behavior.init(model.rootPath)
        behavior.eval(inValues, outValues)
        writeOutputs()
    }

    override fun readInputs() {
        val ins = inputs ?: return
        for (i in inIndex.indices) inValues[inIndex[i]] = ins.get(i).valueHighZIsZero
        behavior.eval(inValues, outValues)
    }

    override fun writeOutputs() {
        for (i in outputs.indices) {
            val k = outIndex[i]
            val z = behavior.highZ[k]
            if (z == 0L) outputs[i].value = outValues[k]
            else outputs[i].set(outValues[k], z)
        }
    }

    override fun setInputs(inputs: ObservableValues) {
        this.inputs = inputs
        if (inputs.size != inIndex.size)
            throw BitsException("wrong number of inputs at ${part.quartusName}", this, -1)
        for (i in inIndex.indices) inputs.get(i).addObserverToValue(this).checkBits(inWidth[i], this, i)
    }

    override fun getOutputs(): ObservableValues = ObservableValues(*outputs)
}
