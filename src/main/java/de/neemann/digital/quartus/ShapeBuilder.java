/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.draw.graphics.Orientation;
import de.neemann.digital.draw.graphics.Polygon;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.digital.draw.shapes.custom.CustomShapeDescription;

import java.awt.Color;
import java.util.List;

/**
 * Converts Quartus drawings to a {@link CustomShapeDescription}.
 * The Quartus coordinates are scaled by 2.5, so that the Quartus grid of 8 units
 * maps to the Digital grid of 20 units.
 */
public final class ShapeBuilder {
    /**
     * Prefix of input pins
     */
    public static final String IN = "i:";
    /**
     * Prefix of output pins
     */
    public static final String OUT = "o:";
    /**
     * Name of the pseudo pin which marks the position used to show a value
     */
    public static final String VALUE_POS = "v:value";

    private static final Color COLOR = Color.BLACK;
    private static final int THIN = 3;
    private static final int BUS = 8;
    private static final int ARC_SEGMENTS = 24;

    private final CustomShapeDescription.Builder builder = new CustomShapeDescription.Builder();
    private final Vector pos;
    private boolean empty = true;

    /**
     * Scales a Quartus coordinate to a Digital coordinate
     *
     * @param q the Quartus coordinate
     * @return the Digital coordinate
     */
    public static int scale(int q) {
        return (int) Math.round(q * 2.5);
    }

    /**
     * Scales a Quartus point to a Digital vector
     *
     * @param p the point
     * @return the vector
     */
    public static Vector scale(BdfFile.Point p) {
        return new Vector(scale(p.x), scale(p.y));
    }

    /**
     * Creates a new builder.
     *
     * @param pos the position of the Digital element in Digital coordinates
     */
    public ShapeBuilder(Vector pos) {
        this.pos = pos;
    }

    private Vector v(double qx, double qy) {
        return new Vector((int) Math.round(qx * 2.5) - pos.x, (int) Math.round(qy * 2.5) - pos.y);
    }

    /**
     * Adds a list of graphics
     *
     * @param graphics the graphics
     * @param ox       x offset of the graphics in Quartus coordinates
     * @param oy       y offset of the graphics in Quartus coordinates
     * @return this for chained calls
     */
    public ShapeBuilder add(List<BdfFile.Graphic> graphics, int ox, int oy) {
        for (BdfFile.Graphic g : graphics)
            add(g, ox, oy);
        return this;
    }

    /**
     * Adds a graphic
     *
     * @param g  the graphic
     * @param ox x offset of the graphic in Quartus coordinates
     * @param oy y offset of the graphic in Quartus coordinates
     * @return this for chained calls
     */
    public ShapeBuilder add(BdfFile.Graphic g, int ox, int oy) {
        int th = g.lineWidth >= 2 ? BUS : THIN;
        switch (g.type) {
            case LINE:
                builder.addLine(v(g.p1.x + ox, g.p1.y + oy), v(g.p2.x + ox, g.p2.y + oy), th, COLOR);
                break;
            case RECTANGLE:
                builder.addPolygon(new Polygon(true)
                        .add(v(g.rect.x1 + ox, g.rect.y1 + oy))
                        .add(v(g.rect.x2 + ox, g.rect.y1 + oy))
                        .add(v(g.rect.x2 + ox, g.rect.y2 + oy))
                        .add(v(g.rect.x1 + ox, g.rect.y2 + oy)), th, COLOR, false);
                break;
            case CIRCLE:
                builder.addCircle(v(g.rect.x1 + ox, g.rect.y1 + oy), v(g.rect.x2 + ox, g.rect.y2 + oy), th, COLOR, false);
                break;
            case ARC:
                addArc(g, ox, oy, th);
                break;
            case TEXT:
                addText(g.text, ox, oy);
                break;
            default:
                break;
        }
        empty = false;
        return this;
    }

