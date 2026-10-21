/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.integration.Resources;
import junit.framework.TestCase;

import java.io.File;

public class PinPlannerTest extends TestCase {

    public void testCircuit() throws Exception {
        Circuit circuit = Circuit.loadCircuit(new File(Resources.getRoot(), "../../main/dig/combinatorial/Xor1.dig"),
                new ShapeFactory(new ElementLibrary()));
        PinPlanner pp = PinPlanner.create(circuit, Board.DE0);
        assertEquals(3, pp.getSignals().size());
        PinPlanner.Signal a = pp.getSignals().get(0);
        PinPlanner.Signal b = pp.getSignals().get(1);
        PinPlanner.Signal y = pp.getSignals().get(2);
        assertEquals("A", a.getLabel());
        assertEquals("Y", y.getLabel());
        assertFalse(y.getInput());

        assertEquals(PinPlanner.Result.OK, pp.assign(a, "J6"));
        assertEquals(PinPlanner.Result.OK, pp.assign(b, "PIN_H5"));
        assertEquals(PinPlanner.Result.OK, pp.assign(y, "J1"));
        assertEquals("J6", a.getPinNumber());
        assertEquals("SW1", pp.resources(b));
        assertEquals("LEDG0", pp.resources(y));
        assertEquals(PinPlanner.Result.WRONG_DIRECTION, pp.assign(a, "J1"));
        assertEquals(PinPlanner.Result.WRONG_DIRECTION, pp.assign(y, "J6"));
        assertEquals(PinPlanner.Result.NO_RESOURCE, pp.assign(y, "XX"));

        // a resource used by another signal is taken from it
        assertEquals(PinPlanner.Result.OK, pp.assign(b, "J6"));
        assertEquals("", a.getPinNumber());
        assertEquals(b, pp.owner("J6").getFirst());
    }

    public void testBus() {
        PinPlanner pp = new PinPlanner(Board.DE0);
        PinPlanner.Signal q = new PinPlanner.Signal(null, "Q", true, false, new String[4]);
        PinPlanner.Signal seg = new PinPlanner.Signal(null, "seg", false, false, new String[7]);
        pp.getSignals().add(q);
        pp.getSignals().add(seg);
        assertEquals(PinPlanner.Result.NOT_ENOUGH_RESOURCES, pp.assign(q, "E4"));
        assertEquals(PinPlanner.Result.OK, pp.assign(q, "H6"));
        assertEquals("H6,G4,G5,J7", q.getPinNumber());
        assertEquals("SW2..SW5", pp.resources(q));
        assertEquals(PinPlanner.Result.OK, pp.assign(seg, "E11"));
        assertEquals("HEX0.a..HEX0.g", pp.resources(seg));

        pp.setPinNumber(q, "J6, , H6");
        assertEquals("J6,,H6", q.getPinNumber());
        assertEquals("SW0, -, SW2, -", pp.resources(q));
        pp.clear(q);
        assertEquals("", q.getPinNumber());
    }
}
