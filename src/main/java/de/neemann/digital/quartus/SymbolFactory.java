/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.draw.graphics.Orientation;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.digital.draw.shapes.custom.CustomShapeDescription;

import java.util.List;

/**
 * Creates drawings which look like the drawings created by Quartus:
 * input and output pins and the default rectangular block symbol.
 * All coordinates are Quartus coordinates.
 */
public final class SymbolFactory {
    /**
     * the distance of two ports
     */
    static final int STEP = 16;
    private static final int LINE = 16;
    private static final int CHAR_WIDTH = 6;

    private SymbolFactory() {
    }

    /**
     * A port of a block symbol
     */
    static final class Port {
        private final String key;
        private final String label;
        private final boolean bus;

        Port(String key, String label, boolean bus) {
            this.key = key;
            this.label = label;
            this.bus = bus;
        }

        String getKey() {
            return key;
        }
    }

    /**
     * Creates the shape of an input pin as drawn by Quartus.
     * The connection point is at (0,0).
     *
     * @param name the name of the pin
     * @param key  the key of the Digital pin including the prefix
     * @return the shape
     */
    public static CustomShapeDescription inputPinShape(String name, String key) {
        ShapeBuilder sb = new ShapeBuilder(new Vector(0, 0));
        inputPin(sb, 0, 0, name, key);
        return sb.build();
    }

    /**
     * Creates the shape of an output pin as drawn by Quartus.
     * The connection point is at (0,0).
     *
     * @param name the name of the pin
     * @param key  the key of the Digital pin including the prefix
     * @return the shape
     */
    public static CustomShapeDescription outputPinShape(String name, String key) {
        ShapeBuilder sb = new ShapeBuilder(new Vector(0, 0));
        outputPin(sb, 0, 0, name, key);
        return sb.build();
    }

    /**
     * Returns the width of the block symbol
     *
     * @param title   the title
     * @param inputs  the inputs
     * @param outputs the outputs
     * @return the width of the body
     */
    static int bodyWidth(String title, List<Port> inputs, List<Port> outputs) {
        int maxIn = 0;
        for (Port p : inputs)
            maxIn = Math.max(maxIn, p.label.length());
        int maxOut = 0;
        for (Port p : outputs)
            maxOut = Math.max(maxOut, p.label.length());
        int w = (maxIn + maxOut) * CHAR_WIDTH + 3 * CHAR_WIDTH;
        w = Math.max(w, title.length() * (CHAR_WIDTH + 1));
        w = Math.max(w, 64);
        return (w + 7) / 8 * 8;
    }

    /**
     * Draws a block symbol. The upper left corner is at (qx,qy).
     * The input pins are at x=qx, the output pins at x=qx+bodyWidth+32.
     * The first port is at y=qy+32.
     *
     * @param sb      the shape builder
     * @param qx      x
     * @param qy      y
     * @param title   the title
     * @param inputs  the inputs
     * @param outputs the outputs
     */
    static void block(ShapeBuilder sb, int qx, int qy, String title, List<Port> inputs, List<Port> outputs) {
        int w = bodyWidth(title, inputs, outputs);
        int n = Math.max(inputs.size(), outputs.size());
        int x1 = qx + LINE;
        int x2 = x1 + w;
        int y1 = qy + LINE;
        int y2 = y1 + STEP * (n + 1);
        sb.polygon(x1, y1, x2, y1, x2, y2, x1, y2);
        sb.text(title, x1, qy + 8, 8, Orientation.LEFTCENTER);
        for (int i = 0; i < inputs.size(); i++) {
            Port p = inputs.get(i);
            int y = qy + 2 * STEP + STEP * i;
            sb.line(qx, y, x1, y, p.bus);
            sb.text(p.label, x1 + 4, y, 8, Orientation.LEFTCENTER);
            sb.addPin(p.key, qx, y);
        }
        for (int i = 0; i < outputs.size(); i++) {
            Port p = outputs.get(i);
            int y = qy + 2 * STEP + STEP * i;
            sb.line(x2, y, x2 + LINE, y, p.bus);
            sb.text(p.label, x2 - 4, y, 8, Orientation.RIGHTCENTER);
            sb.addPin(p.key, x2 + LINE, y);
        }
    }

    /**
     * Draws an input pin. The connection point is at (x,y).
     *
     * @param sb   the shape builder
     * @param x    x
     * @param y    y
     * @param name the name of the pin
     * @param key  the key of the Digital pin
     */
    static void inputPin(ShapeBuilder sb, int x, int y, String name, String key) {
        int ox = x - 168;
        int oy = y - 8;
        sb.line(ox + 92, oy + 12, ox + 117, oy + 12, false);
        sb.line(ox + 92, oy + 4, ox + 117, oy + 4, false);
        sb.line(ox + 121, oy + 8, ox + 168, oy + 8, false);
        sb.line(ox + 92, oy + 12, ox + 92, oy + 4, false);
        sb.line(ox + 117, oy + 4, ox + 121, oy + 8, false);
        sb.line(ox + 117, oy + 12, ox + 121, oy + 8, false);
        sb.text("INPUT", ox + 133, oy + 4, 6, Orientation.LEFTCENTER);
        sb.text(name, ox + 88, oy + 8, 8, Orientation.RIGHTCENTER);
        sb.addPin(key, x, y);
        sb.addPin(ShapeBuilder.VALUE_POS, ox + 105, oy + 8);
    }

    /**
     * Draws an output pin. The connection point is at (x,y).
     *
     * @param sb   the shape builder
     * @param x    x
     * @param y    y
     * @param name the name of the pin
     * @param key  the key of the Digital pin
     */
    static void outputPin(ShapeBuilder sb, int x, int y, String name, String key) {
        int ox = x;
        int oy = y - 8;
        sb.line(ox, oy + 8, ox + 52, oy + 8, false);
        sb.line(ox + 52, oy + 4, ox + 78, oy + 4, false);
        sb.line(ox + 52, oy + 12, ox + 78, oy + 12, false);
        sb.line(ox + 52, oy + 12, ox + 52, oy + 4, false);
        sb.line(ox + 78, oy + 4, ox + 82, oy + 8, false);
        sb.line(ox + 82, oy + 8, ox + 78, oy + 12, false);
        sb.text("OUTPUT", ox + 1, oy + 4, 6, Orientation.LEFTCENTER);
        sb.text(name, ox + 90, oy + 8, 8, Orientation.LEFTCENTER);
        sb.addPin(key, x, y);
        sb.addPin(ShapeBuilder.VALUE_POS, ox + 65, oy + 8);
    }
}
