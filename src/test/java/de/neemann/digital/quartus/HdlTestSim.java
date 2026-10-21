/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.ObservableValues;
import de.neemann.digital.core.extern.Port;
import de.neemann.digital.core.extern.PortDefinition;
import de.neemann.digital.core.extern.handler.ProcessInterface;

import java.io.IOException;

/**
 * Drives a built-in HDL simulator like the external element of Digital does.
 */
final class HdlTestSim {
    /**
     * Starts a simulator.
     */
    interface Starter {
        ProcessInterface start(String entity, String code, PortDefinition inputs, PortDefinition outputs) throws IOException;
    }

    private final ProcessInterface p;
    private final PortDefinition in;
    private final PortDefinition out;
    private final ObservableValues ins;
    private final ObservableValues outs;

    HdlTestSim(Starter starter, String entity, String code, String inputs, String outputs) throws IOException {
        in = new PortDefinition(inputs);
        out = new PortDefinition(outputs);
        p = starter.start(entity, code, in, out);
        ins = in.createOutputs();
        outs = out.createOutputs();
        step();
    }

    private static int index(PortDefinition pd, String name) {
        int i = 0;
        for (Port port : pd) {
            if (port.getName().equals(name))
                return i;
            i++;
        }
        throw new IllegalArgumentException("port " + name + " not found");
    }

    HdlTestSim set(String name, long v) {
        ins.get(index(in, name)).setValue(v);
        return this;
    }

    HdlTestSim step() throws IOException {
        p.writeValues(ins);
        p.readValues(outs);
        return this;
    }

    long get(String name) {
        return outs.get(index(out, name)).getValue();
    }

    HdlTestSim clock(String clk) throws IOException {
        set(clk, 1).step();
        set(clk, 0).step();
        return this;
    }
}
