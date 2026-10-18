/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.basic.And;
import de.neemann.digital.core.basic.NAnd;
import de.neemann.digital.core.basic.NOr;
import de.neemann.digital.core.basic.Not;
import de.neemann.digital.core.basic.Or;
import de.neemann.digital.core.basic.XNOr;
import de.neemann.digital.core.basic.XOr;
import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.io.Const;
import de.neemann.digital.core.io.In;
import de.neemann.digital.core.io.Out;
import de.neemann.digital.core.wiring.Clock;
import de.neemann.digital.core.wiring.Driver;
import de.neemann.digital.core.wiring.Splitter;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.Tunnel;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.digital.gui.components.data.DummyElement;
import de.neemann.digital.quartus.lib.Params;
import de.neemann.digital.quartus.lib.QuartusPart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts a single block design file to a Digital circuit.
 * The connectivity is computed in Quartus coordinates: wires are connected at
 * their end points and at points lying on other wires, nets with the same name
 * are connected, and parts of busses are connected by name ("a[3]" is a part of "a[7..0]").
 * Connections by name are realized with (mostly invisible) tunnels and splitters.
 */
final class BdfConverter {
    private static final Pattern CLOCK_NAME = Pattern.compile("(?i)(.*(clk|clock|horloge).*|h\\d+.*)");
    private static final int HIDDEN_STEP = 13;

    private final QuartusImporter importer;
    private final BdfFile bdf;
    private final String unitName;
    private final boolean top;
    private final DesignInterface ifc;
    private final Circuit circuit = new Circuit();

    private final HashMap<BdfFile.Point, Integer> pointIds = new HashMap<>();
    private final ArrayList<BdfFile.Point> points = new ArrayList<>();
    private final ArrayList<Integer> parent = new ArrayList<>();
    private final ArrayList<Terminal> terminals = new ArrayList<>();
    private final ArrayList<Elem> elems = new ArrayList<>();
    private final ArrayList<Net> nets = new ArrayList<>();
    private final ArrayList<Signal> signals = new ArrayList<>();
    private final ArrayList<BdfFile.Point[]> pieces = new ArrayList<>();
    private final HashSet<Vector> usedPositions = new HashSet<>();
    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int hiddenCounter;
    private int tmpCounter;

    /**
     * A connection point of a pin or a symbol port
     */
    private static final class Terminal {
        private final BdfFile.Point p;
        private final Elem owner;
        private final String pinKey;
        private final boolean driver;
        private final String name;
        private int bits;
        private long unconnectedDefault;
        private boolean clockInput;
        private Net net;

        private Terminal(BdfFile.Point p, Elem owner, String pinKey, boolean driver, int bits, String name) {
            this.p = p;
            this.owner = owner;
            this.pinKey = pinKey;
            this.driver = driver;
            this.bits = bits;
            this.name = name;
        }
    }

    /**
     * An element to create
     */
    private static final class Elem {
        private final String elementName;
        private final ElementAttributes attr;
        private final ArrayList<Terminal> terms = new ArrayList<>();
        private BdfFile.Symbol symbol;
        private BdfFile.Pin pin;
        private QuartusLibrary.Mapping mapping;
        private QuartusPart part;
        private Params params;
        private boolean constant;
        private boolean constHigh;
        private boolean bitwise;
        private boolean buffer;

        private Elem(String elementName, ElementAttributes attr) {
            this.elementName = elementName;
            this.attr = attr;
        }
    }

    /**
     * A set of connected wires and terminals
     */
    private static final class Net {
        private final ArrayList<Terminal> terms = new ArrayList<>();
        private final ArrayList<BdfFile.Connector> connectors = new ArrayList<>();
        private final ArrayList<BusName> labels = new ArrayList<>();
        private final ArrayList<BdfFile.Point[]> pieces = new ArrayList<>();
        private BdfFile.Point anchor;
        private boolean bus;
        private Signal signal;
    }

    /**
     * A set of nets with the same name
     */
    private static final class Signal {
        private final ArrayList<Net> nets = new ArrayList<>();
        private final ArrayList<BusName> labels = new ArrayList<>();
        private String tunnelName;
        private int bits;
        private boolean needsTunnel;
        private boolean driven;
        private boolean hasSinks;
        private boolean hasLabeledConnector;
        private boolean decoration;

        private BusName primary() {
            return labels.isEmpty() ? null : labels.get(0);
        }
    }

    /**
     * A reference to a bit of a signal
     */
    private static final class BitRef {
        private final Signal signal;
        private final int bit;
        private final long constValue;

        private BitRef(Signal signal, int bit) {
            this.signal = signal;
            this.bit = bit;
            this.constValue = -1;
        }

