/*
 * Copyright (c) 2016 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.elements;

import de.neemann.digital.draw.graphics.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/**
 * Used to reduce the wires found in the circuit.
 * So multiple linear connected wires a replaced by a single wire.
 */
public class WireConsistencyChecker {
    private ArrayList<Wire> wires;
    private final Collection<Vector> protectedPoints;

    /**
     * Creates a new instance
     *
     * @param wires the wires to check
     */
    public WireConsistencyChecker(ArrayList<Wire> wires) {
        this(wires, null);
    }

    /**
     * Creates a new instance
     *
     * @param wires           the wires to check
     * @param protectedPoints points where wires are not merged, e.g. pin positions. Maybe null.
     */
    public WireConsistencyChecker(ArrayList<Wire> wires, Collection<Vector> protectedPoints) {
        this.wires = wires;
        this.protectedPoints = protectedPoints;
    }

    /**
     * Performs the check
     *
     * @return the simplified wires
     */
    public ArrayList<Wire> check() {
        wires = merge(wires);
        return wires;
    }

    private ArrayList<Wire> merge(ArrayList<Wire> wires) {

        HashSet<Vector> horiPoints = new HashSet<>();
        HashSet<Vector> vertPoints = new HashSet<>();
        HashSet<Vector> diagPoints = new HashSet<>();

        ArrayList<Wire> newWires = new ArrayList<>();
        WireMerger hori = new WireMerger(Wire.Orientation.horizontal);
        WireMerger vert = new WireMerger(Wire.Orientation.vertical);

        for (Wire w : wires) {
            if (!w.p1.equals(w.p2))
                switch (w.getOrientation()) {
                    case horizontal:
                        hori.add(w);
                        horiPoints.add(w.p1);
                        horiPoints.add(w.p2);
                        break;
                    case vertical:
                        vert.add(w);
                        vertPoints.add(w.p1);
                        vertPoints.add(w.p2);
                        break;
                    default:
                        if (!contains(newWires, w))
                            newWires.add(w);
                        diagPoints.add(w.p1);
                        diagPoints.add(w.p2);
                        break;
                }
        }

        hori.protectPoints(diagPoints);
        hori.protectPoints(vertPoints);
        vert.protectPoints(diagPoints);
        vert.protectPoints(horiPoints);
        if (protectedPoints != null) {
            hori.protectPoints(protectedPoints);
            vert.protectPoints(protectedPoints);
        }

        hori.addTo(newWires);
        vert.addTo(newWires);

        return newWires;
    }

    private boolean contains(ArrayList<Wire> list, Wire wire) {
        for (Wire w : list)
            if (w.equalsContent(wire))
                return true;
        return false;
    }

}