    private void addArc(BdfFile.Graphic g, int ox, int oy, int th) {
        double cx = (g.rect.x1 + g.rect.x2) / 2.0;
        double cy = (g.rect.y1 + g.rect.y2) / 2.0;
        double rx = Math.abs(g.rect.x2 - g.rect.x1) / 2.0;
        double ry = Math.abs(g.rect.y2 - g.rect.y1) / 2.0;
        if (rx == 0 || ry == 0)
            return;
        // Quartus draws arcs counter clockwise (as seen on the screen) from p1 to p2
        double a1 = Math.atan2((g.p1.y - cy) / ry, (g.p1.x - cx) / rx);
        double a2 = Math.atan2((g.p2.y - cy) / ry, (g.p2.x - cx) / rx);
        while (a2 >= a1)
            a2 -= 2 * Math.PI;
        Polygon p = new Polygon(false);
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            double a = a1 + (a2 - a1) * i / ARC_SEGMENTS;
            p.add(v(cx + rx * Math.cos(a) + ox, cy + ry * Math.sin(a) + oy));
        }
        builder.addPolygon(p, th, COLOR, false);
    }

    /**
     * Adds a text
     *
     * @param t  the text
     * @param ox x offset of the text in Quartus coordinates
     * @param oy y offset of the text in Quartus coordinates
     * @return this for chained calls
     */
    public ShapeBuilder addText(BdfFile.Text t, int ox, int oy) {
        if (t == null || t.invisible || t.text.trim().isEmpty())
            return this;
        BdfFile.Rect r = t.rect;
        int h = t.vertical ? r.width() : r.height();
        int size = fontSize(t, h);
        if (t.vertical) {
            Vector p = v((r.x1 + r.x2) / 2.0 + ox, r.y2 + oy);
            builder.addText(p, p.add(0, -1), t.text, Orientation.LEFTCENTER, size, COLOR);
        } else {
            Vector p = v(r.x1 + ox, (r.y1 + r.y2) / 2.0 + oy);
            builder.addText(p, p.add(1, 0), t.text, Orientation.LEFTCENTER, size, COLOR);
        }
        empty = false;
        return this;
    }

    private static int fontSize(BdfFile.Text t, int rectHeight) {
        int pt = t.fontSize;
        if (pt <= 0)
            pt = 8;
        int fromPt = (int) Math.round(pt * 3.2);
        if (rectHeight > 0) {
            int fromRect = (int) Math.round(rectHeight * 2.5 * 0.85);
            return Math.max(10, Math.min(fromPt, fromRect));
        }
        return fromPt;
    }

    /**
     * Adds a pin
     *
     * @param name the name of the pin, including the prefix
     * @param qx   x position in absolute Quartus coordinates
     * @param qy   y position in absolute Quartus coordinates
     * @return this for chained calls
     */
    public ShapeBuilder addPin(String name, int qx, int qy) {
        builder.addPin(name, v(qx, qy), false);
        return this;
    }

    /**
     * Adds a pin at an exact position
     *
     * @param name the name of the pin, including the prefix
     * @param abs  the absolute position in Digital coordinates
     * @return this for chained calls
     */
    public ShapeBuilder addPinDigital(String name, Vector abs) {
        builder.addPin(name, abs.sub(pos), false);
        return this;
    }

    /**
     * Sets the position of the label
     *
     * @param qx x position in absolute Quartus coordinates
     * @param qy y position in absolute Quartus coordinates
     * @return this for chained calls
     */
    public ShapeBuilder setLabel(int qx, int qy) {
        Vector p = v(qx, qy);
        builder.setLabel(p, p.add(1, 0), Orientation.LEFTTOP, 24, COLOR);
        return this;
    }

    /**
     * @return true if nothing is drawn
     */
    public boolean isEmpty() {
        return empty;
    }

    /**
     * @return the shape description
     */
    public CustomShapeDescription build() {
        return builder.build();
    }
}