        private BitRef(long constValue) {
            this.signal = null;
            this.bit = 0;
            this.constValue = constValue;
        }
    }

    BdfConverter(QuartusImporter importer, BdfFile bdf, String unitName, boolean top, DesignInterface ifc) {
        this.importer = importer;
        this.bdf = bdf;
        this.unitName = unitName;
        this.top = top;
        this.ifc = ifc;
    }

    /**
     * Creates the Digital circuit
     *
     * @return the circuit
     */
    Circuit convert() {
        collectPins();
        collectSymbols();
        buildNets();
        buildSignals();
        resolveWidths();
        registerUsedPositions();
        createElements();
        connectSignals();
        createFreeGraphics();
        createWires();
        return circuit;
    }

    private void warn(String msg) {
        importer.warn(unitName + ": " + msg);
    }

    // ------------------------------------------------------------------------------------
    // collect elements
    // ------------------------------------------------------------------------------------

    private void updateBounds(BdfFile.Rect r) {
        minX = Math.min(minX, Math.min(r.x1, r.x2));
        minY = Math.min(minY, Math.min(r.y1, r.y2));
        maxX = Math.max(maxX, Math.max(r.x1, r.x2));
    }

    private void collectPins() {
        for (BdfFile.Pin p : bdf.getPins()) {
            updateBounds(p.rect);
            String name = p.getName();
            BusName bn = BusName.parse(name);
            DesignInterface.Port port = ifc.find(name);
            String label = port == null ? DesignInterface.sanitize(name) : port.getLabel();
            boolean input = p.direction != BdfFile.Direction.OUTPUT;
            if (p.direction == BdfFile.Direction.BIDIR)
                warn("bidirectional pin '" + name + "' is treated as an input");

            ElementAttributes attr = new ElementAttributes()
                    .set(Keys.LABEL, label)
                    .setBits(bn.getBits());
            if (top && importer.getProject() != null) {
                String loc = importer.getProject().getPinLocation(name);
                if (loc != null)
                    attr.set(Keys.PINNUMBER, loc);
            }
            Elem e = new Elem(input ? In.DESCRIPTION.getName() : Out.DESCRIPTION.getName(), attr);
            e.pin = p;
            Terminal t = new Terminal(p.getAbsolutePos(), e, input ? ShapeBuilder.OUT + "out" : ShapeBuilder.IN + "in",
                    input, bn.getBits(), "pin " + name);
            e.terms.add(t);
            terminals.add(t);
            elems.add(e);
        }
    }

    private void collectSymbols() {
        for (BdfFile.Symbol s : bdf.getSymbols()) {
            updateBounds(s.rect);
            String type = s.getTypeName();
            QuartusLibrary.Mapping m = QuartusLibrary.map(type);
            if (m != null && m.getPart() != null && !allPortsKnown(s, m)) {
                warn("symbol '" + type + "' (" + s.getInstanceName() + ") has unknown ports, it is imported as a sub design");
                m = null;
            }
            if (m != null)
                collectPrimitive(s, m);
            else
                collectSubDesign(s);
        }
    }

    private boolean allPortsKnown(BdfFile.Symbol s, QuartusLibrary.Mapping m) {
        for (BdfFile.Port p : s.ports)
            if (m.getPinKey(p.getName()) == null)
                return false;
        return true;
    }

