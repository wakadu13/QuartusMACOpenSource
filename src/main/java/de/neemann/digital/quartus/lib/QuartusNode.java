/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib;

import de.neemann.digital.core.BitsException;
import de.neemann.digital.core.Node;
import de.neemann.digital.core.NodeException;
import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.core.ObservableValues;
import de.neemann.digital.core.element.Element;
import de.neemann.digital.core.element.ElementAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * The simulation model of a {@link QuartusPart}.
 * Only the used ports are created as pins. The unused inputs are
 * set to their default values.
 */
public class QuartusNode extends Node implements Element {
    private final QuartusPart part;
    private final QuartusPart.Behavior behavior;
    private final List<QuartusPart.PinSpec> inSpecs;
    private final int[] inIndex;
    private final int[] inWidth;
    private final long[] inValues;
    private final long[] outValues;
    private final int[] outIndex;
    private final ObservableValue[] outputs;
    private ObservableValues inputs;

    /**
     * Creates a new instance
     *
     * @param part the part
     * @param attr the attributes
     */
    public QuartusNode(QuartusPart part, ElementAttributes attr) {
        super(true);
        this.part = part;
        Params p = part.getParams(attr);
        behavior = part.createBehavior(p);
        inSpecs = part.getInputs();

        inValues = new long[inSpecs.size()];
        ArrayList<Integer> used = new ArrayList<>();
        for (int i = 0; i < inSpecs.size(); i++) {
            QuartusPart.PinSpec s = inSpecs.get(i);
            inValues[i] = s.getDefault();
            if (p.isPortUsed(s.getName()))
                used.add(i);
        }
        inIndex = new int[used.size()];
        inWidth = new int[used.size()];
        for (int i = 0; i < inIndex.length; i++) {
            inIndex[i] = used.get(i);
            inWidth[i] = inSpecs.get(inIndex[i]).getWidth(p);
        }

        List<QuartusPart.PinSpec> outSpecs = part.getOutputs();
        outValues = new long[outSpecs.size()];
        ArrayList<Integer> usedOut = new ArrayList<>();
        for (int i = 0; i < outSpecs.size(); i++)
            if (p.isPortUsed(outSpecs.get(i).getName()))
                usedOut.add(i);
        outIndex = new int[usedOut.size()];
        outputs = new ObservableValue[usedOut.size()];
        for (int i = 0; i < outIndex.length; i++) {
            outIndex[i] = usedOut.get(i);
            QuartusPart.PinSpec s = outSpecs.get(outIndex[i]);
            outputs[i] = new ObservableValue(s.getName(), s.getWidth(p)).setDescription(s.getName());
        }
        behavior.eval(inValues, outValues);
        for (int i = 0; i < outputs.length; i++)
            outputs[i].setValue(outValues[outIndex[i]]);
    }

    @Override
    public void readInputs() throws NodeException {
        for (int i = 0; i < inIndex.length; i++)
            inValues[inIndex[i]] = inputs.get(i).getValueHighZIsZero();
        behavior.eval(inValues, outValues);
    }

    @Override
    public void writeOutputs() throws NodeException {
        for (int i = 0; i < outputs.length; i++)
            outputs[i].setValue(outValues[outIndex[i]]);
    }

    @Override
    public void setInputs(ObservableValues inputs) throws NodeException {
        this.inputs = inputs;
        if (inputs.size() != inIndex.length)
            throw new BitsException("wrong number of inputs at " + part.getQuartusName(), this, -1);
        for (int i = 0; i < inIndex.length; i++)
            inputs.get(i).addObserverToValue(this).checkBits(inWidth[i], this, i);
    }

    @Override
    public ObservableValues getOutputs() {
        return new ObservableValues(outputs);
    }
}
