/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The content of a Quartus block design file (bdf) or block symbol file (bsf).
 * All coordinates are stored in Quartus units. In Quartus the coordinates of ports and
 * drawings are stored relative to the upper left corner of the bounding rectangle
 * of a symbol or a pin, and they are already transformed (rotated/mirrored).
 */
public final class BdfFile {
    private final String headerType;
    private final List<Pin> pins = new ArrayList<>();
    private final List<Symbol> symbols = new ArrayList<>();
    private final List<Connector> connectors = new ArrayList<>();
    private final List<Point> junctions = new ArrayList<>();
    private final List<Graphic> graphics = new ArrayList<>();

    /**
     * Reads a file
     *
     * @param file the file
     * @return the parsed file
     * @throws IOException IOException
     */
    public static BdfFile read(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        String text = new String(data, StandardCharsets.ISO_8859_1);
        try {
            return parse(text);
        } catch (IOException e) {
            throw new IOException(file.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses the given text
     *
     * @param text the text
     * @return the parsed file
     * @throws IOException IOException
     */
    public static BdfFile parse(String text) throws IOException {
        List<SExpr> nodes = SExpr.parse(text);
        String type = "graphic";
        if (!nodes.isEmpty() && nodes.get(0).getName().equalsIgnoreCase("header")) {
            String t = nodes.get(0).getString(0);
            if (t != null)
                type = t.toLowerCase(Locale.ROOT);
        } else
            throw new IOException("not a Quartus block design or symbol file: header missing");
        BdfFile f = new BdfFile(type);
        for (SExpr n : nodes) {
            switch (n.getName().toLowerCase(Locale.ROOT)) {
                case "pin":
                    f.pins.add(new Pin(n));
                    break;
                case "symbol":
                case "block":
                    f.symbols.add(new Symbol(n));
                    break;
                case "connector":
                    f.connectors.add(new Connector(n));
                    break;
                case "junction":
                    SExpr pt = n.get("pt");
                    if (pt != null)
                        f.junctions.add(new Point(pt));
                    break;
                case "text":
                case "line":
                case "rectangle":
                case "circle":
                case "arc":
                    Graphic g = Graphic.create(n);
                    if (g != null)
                        f.graphics.add(g);
                    break;
                default:
                    // header and unknown entries are ignored
                    break;
            }
        }
        return f;
    }

    private BdfFile(String headerType) {
        this.headerType = headerType;
    }

    /**
     * @return true if this is a block symbol file
     */
    public boolean isSymbolFile() {
        return headerType.equals("symbol");
    }

    /**
     * @return the pins
     */
    public List<Pin> getPins() {
        return pins;
    }

    /**
     * @return the symbols and blocks
     */
    public List<Symbol> getSymbols() {
        return symbols;
    }

    /**
     * @return the connectors (wires and busses)
     */
    public List<Connector> getConnectors() {
        return connectors;
    }

    /**
     * @return the junctions
     */
    public List<Point> getJunctions() {
        return junctions;
    }

    /**
     * @return free graphics and texts drawn on the schematic
     */
    public List<Graphic> getGraphics() {
        return graphics;
    }

    /**
     * A point
     */
    public static final class Point {
        /**
         * x coordinate
         */
        public final int x;
        /**
         * y coordinate
         */
        public final int y;

        /**
         * Creates a new point
         *
         * @param x x
         * @param y y
         */
        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        private Point(SExpr pt) {
            this(pt.getInt(0, 0), pt.getInt(1, 0));
        }

        /**
         * Adds an offset
         *
         * @param dx dx
         * @param dy dy
         * @return the new point
         */
        public Point add(int dx, int dy) {
            return new Point(x + dx, y + dy);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Point point = (Point) o;
            return x == point.x && y == point.y;
        }

        @Override
        public int hashCode() {
            return x * 31 + y;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + ")";
        }
    }

    /**
     * A rectangle
     */
    public static final class Rect {
        /**
         * left
         */
        public final int x1;
        /**
         * top
         */
        public final int y1;
        /**
         * right
         */
        public final int x2;
        /**
         * bottom
         */
        public final int y2;

        /**
         * Creates a new rectangle
         *
         * @param x1 x1
         * @param y1 y1
         * @param x2 x2
         * @param y2 y2
         */
        public Rect(int x1, int y1, int x2, int y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        private static Rect of(SExpr n) {
            if (n == null)
                return new Rect(0, 0, 0, 0);
            return new Rect(n.getInt(0, 0), n.getInt(1, 0), n.getInt(2, 0), n.getInt(3, 0));
        }

        /**
         * @return the width
         */
        public int width() {
            return Math.abs(x2 - x1);
        }

        /**
         * @return the height
         */
        public int height() {
            return Math.abs(y2 - y1);
        }
    }

    /**
     * A text
     */
    public static final class Text {
        /**
         * the text
         */
        public final String text;
        /**
         * the bounding rectangle
         */
        public final Rect rect;
        /**
         * the font size in points, 0 if not given
         */
        public final int fontSize;
        /**
         * the font name
         */
        public final String font;
        /**
         * true if bold
         */
        public final boolean bold;
        /**
         * true if vertical
         */
        public final boolean vertical;
        /**
         * true if invisible
         */
        public final boolean invisible;

        /**
         * Creates a text from a node
         *
         * @param n the node
         */
        public Text(SExpr n) {
            String t = n.getString(0);
            text = t == null ? "" : t;
            rect = Rect.of(n.get("rect"));
            SExpr f = n.get("font");
            if (f != null) {
                font = f.getString(0);
                SExpr fs = f.get("font_size");
                fontSize = fs == null ? 0 : fs.getInt(0, 0);
                bold = f.has("bold");
            } else {
                font = "Arial";
                fontSize = 0;
                bold = false;
            }
            vertical = n.has("vertical");
            invisible = n.has("invisible");
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * The kind of a graphic primitive
     */
    public enum GraphicType {
        /**
         * a line
         */
        LINE,
        /**
         * a rectangle
         */
        RECTANGLE,
        /**
         * a circle or ellipse
         */
        CIRCLE,
        /**
         * an elliptic arc
         */
        ARC,
        /**
         * a text
         */
        TEXT
    }

    /**
     * A graphic primitive
     */
    public static final class Graphic {
        /**
         * the type
         */
        public final GraphicType type;
        /**
         * first point
         */
        public final Point p1;
        /**
         * second point
         */
        public final Point p2;
        /**
         * the bounding rectangle
         */
        public final Rect rect;
        /**
         * the line width
         */
        public final int lineWidth;
        /**
         * the text if this is a text
         */
        public final Text text;

        private Graphic(GraphicType type, Point p1, Point p2, Rect rect, int lineWidth, Text text) {
            this.type = type;
            this.p1 = p1;
            this.p2 = p2;
            this.rect = rect;
            this.lineWidth = lineWidth;
            this.text = text;
        }

        /**
         * Creates a graphic primitive from the given node
         *
         * @param n the node
         * @return the graphic or null if not a graphic node
         */
        public static Graphic create(SExpr n) {
            SExpr lw = n.get("line_width");
            int lineWidth = lw == null ? 1 : lw.getInt(0, 1);
            List<SExpr> pts = n.getChildren("pt");
            Point p1 = pts.size() > 0 ? new Point(pts.get(0)) : null;
            Point p2 = pts.size() > 1 ? new Point(pts.get(1)) : null;
            Rect r = n.get("rect") == null ? null : Rect.of(n.get("rect"));
            switch (n.getName().toLowerCase(Locale.ROOT)) {
                case "line":
                    if (p1 == null || p2 == null) return null;
                    return new Graphic(GraphicType.LINE, p1, p2, r, lineWidth, null);
                case "rectangle":
                    if (r == null) return null;
                    return new Graphic(GraphicType.RECTANGLE, p1, p2, r, lineWidth, null);
                case "circle":
                    if (r == null) return null;
                    return new Graphic(GraphicType.CIRCLE, p1, p2, r, lineWidth, null);
                case "arc":
                    if (r == null || p1 == null || p2 == null) return null;
                    return new Graphic(GraphicType.ARC, p1, p2, r, lineWidth, null);
                case "text":
                    return new Graphic(GraphicType.TEXT, null, null, r, lineWidth, new Text(n));
                default:
                    return null;
            }
        }

        private static List<Graphic> createAll(SExpr drawing) {
            ArrayList<Graphic> l = new ArrayList<>();
            if (drawing != null)
                for (SExpr c : drawing.getChildren()) {
                    Graphic g = create(c);
                    if (g != null)
                        l.add(g);
                }
            return l;
        }
    }

    /**
     * The direction of a port
     */
    public enum Direction {
        /**
         * input
         */
        INPUT,
        /**
         * output
         */
        OUTPUT,
        /**
         * bidirectional
         */
        BIDIR
    }

    private static Direction readDirection(SExpr n) {
        if (n.has("output"))
            return Direction.OUTPUT;
        if (n.has("bidir"))
            return Direction.BIDIR;
        return Direction.INPUT;
    }

    private static String readTransform(SExpr n) {
        for (SExpr c : n.getChildren()) {
            String name = c.getName().toLowerCase(Locale.ROOT);
            if (c.getArgs().isEmpty() && (name.startsWith("rotate") || name.startsWith("flip")))
                return name;
        }
        return "";
    }

    /**
     * A port of a symbol
     */
    public static final class Port {
        /**
         * the connection point relative to the symbol
         */
        public final Point pt;
        /**
         * the direction
         */
        public final Direction direction;
        /**
         * the name of the port
         */
        public final Text name;
        /**
         * the text drawn inside the symbol
         */
        public final Text label;
        /**
         * the line connecting the port to the symbol body
         */
        public final Graphic line;

        private Port(SExpr n) {
            SExpr p = n.get("pt");
            pt = p == null ? new Point(0, 0) : new Point(p);
            direction = readDirection(n);
            List<SExpr> texts = n.getChildren("text");
            name = texts.isEmpty() ? null : new Text(texts.get(0));
            label = texts.size() > 1 ? new Text(texts.get(1)) : name;
            SExpr l = n.get("line");
            line = l == null ? null : Graphic.create(l);
        }

        /**
         * @return the name of the port
         */
        public String getName() {
            if (name == null)
                return "";
            return name.text.trim();
        }

        /**
         * @return true if the port is drawn as a bus
         */
        public boolean isBusLine() {
            return line != null && line.lineWidth >= 3;
        }
    }

    /**
     * A symbol instance in a block design file
     */
    public static final class Symbol {
        /**
         * the bounding rectangle in absolute coordinates
         */
        public final Rect rect;
        /**
         * the name of the symbol type
         */
        public final Text type;
        /**
         * the name of the instance
         */
        public final Text instance;
        /**
         * the ports
         */
        public final List<Port> ports = new ArrayList<>();
        /**
         * the drawing of the symbol
         */
        public final List<Graphic> drawing;
        /**
         * the parameters
         */
        public final LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        /**
         * the transformation flag like rotate90 or flipx
         */
        public final String transform;
        /**
         * true if this is a block (created with the block tool) and not a symbol
         */
        public final boolean isBlock;

        private Symbol(SExpr n) {
            isBlock = n.getName().equalsIgnoreCase("block");
            rect = Rect.of(n.get("rect"));
            List<SExpr> texts = n.getChildren("text");
            type = texts.isEmpty() ? null : new Text(texts.get(0));
            instance = texts.size() > 1 ? new Text(texts.get(1)) : null;
            for (SExpr p : n.getChildren("port"))
                ports.add(new Port(p));
            ArrayList<Graphic> d = new ArrayList<>(Graphic.createAll(n.get("drawing")));
            if (isBlock) {
                SExpr r = n.get("rect");
                if (r != null)
                    d.add(new Graphic(GraphicType.RECTANGLE, null, null,
                            new Rect(0, 0, rect.width(), rect.height()), 1, null));
            }
            drawing = d;
            for (SExpr p : n.getChildren("parameter"))
                addParameter(p);
            SExpr ab = n.get("annotation_block");
            if (ab != null)
                for (SExpr p : ab.getChildren("parameter"))
                    addParameter(p);
            transform = readTransform(n);
        }

        private void addParameter(SExpr p) {
            String pn = p.getString(0);
            String pv = p.getString(1);
            if (pn != null && pv != null)
                parameters.put(pn.toUpperCase(Locale.ROOT), pv);
        }

        /**
         * @return the name of the symbol type
         */
        public String getTypeName() {
            if (type == null)
                return "";
            return type.text.trim();
        }

        /**
         * @return the name of the instance
         */
        public String getInstanceName() {
            if (instance == null)
                return "";
            return instance.text.trim();
        }

        /**
         * Returns the absolute position of the given port
         *
         * @param p the port
         * @return the absolute position
         */
        public Point getAbsolutePos(Port p) {
            return new Point(rect.x1 + p.pt.x, rect.y1 + p.pt.y);
        }
    }

    /**
     * A pin (input, output or bidir) of a block design file
     */
    public static final class Pin {
        /**
         * the direction
         */
        public final Direction direction;
        /**
         * the bounding rectangle in absolute coordinates
         */
        public final Rect rect;
        /**
         * the pin type text (INPUT, OUTPUT, BIDIR)
         */
        public final Text typeText;
        /**
         * the pins name
         */
        public final Text name;
        /**
         * the default value text (VCC or GND), may be null
         */
        public final Text defaultValue;
        /**
         * the connection point relative to the rectangle
         */
        public final Point pt;
        /**
         * the drawing
         */
        public final List<Graphic> drawing;
        /**
         * the transformation flag like rotate90 or flipx
         */
        public final String transform;

        private Pin(SExpr n) {
            direction = readDirection(n);
            rect = Rect.of(n.get("rect"));
            List<SExpr> texts = n.getChildren("text");
            typeText = texts.isEmpty() ? null : new Text(texts.get(0));
            name = texts.size() > 1 ? new Text(texts.get(1)) : null;
            defaultValue = texts.size() > 2 ? new Text(texts.get(2)) : null;
            SExpr p = n.get("pt");
            pt = p == null ? new Point(0, 0) : new Point(p);
            drawing = Graphic.createAll(n.get("drawing"));
            transform = readTransform(n);
        }

        /**
         * @return the pins name
         */
        public String getName() {
            if (name == null)
                return "";
            return name.text.trim();
        }

        /**
         * @return the absolute connection point
         */
        public Point getAbsolutePos() {
            return new Point(rect.x1 + pt.x, rect.y1 + pt.y);
        }

        /**
         * @return true if the default value of this pin is VCC
         */
        public boolean isDefaultVCC() {
            return defaultValue != null && defaultValue.text.trim().equalsIgnoreCase("VCC");
        }
    }

    /**
     * A connector (wire or bus segment)
     */
    public static final class Connector {
        /**
         * first point
         */
        public final Point p1;
        /**
         * second point
         */
        public final Point p2;
        /**
         * the label, may be null
         */
        public final Text label;
        /**
         * true if this is a bus
         */
        public final boolean bus;
        /**
         * true if this is a conduit
         */
        public final boolean conduit;

        private Connector(SExpr n) {
            List<SExpr> pts = n.getChildren("pt");
            p1 = pts.size() > 0 ? new Point(pts.get(0)) : new Point(0, 0);
            p2 = pts.size() > 1 ? new Point(pts.get(1)) : p1;
            SExpr t = n.get("text");
            Text l = t == null ? null : new Text(t);
            if (l != null && l.text.trim().isEmpty())
                l = null;
            label = l;
            bus = n.has("bus");
            conduit = n.has("conduit");
        }

        /**
         * @return the label text or null
         */
        public String getLabelText() {
            if (label == null)
                return null;
            return label.text.trim();
        }
    }
}