    private void collectPrimitive(BdfFile.Symbol s, QuartusLibrary.Mapping m) {
        ElementAttributes attr = new ElementAttributes(m.getAttributes());
        Elem e = new Elem(m.getElement(), attr);
        e.symbol = s;
        e.mapping = m;
        e.part = m.getPart();
        e.constant = m.isConstant();
        e.constHigh = e.constant && attr.get(Keys.VALUE) != 0;
        String en = m.getElement();
        e.bitwise = en.equals(And.DESCRIPTION.getName()) || en.equals(Or.DESCRIPTION.getName())
                || en.equals(NAnd.DESCRIPTION.getName()) || en.equals(NOr.DESCRIPTION.getName())
                || en.equals(XOr.DESCRIPTION.getName()) || en.equals(XNOr.DESCRIPTION.getName())
                || en.equals(Not.DESCRIPTION.getName()) || en.equals(Driver.DESCRIPTION.getName());
        e.buffer = en.equals(Splitter.DESCRIPTION.getName());
        if (e.part != null) {
            Params p = new Params(e.part.getDefaultParams());
            for (Map.Entry<String, String> en2 : s.parameters.entrySet())
                p.set(en2.getKey(), en2.getValue());
            e.params = p;
            if (!s.getInstanceName().isEmpty())
                attr.set(Keys.LABEL, s.getInstanceName());
        }
        for (BdfFile.Port port : s.ports) {
            Terminal t;
            if (e.constant)
                t = new Terminal(s.getAbsolutePos(port), e, ShapeBuilder.OUT + "out", true, -1, type(s));
            else {
                String key = m.getPinKey(port.getName());
                if (key == null) {
                    warn("unknown port '" + port.getName() + "' at " + type(s));
                    continue;
                }
                boolean out = key.startsWith(ShapeBuilder.OUT);
                int bits = -1;
                if (e.part != null) {
                    String pn = key.substring(2);
                    QuartusPart.PinSpec spec = out ? e.part.getOutput(pn) : e.part.getInput(pn);
                    bits = spec.getWidth(e.params);
                    if (!out) {
                        t = new Terminal(s.getAbsolutePos(port), e, key, false, bits, type(s) + "." + port.getName());
                        t.clockInput = spec.isClock();
                        t.unconnectedDefault = spec.getDefault();
                        e.terms.add(t);
                        terminals.add(t);
                        continue;
                    }
                }
                t = new Terminal(s.getAbsolutePos(port), e, key, out, bits, type(s) + "." + port.getName());
            }
            e.terms.add(t);
            terminals.add(t);
        }
        elems.add(e);
    }

    private static String type(BdfFile.Symbol s) {
        String i = s.getInstanceName();
        if (i.isEmpty())
            return s.getTypeName();
        return s.getTypeName() + " (" + i + ")";
    }

    private void collectSubDesign(BdfFile.Symbol s) {
        DesignInterface sub = importer.getInterface(s.getTypeName(), s);
        ElementAttributes attr = new ElementAttributes();
        if (!s.getInstanceName().isEmpty())
            attr.set(Keys.LABEL, s.getInstanceName());
        Elem e = new Elem(importer.getElementName(sub), attr);
        e.symbol = s;
        for (BdfFile.Port port : s.ports) {
            DesignInterface.Port ip = sub.find(port.getName());
            if (ip == null) {
                warn("port '" + port.getName() + "' not found in design '" + s.getTypeName() + "'");
                continue;
            }
            boolean out = ip.isOutput();
            String key = (out ? ShapeBuilder.OUT : ShapeBuilder.IN) + ip.getLabel();
            Terminal t = new Terminal(s.getAbsolutePos(port), e, key, out, ip.getBits(), type(s) + "." + port.getName());
            t.unconnectedDefault = ip.isDefaultHigh() ? -1 : 0;
            t.clockInput = !out && CLOCK_NAME.matcher(ip.getQuartusName()).matches();
            e.terms.add(t);
            terminals.add(t);
        }
        elems.add(e);
    }

    // ------------------------------------------------------------------------------------
    // build nets
    // ------------------------------------------------------------------------------------

    private int id(BdfFile.Point p) {
        Integer i = pointIds.get(p);
        if (i == null) {
            i = points.size();
            pointIds.put(p, i);
            points.add(p);
            parent.add(i);
        }
        return i;
    }

    private int find(int i) {
        while (parent.get(i) != i) {
            parent.set(i, parent.get(parent.get(i)));
            i = parent.get(i);
        }
        return i;
    }

    private void union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra != rb)
            parent.set(ra, rb);
    }

    private static boolean onSegment(BdfFile.Point a, BdfFile.Point b, BdfFile.Point p) {
        long cross = (long) (b.x - a.x) * (p.y - a.y) - (long) (b.y - a.y) * (p.x - a.x);
        if (cross != 0)
            return false;
        return Math.min(a.x, b.x) <= p.x && p.x <= Math.max(a.x, b.x)
                && Math.min(a.y, b.y) <= p.y && p.y <= Math.max(a.y, b.y);
    }

    private void buildNets() {
        HashSet<BdfFile.Point> special = new HashSet<>();
        for (BdfFile.Connector c : bdf.getConnectors()) {
            special.add(c.p1);
            special.add(c.p2);
        }
        for (Terminal t : terminals)
            special.add(t.p);
        special.addAll(bdf.getJunctions());
        for (BdfFile.Point p : special)
            id(p);

        for (BdfFile.Connector c : bdf.getConnectors()) {
            if (c.p1.equals(c.p2))
                continue;
            ArrayList<BdfFile.Point> on = new ArrayList<>();
            for (BdfFile.Point p : special)
                if (onSegment(c.p1, c.p2, p))
                    on.add(p);
            on.sort((a, b) -> Long.compare(dist(c.p1, a), dist(c.p1, b)));
            for (int i = 1; i < on.size(); i++) {
                union(id(on.get(i - 1)), id(on.get(i)));
                pieces.add(new BdfFile.Point[]{on.get(i - 1), on.get(i), c.bus ? on.get(i) : null});
            }
        }

        HashMap<Integer, Net> netMap = new HashMap<>();
        for (Terminal t : terminals) {
            Net n = netMap.computeIfAbsent(find(id(t.p)), k -> new Net());
            n.terms.add(t);
            t.net = n;
            if (n.anchor == null)
                n.anchor = t.p;
        }
        for (BdfFile.Connector c : bdf.getConnectors()) {
            Net n = netMap.computeIfAbsent(find(id(c.p1)), k -> new Net());
            if (c.bus)
                n.bus = true;
            if (n.anchor == null || n.connectors.isEmpty())
                n.anchor = c.p1;
            n.connectors.add(c);
            String l = c.getLabelText();
            if (l != null)
                n.labels.add(BusName.parse(l));
        }
        for (Terminal t : terminals)
            if (t.owner.pin != null && !t.owner.pin.getName().isEmpty())
                t.net.labels.add(BusName.parse(t.owner.pin.getName()));
        for (BdfFile.Point[] pc : pieces)
            netMap.get(find(id(pc[0]))).pieces.add(pc);
        nets.addAll(netMap.values());
    }

    private static long dist(BdfFile.Point a, BdfFile.Point b) {
        long dx = a.x - b.x;
        long dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private void buildSignals() {
        HashMap<String, Signal> byKey = new HashMap<>();
        for (Net n : nets) {
            Signal s = null;
            for (BusName l : n.labels) {
                Signal o = byKey.get(l.getKey());
                if (o != null && o != s) {
                    if (s == null)
                        s = o;
                    else
                        s = merge(s, o, byKey);
                }
            }
            if (s == null) {
                s = new Signal();
                signals.add(s);
            }
            s.nets.add(n);
            n.signal = s;
            for (BusName l : n.labels) {
                if (!byKey.containsKey(l.getKey())) {
                    s.labels.add(l);
                    byKey.put(l.getKey(), s);
                }
            }
            for (BdfFile.Connector c : n.connectors)
                if (c.getLabelText() != null)
                    s.hasLabeledConnector = true;
        }
    }

    private Signal merge(Signal a, Signal b, HashMap<String, Signal> byKey) {
        for (Net n : b.nets) {
            a.nets.add(n);
            n.signal = a;
        }
        for (BusName l : b.labels) {
            a.labels.add(l);
            byKey.put(l.getKey(), a);
        }
        a.hasLabeledConnector |= b.hasLabeledConnector;
        signals.remove(b);
        return a;
    }

    private void resolveWidths() {
        int n = 0;
        for (Signal s : signals) {
            BusName p = s.primary();
            int bits = -1;
            if (p != null && !p.isConstant())
                bits = p.getBits();
            for (Net net : s.nets)
                for (Terminal t : net.terms) {
                    if (t.bits > 0 && bits < 0)
                        bits = t.bits;
                    if (t.driver)
                        s.driven = true;
                    else if (!isIsolated(t))
                        s.hasSinks = true;
                }
            if (bits < 0)
                bits = 1;
            s.bits = bits;
            if (p == null && isConstantOnly(s) && hasMixedWidths(s)) {
                // e.g. VCC connected to busses of different widths
                s.decoration = true;
                s.tunnelName = "$" + unitName + "_n" + (n++);
                long v = constantValue(s);
                for (Net net : s.nets)
                    for (Terminal t : net.terms)
                        t.unconnectedDefault = v;
                continue;
            }
            for (Net net : s.nets)
                for (Terminal t : net.terms) {
                    if (t.bits < 0)
                        t.bits = bits;
                    else if (t.bits != bits)
                        warn("width mismatch at " + t.name + ": " + t.bits + " bits connected to a net with " + bits + " bits");
                }
            if (p != null)
                s.tunnelName = p.getName();
            else
                s.tunnelName = "$" + unitName + "_n" + (n++);
        }
    }

    private static boolean isConstantOnly(Signal s) {
        boolean found = false;
        for (Net n : s.nets)
            for (Terminal t : n.terms)
                if (t.driver) {
                    if (!t.owner.constant)
                        return false;
                    found = true;
                }
        return found;
    }

    private static long constantValue(Signal s) {
        for (Net n : s.nets)
            for (Terminal t : n.terms)
                if (t.driver && t.owner.constHigh)
                    return -1;
        return 0;
    }

    private static boolean hasMixedWidths(Signal s) {
        int w = -1;
        for (Net n : s.nets)
            for (Terminal t : n.terms)
                if (!t.driver && t.bits > 0) {
                    if (w >= 0 && w != t.bits)
                        return true;
                    w = t.bits;
                }
        return false;
    }

    // ------------------------------------------------------------------------------------
    // create the circuit
    // ------------------------------------------------------------------------------------

    private void registerUsedPositions() {
        for (BdfFile.Point[] pc : pieces) {
            usedPositions.add(ShapeBuilder.scale(pc[0]));
            usedPositions.add(ShapeBuilder.scale(pc[1]));
        }
        for (Terminal t : terminals)
            usedPositions.add(ShapeBuilder.scale(t.p));
    }

    private void createWires() {
        HashSet<BdfFile.Point> junctions = new HashSet<>(bdf.getJunctions());
        for (Net n : nets) {
            if (n.signal.decoration) {
                // the net can not be represented by a Digital net, so the wires are only drawn
                if (n.pieces.isEmpty())
                    continue;
                BdfFile.Point p0 = n.pieces.get(0)[0];
                Vector pos = ShapeBuilder.scale(p0);
                ShapeBuilder sb = new ShapeBuilder(pos);
                for (BdfFile.Point[] pc : n.pieces) {
                    sb.line(pc[0].x, pc[0].y, pc[1].x, pc[1].y, pc[2] != null);
                    for (BdfFile.Point p : pc)
                        if (p != null && junctions.remove(p))
                            sb.polygon(p.x - 2, p.y - 2, p.x + 2, p.y - 2, p.x + 2, p.y + 2, p.x - 2, p.y + 2);
                }
                VisualElement ve = ShapeBuilder.createElement(DummyElement.TEXTDESCRIPTION.getName(), pos);
                ve.getElementAttributes().set(Keys.CUSTOM_SHAPE, sb.build());
                circuit.add(ve);
            } else {
                for (BdfFile.Point[] pc : n.pieces) {
                    Vector v1 = ShapeBuilder.scale(pc[0]);
                    Vector v2 = ShapeBuilder.scale(pc[1]);
                    if (!v1.equals(v2))
                        circuit.getWires().add(new Wire(v1, v2));
                }
            }
        }
    }

    private void createElements() {
        for (Elem e : elems) {
            if (e.pin != null)
                createPinElement(e);
            else
                createSymbolElement(e);
        }
    }

    private void createPinElement(Elem e) {
        BdfFile.Pin p = e.pin;
        Vector pos = ShapeBuilder.scale(new BdfFile.Point(p.rect.x1, p.rect.y1));
        ShapeBuilder sb = new ShapeBuilder(pos);
        sb.add(p.drawing, p.rect.x1, p.rect.y1);
        sb.addText(p.typeText, p.rect.x1, p.rect.y1);
        sb.addText(p.name, p.rect.x1, p.rect.y1);
        sb.addText(p.defaultValue, p.rect.x1, p.rect.y1);
        Terminal t = e.terms.get(0);
        String elementName = e.elementName;
        String key = t.pinKey;
        if (top && t.driver && t.bits == 1 && isClock(t)) {
            elementName = Clock.DESCRIPTION.getName();
            e.attr.set(Keys.FREQUENCY, 1);
            key = ShapeBuilder.OUT + "C";
        }
        sb.addPin(key, t.p.x, t.p.y);
        BdfFile.Point vp = valuePos(p);
        sb.addPin(ShapeBuilder.VALUE_POS, vp.x + p.rect.x1, vp.y + p.rect.y1);
        e.attr.set(Keys.CUSTOM_SHAPE, sb.build());
        VisualElement ve = ShapeBuilder.createElement(elementName, pos, e.attr);
        circuit.add(ve);
    }

    private boolean isClock(Terminal pinTerm) {
        if (CLOCK_NAME.matcher(pinTerm.owner.pin.getName()).matches())
            return true;
        for (Net n : pinTerm.net.signal.nets)
            for (Terminal t : n.terms)
                if (t.clockInput && t.owner.part != null)
                    return true;
        return false;
    }

    /*
     * The position used to show the value: the center of the pin drawing without the connection line.
     */
    private static BdfFile.Point valuePos(BdfFile.Pin p) {
        int x1 = Integer.MAX_VALUE;
        int y1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE;
        int y2 = Integer.MIN_VALUE;
        for (BdfFile.Graphic g : p.drawing) {
            if (g.type != BdfFile.GraphicType.LINE)
                continue;
            if (g.p1.equals(p.pt) || g.p2.equals(p.pt))
                continue;
            x1 = Math.min(x1, Math.min(g.p1.x, g.p2.x));
            x2 = Math.max(x2, Math.max(g.p1.x, g.p2.x));
            y1 = Math.min(y1, Math.min(g.p1.y, g.p2.y));
            y2 = Math.max(y2, Math.max(g.p1.y, g.p2.y));
        }
        if (x1 > x2)
            return p.pt;
        return new BdfFile.Point((x1 + x2) / 2, (y1 + y2) / 2);
    }

    private void createSymbolElement(Elem e) {
        BdfFile.Symbol s = e.symbol;
        Vector pos = ShapeBuilder.scale(new BdfFile.Point(s.rect.x1, s.rect.y1));
        ShapeBuilder sb = new ShapeBuilder(pos);
        sb.add(s.drawing, s.rect.x1, s.rect.y1);
        if (s.type != null && !isPrimitiveTypeHidden(e))
            sb.addText(s.type, s.rect.x1, s.rect.y1);
        sb.addText(s.instance, s.rect.x1, s.rect.y1);
        for (BdfFile.Port port : s.ports) {
            if (port.line != null)
                sb.add(port.line, s.rect.x1, s.rect.y1);
            if (port.label != null && !port.label.invisible)
                sb.addText(port.label, s.rect.x1, s.rect.y1);
        }

        ElementAttributes attr = e.attr;
        int width = 1;
        for (Terminal t : e.terms)
            width = Math.max(width, t.bits);

        if (e.constant && !e.terms.isEmpty() && e.terms.get(0).net.signal.decoration) {
            VisualElement ve = ShapeBuilder.createElement(DummyElement.TEXTDESCRIPTION.getName(), pos);
            ve.getElementAttributes().set(Keys.CUSTOM_SHAPE, sb.build());
            circuit.add(ve);
            return;
        }

        if (e.constant) {
            attr.setBits(width);
            attr.set(Keys.VALUE, e.constHigh ? (width >= 64 ? -1L : (1L << width) - 1) : 0L);
        } else if (e.bitwise && width > 1) {
            attr.setBits(width);
        } else if (e.buffer) {
            attr.set(Keys.INPUT_SPLIT, Integer.toString(width)).set(Keys.OUTPUT_SPLIT, Integer.toString(width));
        }

        if (e.part != null) {
            StringBuilder ports = new StringBuilder();
            for (Terminal t : e.terms) {
                boolean used = t.driver || isConnected(t) || t.net.signal.decoration;
                if (used) {
                    if (ports.length() > 0)
                        ports.append(',');
                    ports.append(t.pinKey.substring(2));
                }
            }
            e.params.set(Params.PORTS, ports.toString());
            attr.set(Keys.QUARTUS_PARAMS, e.params.toString());
        }

        for (Terminal t : e.terms) {
            String key = t.pinKey;
            if (e.buffer && width > 1)
                key = key.substring(0, 2) + splitterPin(0, width);
            if (e.part != null && !t.driver && !isConnected(t) && !t.net.signal.decoration)
                continue;
            sb.addPin(key, t.p.x, t.p.y);
            if (!t.driver && !isConnected(t))
                addDefault(t);
        }

        attr.set(Keys.CUSTOM_SHAPE, sb.build());
        VisualElement ve = ShapeBuilder.createElement(e.elementName, pos, attr);
        circuit.add(ve);
    }

    private boolean isPrimitiveTypeHidden(Elem e) {
        return e.symbol.type.invisible;
    }

    /*
     * A terminal is connected if its net contains something else
     */
    private static boolean isConnected(Terminal t) {
        Net n = t.net;
        if (n.signal.decoration)
            return false;
        return !isIsolated(t);
    }

    private static boolean isIsolated(Terminal t) {
        Net n = t.net;
        return n.terms.size() <= 1 && n.connectors.isEmpty() && n.signal.nets.size() <= 1 && n.labels.isEmpty();
    }

    private void addDefault(Terminal t) {
        long v = t.unconnectedDefault;
        if (t.bits < 64 && v != 0)
            v = v & ((1L << t.bits) - 1);
        Vector p = ShapeBuilder.scale(t.p);
        addHiddenConst(p, t.bits, v);
    }

    private void addHiddenConst(Vector p, int bits, long value) {
        ShapeBuilder sb = new ShapeBuilder(p);
        sb.addPinDigital(ShapeBuilder.OUT + "out", p);
        VisualElement ve = ShapeBuilder.createElement(Const.DESCRIPTION.getName(), p);
        ve.getElementAttributes()
                .setBits(bits)
                .set(Keys.VALUE, value)
                .set(Keys.CUSTOM_SHAPE, sb.build());
        circuit.add(ve);
    }

    private void addTunnel(Vector p, String name, BdfFile.Text label) {
        ShapeBuilder sb = new ShapeBuilder(p);
        sb.addPinDigital(ShapeBuilder.IN + "in", p);
        if (label != null)
            sb.addText(label, 0, 0);
        VisualElement ve = ShapeBuilder.createElement(Tunnel.DESCRIPTION.getName(), p);
        ve.getElementAttributes()
                .set(Keys.NETNAME, name)
                .set(Keys.CUSTOM_SHAPE, sb.build());
        circuit.add(ve);
    }

    private Vector freePos() {
        int cols = Math.max(4, (ShapeBuilder.scale(maxX) - ShapeBuilder.scale(minX)) / HIDDEN_STEP - 2);
        while (true) {
            int i = hiddenCounter++;
            int x = ShapeBuilder.scale(minX) + 7 + (i % cols) * HIDDEN_STEP;
            int y = ShapeBuilder.scale(minY) + 7 + (i / cols) * HIDDEN_STEP;
            Vector v = new Vector(x, y);
            if (!usedPositions.contains(v)) {
                usedPositions.add(v);
                return v;
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // connect the signals
    // ------------------------------------------------------------------------------------

    private void connectSignals() {
        HashMap<String, BitRef> atoms = new HashMap<>();
        for (Signal s : signals)
            if (s.driven)
                for (BusName l : s.labels)
                    if (l.getBits() == s.bits) {
                        List<String> a = l.getAtoms();
                        for (int i = 0; i < a.size(); i++)
                            atoms.putIfAbsent(a.get(i), new BitRef(s, s.bits - 1 - i));
                    }
        atoms.put(BusName.VCC, new BitRef(1));
        atoms.put(BusName.GND, new BitRef(0));

        for (Signal s : new ArrayList<>(signals))
            if (!s.driven && s.hasSinks)
                feedSignal(s, atoms);

        for (Signal s : signals) {
            boolean multi = s.nets.size() > 1 || s.needsTunnel;
            for (Net n : s.nets) {
                boolean hasTunnel = false;
                for (BdfFile.Connector c : n.connectors)
                    if (c.getLabelText() != null) {
                        addTunnel(ShapeBuilder.scale(c.p1), s.tunnelName, c.label);
                        hasTunnel = true;
                    }
                if (!hasTunnel && multi && n.anchor != null)
                    addTunnel(ShapeBuilder.scale(n.anchor), s.tunnelName, null);
            }
        }
    }

    private void feedSignal(Signal s, HashMap<String, BitRef> atoms) {
        BusName p = s.primary();
        BitRef[] bits = new BitRef[s.bits];
        int resolved = 0;
        if (p != null && p.getBits() == s.bits) {
            List<String> a = p.getAtoms();
            for (int j = 0; j < s.bits; j++) {
                BitRef r = atoms.get(a.get(s.bits - 1 - j));
                if (r != null && r.signal != s) {
                    bits[j] = r;
                    resolved++;
                }
            }
        }
        if (resolved < s.bits) {
            if (resolved == 0 || p == null)
                warn("net " + (p == null ? describe(s) : "'" + p.getName() + "'") + " has no source, it is connected to GND");
            else
                warn("some bits of net '" + p.getName() + "' have no source, they are connected to GND");
            for (int j = 0; j < s.bits; j++)
                if (bits[j] == null)
                    bits[j] = new BitRef(0);
        }

        // collect runs of consecutive bits from the same source
        ArrayList<int[]> runs = new ArrayList<>(); // start, length
        int start = 0;
        for (int j = 1; j <= s.bits; j++) {
            if (j == s.bits || !continues(bits[j - 1], bits[j])) {
                runs.add(new int[]{start, j - start});
                start = j;
            }
        }

        s.needsTunnel = true;
        if (runs.size() == 1 && bits[0].signal == null) {
            // only a constant
            long v = 0;
            for (int j = 0; j < s.bits; j++)
                v |= (bits[j].constValue & 1) << j;
            Vector pos = freePos();
            addHiddenConst(pos, s.bits, v);
            addTunnel(pos, s.tunnelName, null);
            return;
        }

        // one slicing splitter for every source signal
        LinkedHashMap<Signal, List<int[]>> bySource = new LinkedHashMap<>();
        for (int[] r : runs) {
            BitRef b = bits[r[0]];
            if (b.signal != null)
                bySource.computeIfAbsent(b.signal, k -> new ArrayList<>()).add(r);
        }
        HashMap<int[], String> runTunnel = new HashMap<>();
        for (Map.Entry<Signal, List<int[]>> en : bySource.entrySet()) {
            Signal src = en.getKey();
            src.needsTunnel = true;
            StringBuilder outSpec = new StringBuilder();
            ArrayList<String> outNames = new ArrayList<>();
            for (int[] r : en.getValue()) {
                int lo = bits[r[0]].bit;
                int hi = lo + r[1] - 1;
                String n = splitterPin(lo, r[1]);
                if (outSpec.length() > 0)
                    outSpec.append(',');
                outSpec.append(lo).append('-').append(hi);
                outNames.add(n);
                if (runs.size() == 1)
                    runTunnel.put(r, s.tunnelName);
                else
                    runTunnel.put(r, "$" + unitName + "_t" + (tmpCounter++));
            }
            ArrayList<String> keys = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            keys.add(ShapeBuilder.IN + splitterPin(0, src.bits));
            names.add(src.tunnelName);
            for (int i = 0; i < outNames.size(); i++) {
                keys.add(ShapeBuilder.OUT + outNames.get(i));
                names.add(runTunnel.get(en.getValue().get(i)));
            }
            addHiddenSplitter(Integer.toString(src.bits), outSpec.toString(), keys, names);
        }
        if (runs.size() == 1)
            return;

        // merge the runs
        StringBuilder inSpec = new StringBuilder();
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        int pos = 0;
        for (int[] r : runs) {
            if (inSpec.length() > 0)
                inSpec.append(',');
            inSpec.append(r[1]);
            keys.add(ShapeBuilder.IN + splitterPin(pos, r[1]));
            pos += r[1];
            String tn = runTunnel.get(r);
            if (tn == null) {
                // constant run
                tn = "$" + unitName + "_t" + (tmpCounter++);
                long v = 0;
                for (int j = 0; j < r[1]; j++)
                    v |= (bits[r[0] + j].constValue & 1) << j;
                Vector cp = freePos();
                addHiddenConst(cp, r[1], v);
                addTunnel(cp, tn, null);
            }
            names.add(tn);
        }
        keys.add(ShapeBuilder.OUT + splitterPin(0, s.bits));
        names.add(s.tunnelName);
        addHiddenSplitter(inSpec.toString(), Integer.toString(s.bits), keys, names);
    }

    /*
     * the name of a splitter pin as created by the splitter
     */
    private static String splitterPin(int pos, int bits) {
        if (bits == 1)
            return Integer.toString(pos);
        if (bits == 2)
            return pos + "," + (pos + 1);
        return pos + "-" + (pos + bits - 1);
    }

    private static boolean continues(BitRef a, BitRef b) {
        if (a.signal == null || b.signal == null)
            return a.signal == null && b.signal == null;
        return a.signal == b.signal && b.bit == a.bit + 1;
    }

    private String describe(Signal s) {
        for (Net n : s.nets)
            for (Terminal t : n.terms)
                return "at " + t.name;
        return "";
    }

    private void addHiddenSplitter(String in, String out, List<String> keys, List<String> tunnelNames) {
        ArrayList<Vector> pos = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++)
            pos.add(freePos());
        ShapeBuilder sb = new ShapeBuilder(pos.get(0));
        for (int i = 0; i < keys.size(); i++)
            sb.addPinDigital(keys.get(i), pos.get(i));
        VisualElement ve = ShapeBuilder.createElement(Splitter.DESCRIPTION.getName(), pos.get(0));
        ve.getElementAttributes()
                .set(Keys.INPUT_SPLIT, in)
                .set(Keys.OUTPUT_SPLIT, out)
                .set(Keys.CUSTOM_SHAPE, sb.build());
        circuit.add(ve);
        for (int i = 0; i < keys.size(); i++)
            addTunnel(pos.get(i), tunnelNames.get(i), null);
    }

    // ------------------------------------------------------------------------------------
    // free graphics
    // ------------------------------------------------------------------------------------

    private void createFreeGraphics() {
        for (BdfFile.Graphic g : bdf.getGraphics()) {
            int x;
            int y;
            if (g.p1 != null) {
                x = g.p1.x;
                y = g.p1.y;
            } else if (g.rect != null) {
                x = g.rect.x1;
                y = g.rect.y1;
            } else
                continue;
            Vector pos = ShapeBuilder.scale(new BdfFile.Point(x, y));
            ShapeBuilder sb = new ShapeBuilder(pos);
            sb.add(g, 0, 0);
            if (sb.isEmpty())
                continue;
            VisualElement ve = ShapeBuilder.createElement(DummyElement.TEXTDESCRIPTION.getName(), pos);
            ve.getElementAttributes().set(Keys.CUSTOM_SHAPE, sb.build());
            if (g.text != null)
                ve.getElementAttributes().set(Keys.DESCRIPTION, g.text.text);
            circuit.add(ve);
        }
    }
}
