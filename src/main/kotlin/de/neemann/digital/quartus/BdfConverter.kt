/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.basic.And
import de.neemann.digital.core.basic.NAnd
import de.neemann.digital.core.basic.NOr
import de.neemann.digital.core.basic.Not
import de.neemann.digital.core.basic.Or
import de.neemann.digital.core.basic.XNOr
import de.neemann.digital.core.basic.XOr
import de.neemann.digital.core.element.ElementAttributes
import de.neemann.digital.core.element.Keys
import de.neemann.digital.core.io.Const
import de.neemann.digital.core.io.In
import de.neemann.digital.core.io.Out
import de.neemann.digital.core.wiring.Clock
import de.neemann.digital.core.wiring.Driver
import de.neemann.digital.core.wiring.Splitter
import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.draw.elements.Tunnel
import de.neemann.digital.draw.elements.Wire
import de.neemann.digital.draw.graphics.Vector
import de.neemann.digital.gui.components.data.DummyElement
import de.neemann.digital.quartus.BdfFile.Point
import de.neemann.digital.quartus.lib.Params
import de.neemann.digital.quartus.lib.QuartusPart
import java.io.File

/**
 * Converts a single block design file to a Digital circuit.
 * The connectivity is computed in Quartus coordinates: wires are connected at their end points
 * and at points lying on other wires, nets with the same name are connected, and parts of
 * busses are connected by name ("a[3]" is a part of "a[7..0]").
 * Connections by name are realized with (mostly invisible) tunnels and splitters.
 */
internal class BdfConverter(
    private val importer: QuartusImporter,
    private val bdf: BdfFile,
    private val unitName: String,
    private val top: Boolean,
    private val ifc: DesignInterface,
    private val sourceDir: File?
) {
    private val circuit = Circuit()
    private val terminals = ArrayList<Terminal>()
    private val elems = ArrayList<Elem>()
    private val nets = ArrayList<Net>()
    private val signals = ArrayList<Signal>()
    private val pieces = ArrayList<Piece>()
    private val usedPositions = HashSet<Vector>()
    private val uf = UnionFind()
    private var minX = Int.MAX_VALUE
    private var minY = Int.MAX_VALUE
    private var maxX = Int.MIN_VALUE
    private var hiddenCounter = 0
    private var tmpCounter = 0

    /** a connection point of a pin or a symbol port */
    private class Terminal(
        val p: Point, val owner: Elem, val pinKey: String, val driver: Boolean, var bits: Int, val name: String
    ) {
        var unconnectedDefault = 0L
        var clockInput = false
        lateinit var net: Net
    }

    /** an element to create */
    private class Elem(val elementName: String, val attr: ElementAttributes) {
        val terms = ArrayList<Terminal>()
        var symbol: BdfFile.Symbol? = null
        var pin: BdfFile.Pin? = null
        var part: QuartusPart? = null
        var params: Params? = null
        var constant = false
        var constHigh = false
        var bitwise = false
        var buffer = false
    }

    /** a wire segment between two connection points */
    private class Piece(val p1: Point, val p2: Point, val bus: Boolean)

    /** a set of connected wires and terminals */
    private class Net {
        val terms = ArrayList<Terminal>()
        val connectors = ArrayList<BdfFile.Connector>()
        val labels = ArrayList<BusName>()
        val pieces = ArrayList<Piece>()
        var anchor: Point? = null
        lateinit var signal: Signal
    }

    /** a set of nets with the same name */
    private class Signal {
        val nets = ArrayList<Net>()
        val labels = ArrayList<BusName>()
        var tunnelName = ""
        var bits = 1
        var needsTunnel = false
        var driven = false
        var hasSinks = false
        var decoration = false
        val primary: BusName? get() = labels.firstOrNull()
    }

    /** a reference to a bit of a signal or a constant */
    private class BitRef(val signal: Signal?, val bit: Int, val constValue: Long = -1)

    /** union find on points */
    private class UnionFind {
        private val ids = HashMap<Point, Int>()
        private val parent = ArrayList<Int>()

        fun id(p: Point): Int = ids.getOrPut(p) {
            parent.add(parent.size)
            parent.size - 1
        }

        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        fun root(p: Point) = find(id(p))
    }

    /** creates the Digital circuit */
    fun convert(): Circuit {
        collectPins()
        collectSymbols()
        buildNets()
        buildSignals()
        resolveWidths()
        registerUsedPositions()
        elems.forEach { if (it.pin != null) createPinElement(it) else createSymbolElement(it) }
        connectSignals()
        createFreeGraphics()
        createWires()
        return circuit
    }

    private fun warn(msg: String) = importer.warn("$unitName: $msg")

    // ------------------------------------------------------------------------------------
    // collect elements
    // ------------------------------------------------------------------------------------

    private fun updateBounds(r: BdfFile.Rect) {
        minX = minOf(minX, r.x1, r.x2)
        minY = minOf(minY, r.y1, r.y2)
        maxX = maxOf(maxX, r.x1, r.x2)
    }

    private fun collectPins() {
        for (p in bdf.pins) {
            updateBounds(p.rect)
            val name = p.getName()
            val bn = BusName.parse(name)
            val label = ifc.find(name)?.label ?: DesignInterface.sanitize(name)
            val input = p.direction != BdfFile.Direction.OUTPUT
            if (p.direction == BdfFile.Direction.BIDIR) warn("bidirectional pin '$name' is treated as an input")
            val attr = ElementAttributes().set(Keys.LABEL, label).setBits(bn.bits)
            if (top) pinNumber(name, bn)?.let { attr.set(Keys.PINNUMBER, it) }
            val e = Elem(if (input) In.DESCRIPTION.name else Out.DESCRIPTION.name, attr)
            e.pin = p
            val t = Terminal(p.getAbsolutePos(), e, if (input) ShapeBuilder.OUT + "out" else ShapeBuilder.IN + "in",
                input, bn.bits, "pin $name")
            e.terms.add(t)
            terminals.add(t)
            elems.add(e)
        }
    }

    /* the pin number of a pin, for busses the pin numbers of the bits are joined (lsb first) */
    private fun pinNumber(name: String, bn: BusName): String? {
        val project = importer.project
        project.getPinLocation(name)?.let { return it }
        if (bn.bits == 1) return null
        val locs = bn.atoms.reversed().map { project.getPinLocation(it) ?: return null }
        return locs.joinToString(",")
    }

    private fun collectSymbols() {
        for (s in bdf.symbols) {
            updateBounds(s.rect)
            var m = QuartusLibrary.map(s.getTypeName())
            if (m != null && m.part != null && s.ports.any { m!!.getPinKey(it.getName()) == null }) {
                warn("symbol '${s.getTypeName()}' (${s.getInstanceName()}) has unknown ports, it is imported as a sub design")
                m = null
            }
            if (m != null) collectPrimitive(s, m) else collectSubDesign(s)
        }
    }

    private fun collectPrimitive(s: BdfFile.Symbol, m: QuartusLibrary.Mapping) {
        val attr = ElementAttributes(m.attributes)
        val e = Elem(m.element, attr)
        e.symbol = s
        e.part = m.part
        e.constant = m.isConstant
        e.constHigh = e.constant && attr.get(Keys.VALUE) != 0L
        e.bitwise = m.element in BITWISE
        e.buffer = m.element == Splitter.DESCRIPTION.name
        e.part?.let { part ->
            val p = Params(part.defaultParams)
            for ((k, v) in s.parameters) p.set(k, v)
            fixFileParams(p)
            e.params = p
            if (s.getInstanceName().isNotEmpty()) attr.set(Keys.LABEL, s.getInstanceName())
        }
        for (port in s.ports) {
            val pos = s.getAbsolutePos(port)
            if (e.constant) {
                add(e, Terminal(pos, e, ShapeBuilder.OUT + "out", true, -1, type(s)))
                continue
            }
            val key = m.getPinKey(port.getName())
            if (key == null) {
                warn("unknown port '${port.getName()}' at ${type(s)}")
                continue
            }
            val out = key.startsWith(ShapeBuilder.OUT)
            val part = e.part
            if (part != null) {
                val pn = key.substring(2)
                val spec = (if (out) part.getOutput(pn) else part.getInput(pn))!!
                val t = Terminal(pos, e, key, out, spec.getWidth(e.params!!), "${type(s)}.${port.getName()}")
                t.clockInput = spec.isClock
                t.unconnectedDefault = spec.default
                add(e, t)
            } else
                add(e, Terminal(pos, e, key, out, -1, "${type(s)}.${port.getName()}"))
        }
        elems.add(e)
    }

    /* memory initialization files are searched relative to the source file */
    private fun fixFileParams(p: Params) {
        for (k in listOf("LPM_FILE", "INIT_FILE")) {
            val f = p.get(k, "")
            if (f.isEmpty() || f.equals("UNUSED", true)) continue
            importer.resolveDataFile(f, sourceDir)?.let { p.set(k, it.absolutePath) }
        }
    }

    private fun add(e: Elem, t: Terminal) {
        e.terms.add(t)
        terminals.add(t)
    }

    private fun collectSubDesign(s: BdfFile.Symbol) {
        val sub = importer.getInterface(s.getTypeName(), s)
        val attr = ElementAttributes()
        if (s.getInstanceName().isNotEmpty()) attr.set(Keys.LABEL, s.getInstanceName())
        val e = Elem(importer.getElementName(sub), attr)
        e.symbol = s
        for (port in s.ports) {
            val ip = sub.find(port.getName())
            if (ip == null) {
                warn("port '${port.getName()}' not found in design '${s.getTypeName()}'")
                continue
            }
            val out = ip.isOutput
            val t = Terminal(s.getAbsolutePos(port), e, (if (out) ShapeBuilder.OUT else ShapeBuilder.IN) + ip.label,
                out, ip.bits, "${type(s)}.${port.getName()}")
            t.unconnectedDefault = if (ip.isDefaultHigh) -1 else 0
            t.clockInput = !out && CLOCK_NAME.matches(ip.quartusName)
            add(e, t)
        }
        elems.add(e)
    }

    // ------------------------------------------------------------------------------------
    // build nets
    // ------------------------------------------------------------------------------------

    private fun buildNets() {
        val special = HashSet<Point>()
        for (c in bdf.connectors) {
            special.add(c.p1)
            special.add(c.p2)
        }
        terminals.forEach { special.add(it.p) }
        special.addAll(bdf.junctions)
        special.forEach { uf.id(it) }

        // index the points by row and column to find the points on a wire quickly
        val byRow = special.groupBy { it.y }
        val byCol = special.groupBy { it.x }
        for (c in bdf.connectors) {
            if (c.p1 == c.p2) continue
            val candidates = when {
                c.p1.y == c.p2.y -> byRow[c.p1.y] ?: emptyList()
                c.p1.x == c.p2.x -> byCol[c.p1.x] ?: emptyList()
                else -> special
            }
            val on = candidates.filter { onSegment(c.p1, c.p2, it) }.sortedBy { dist(c.p1, it) }
            for (i in 1 until on.size) {
                uf.union(uf.id(on[i - 1]), uf.id(on[i]))
                pieces.add(Piece(on[i - 1], on[i], c.bus))
            }
        }

        val netMap = HashMap<Int, Net>()
        for (t in terminals) {
            val n = netMap.getOrPut(uf.root(t.p)) { Net() }
            n.terms.add(t)
            t.net = n
            if (n.anchor == null) n.anchor = t.p
        }
        for (c in bdf.connectors) {
            val n = netMap.getOrPut(uf.root(c.p1)) { Net() }
            if (n.anchor == null || n.connectors.isEmpty()) n.anchor = c.p1
            n.connectors.add(c)
            c.getLabelText()?.let { n.labels.add(BusName.parse(it)) }
        }
        for (t in terminals) {
            val pinName = t.owner.pin?.getName()
            if (!pinName.isNullOrEmpty()) t.net.labels.add(BusName.parse(pinName))
        }
        for (pc in pieces) netMap[uf.root(pc.p1)]!!.pieces.add(pc)
        nets.addAll(netMap.values)
    }

    private fun buildSignals() {
        val byKey = HashMap<String, Signal>()
        for (n in nets) {
            var s: Signal? = null
            for (l in n.labels) {
                val o = byKey[l.key]
                if (o != null && o !== s) s = if (s == null) o else merge(s, o, byKey)
            }
            if (s == null) {
                s = Signal()
                signals.add(s)
            }
            s.nets.add(n)
            n.signal = s
            for (l in n.labels)
                if (!byKey.containsKey(l.key)) {
                    s.labels.add(l)
                    byKey[l.key] = s
                }
        }
    }

    private fun merge(a: Signal, b: Signal, byKey: HashMap<String, Signal>): Signal {
        for (n in b.nets) {
            a.nets.add(n)
            n.signal = a
        }
        for (l in b.labels) {
            a.labels.add(l)
            byKey[l.key] = a
        }
        signals.remove(b)
        return a
    }

    private fun resolveWidths() {
        var n = 0
        for (s in signals) {
            val p = s.primary
            var bits = if (p != null && !p.isConstant) p.bits else -1
            for (t in s.nets.flatMap { it.terms }) {
                if (t.bits > 0 && bits < 0) bits = t.bits
                if (t.driver) s.driven = true
                else if (!isIsolated(t)) s.hasSinks = true
            }
            if (bits < 0) bits = 1
            s.bits = bits
            s.tunnelName = p?.name ?: "\$${unitName}_n${n++}"
            if (p == null && isConstantOnly(s) && hasMixedWidths(s)) {
                // e.g. VCC connected to busses of different widths: every input gets its own constant
                s.decoration = true
                val v = if (s.nets.flatMap { it.terms }.any { it.driver && it.owner.constHigh }) -1L else 0L
                s.nets.flatMap { it.terms }.forEach { it.unconnectedDefault = v }
                continue
            }
            for (t in s.nets.flatMap { it.terms }) {
                if (t.bits < 0) t.bits = bits
                else if (t.bits != bits) warn("width mismatch at ${t.name}: ${t.bits} bits connected to a net with $bits bits")
            }
        }
    }

    private fun isConstantOnly(s: Signal): Boolean {
        val drivers = s.nets.flatMap { it.terms }.filter { it.driver }
        return drivers.isNotEmpty() && drivers.all { it.owner.constant }
    }

    private fun hasMixedWidths(s: Signal) =
        s.nets.flatMap { it.terms }.filter { !it.driver && it.bits > 0 }.map { it.bits }.distinct().size > 1

    // ------------------------------------------------------------------------------------
    // create the circuit
    // ------------------------------------------------------------------------------------

    private fun registerUsedPositions() {
        for (pc in pieces) {
            usedPositions.add(ShapeBuilder.scale(pc.p1))
            usedPositions.add(ShapeBuilder.scale(pc.p2))
        }
        terminals.forEach { usedPositions.add(ShapeBuilder.scale(it.p)) }
    }

    private fun createWires() {
        val junctions = HashSet(bdf.junctions)
        for (n in nets) {
            if (n.signal.decoration) {
                // the net can not be represented by a Digital net, so the wires are only drawn
                if (n.pieces.isEmpty()) continue
                val pos = ShapeBuilder.scale(n.pieces[0].p1)
                val sb = ShapeBuilder(pos)
                for (pc in n.pieces) {
                    sb.line(pc.p1.x, pc.p1.y, pc.p2.x, pc.p2.y, pc.bus)
                    for (p in listOf(pc.p1, pc.p2))
                        if (junctions.remove(p)) sb.polygon(p.x - 2, p.y - 2, p.x + 2, p.y - 2, p.x + 2, p.y + 2, p.x - 2, p.y + 2)
                }
                addDecoration(pos, sb, null)
            } else {
                for (pc in n.pieces) {
                    val v1 = ShapeBuilder.scale(pc.p1)
                    val v2 = ShapeBuilder.scale(pc.p2)
                    if (v1 != v2) circuit.wires.add(Wire(v1, v2))
                }
            }
        }
    }

    private fun addDecoration(pos: Vector, sb: ShapeBuilder, description: String?) {
        val ve = ShapeBuilder.createElement(DummyElement.TEXTDESCRIPTION.name, pos)
        ve.elementAttributes.set(Keys.CUSTOM_SHAPE, sb.build())
        if (description != null) ve.elementAttributes.set(Keys.DESCRIPTION, description)
        circuit.add(ve)
    }

    private fun createPinElement(e: Elem) {
        val p = e.pin!!
        val pos = ShapeBuilder.scale(Point(p.rect.x1, p.rect.y1))
        val sb = ShapeBuilder(pos)
            .add(p.drawing, p.rect.x1, p.rect.y1)
            .addText(p.typeText, p.rect.x1, p.rect.y1)
            .addText(p.name, p.rect.x1, p.rect.y1)
            .addText(p.defaultValue, p.rect.x1, p.rect.y1)
        val t = e.terms[0]
        var elementName = e.elementName
        var key = t.pinKey
        if (top && t.driver && t.bits == 1 && isClock(t)) {
            elementName = Clock.DESCRIPTION.name
            e.attr.set(Keys.FREQUENCY, 1)
            key = ShapeBuilder.OUT + "C"
        }
        sb.addPin(key, t.p.x, t.p.y)
        val vp = valuePos(p)
        sb.addPin(ShapeBuilder.VALUE_POS, vp.x + p.rect.x1, vp.y + p.rect.y1)
        e.attr.set(Keys.CUSTOM_SHAPE, sb.build())
        circuit.add(ShapeBuilder.createElement(elementName, pos, e.attr))
    }

    private fun isClock(pinTerm: Terminal): Boolean {
        if (!importer.convertClocks) return false
        if (CLOCK_NAME.matches(pinTerm.owner.pin!!.getName())) return true
        return pinTerm.net.signal.nets.flatMap { it.terms }.any { it.clockInput && it.owner.part != null }
    }

    /* the position used to show the value: the center of the pin drawing without the connection line */
    private fun valuePos(p: BdfFile.Pin): Point {
        val lines = p.drawing.filter { it.type == BdfFile.GraphicType.LINE && it.p1 != p.pt && it.p2 != p.pt }
        if (lines.isEmpty()) return p.pt
        val xs = lines.flatMap { listOf(it.p1!!.x, it.p2!!.x) }
        val ys = lines.flatMap { listOf(it.p1!!.y, it.p2!!.y) }
        return Point((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2)
    }

    private fun createSymbolElement(e: Elem) {
        val s = e.symbol!!
        val pos = ShapeBuilder.scale(Point(s.rect.x1, s.rect.y1))
        val sb = ShapeBuilder(pos).add(s.drawing, s.rect.x1, s.rect.y1)
        if (s.type != null && !s.type.invisible) sb.addText(s.type, s.rect.x1, s.rect.y1)
        sb.addText(s.instance, s.rect.x1, s.rect.y1)
        for (port in s.ports) {
            port.line?.let { sb.add(it, s.rect.x1, s.rect.y1) }
            if (port.label != null && !port.label.invisible) sb.addText(port.label, s.rect.x1, s.rect.y1)
        }

        val attr = e.attr
        val width = maxOf(1, e.terms.maxOfOrNull { it.bits } ?: 1)

        if (e.constant && e.terms.isNotEmpty() && e.terms[0].net.signal.decoration) {
            addDecoration(pos, sb, null)
            return
        }
        when {
            e.constant -> {
                attr.setBits(width)
                attr.set(Keys.VALUE, if (e.constHigh) (if (width >= 64) -1L else (1L shl width) - 1) else 0L)
            }
            e.bitwise && width > 1 -> attr.setBits(width)
            e.buffer -> attr.set(Keys.INPUT_SPLIT, width.toString()).set(Keys.OUTPUT_SPLIT, width.toString())
        }

        val part = e.part
        if (part != null) {
            val used = e.terms.filter { it.driver || isConnected(it) || it.net.signal.decoration }.map { it.pinKey.substring(2) }
            e.params!!.set(Params.PORTS, used.joinToString(","))
            attr.set(Keys.QUARTUS_PARAMS, e.params.toString())
        }

        for (t in e.terms) {
            var key = t.pinKey
            if (e.buffer && width > 1) key = key.substring(0, 2) + splitterPin(0, width)
            if (part != null && !t.driver && !isConnected(t) && !t.net.signal.decoration) continue
            sb.addPin(key, t.p.x, t.p.y)
            if (!t.driver && !isConnected(t)) addDefault(t)
        }
        attr.set(Keys.CUSTOM_SHAPE, sb.build())
        circuit.add(ShapeBuilder.createElement(e.elementName, pos, attr))
    }

    /* a terminal is connected if its net contains something else */
    private fun isConnected(t: Terminal) = !t.net.signal.decoration && !isIsolated(t)

    private fun isIsolated(t: Terminal): Boolean {
        val n = t.net
        return n.terms.size <= 1 && n.connectors.isEmpty() && n.signal.nets.size <= 1 && n.labels.isEmpty()
    }

    private fun addDefault(t: Terminal) {
        var v = t.unconnectedDefault
        if (t.bits < 64 && v != 0L) v = v and ((1L shl t.bits) - 1)
        addHiddenConst(ShapeBuilder.scale(t.p), t.bits, v)
    }

    private fun addHiddenConst(p: Vector, bits: Int, value: Long) {
        val ve = ShapeBuilder.createElement(Const.DESCRIPTION.name, p)
        ve.elementAttributes.setBits(bits).set(Keys.VALUE, value)
            .set(Keys.CUSTOM_SHAPE, ShapeBuilder(p).addPinDigital(ShapeBuilder.OUT + "out", p).build())
        circuit.add(ve)
    }

    private fun addTunnel(p: Vector, name: String, label: BdfFile.Text?) {
        val sb = ShapeBuilder(p).addPinDigital(ShapeBuilder.IN + "in", p)
        if (label != null) sb.addText(label, 0, 0)
        val ve = ShapeBuilder.createElement(Tunnel.DESCRIPTION.name, p)
        ve.elementAttributes.set(Keys.NETNAME, name).set(Keys.CUSTOM_SHAPE, sb.build())
        circuit.add(ve)
    }

    private fun freePos(): Vector {
        val x0 = ShapeBuilder.scale(minX)
        val y0 = ShapeBuilder.scale(minY)
        val cols = maxOf(4, (ShapeBuilder.scale(maxX) - x0) / HIDDEN_STEP - 2)
        while (true) {
            val i = hiddenCounter++
            val v = Vector(x0 + 7 + (i % cols) * HIDDEN_STEP, y0 + 7 + (i / cols) * HIDDEN_STEP)
            if (usedPositions.add(v)) return v
        }
    }

    // ------------------------------------------------------------------------------------
    // connect the signals
    // ------------------------------------------------------------------------------------

    private fun connectSignals() {
        val atoms = HashMap<String, BitRef>()
        for (s in signals.filter { it.driven })
            for (l in s.labels.filter { it.bits == s.bits })
                l.atoms.forEachIndexed { i, a -> atoms.putIfAbsent(a, BitRef(s, s.bits - 1 - i)) }
        atoms[BusName.VCC] = BitRef(null, 0, 1)
        atoms[BusName.GND] = BitRef(null, 0, 0)

        for (s in ArrayList(signals)) if (!s.driven && s.hasSinks) feedSignal(s, atoms)

        for (s in signals) {
            // a labeled wire connected to nothing
            if (!s.driven && !s.hasSinks && !s.needsTunnel) continue
            val multi = s.nets.size > 1 || s.needsTunnel
            for (n in s.nets) {
                var hasTunnel = false
                for (c in n.connectors.filter { it.getLabelText() != null }) {
                    addTunnel(ShapeBuilder.scale(c.p1), s.tunnelName, c.label)
                    hasTunnel = true
                }
                val anchor = n.anchor
                if (!hasTunnel && multi && anchor != null) addTunnel(ShapeBuilder.scale(anchor), s.tunnelName, null)
            }
        }
    }

    private fun feedSignal(s: Signal, atoms: Map<String, BitRef>) {
        val p = s.primary
        val bits = arrayOfNulls<BitRef>(s.bits)
        var resolved = 0
        if (p != null && p.bits == s.bits) {
            for (j in 0 until s.bits) {
                val r = atoms[p.atoms[s.bits - 1 - j]]
                if (r != null && r.signal !== s) {
                    bits[j] = r
                    resolved++
                }
            }
        }
        if (resolved < s.bits) {
            if (resolved == 0 || p == null)
                warn("net " + (if (p == null) describe(s) else "'${p.name}'") + " has no source, it is connected to GND")
            else warn("some bits of net '${p.name}' have no source, they are connected to GND")
            for (j in bits.indices) if (bits[j] == null) bits[j] = BitRef(null, 0, 0)
        }
        val b = bits.map { it!! }

        // runs of consecutive bits from the same source: start and length
        val runs = ArrayList<IntArray>()
        var start = 0
        for (j in 1..s.bits) {
            if (j == s.bits || !continues(b[j - 1], b[j])) {
                runs.add(intArrayOf(start, j - start))
                start = j
            }
        }

        s.needsTunnel = true
        if (runs.size == 1 && b[0].signal == null) {
            // only a constant
            val pos = freePos()
            addHiddenConst(pos, s.bits, constant(b, 0, s.bits))
            addTunnel(pos, s.tunnelName, null)
            return
        }

        // one slicing splitter for every source signal
        val runTunnel = HashMap<IntArray, String>()
        val bySource = LinkedHashMap<Signal, MutableList<IntArray>>()
        for (r in runs) b[r[0]].signal?.let { bySource.getOrPut(it) { ArrayList() }.add(r) }
        for ((src, srcRuns) in bySource) {
            src.needsTunnel = true
            val keys = arrayListOf(ShapeBuilder.IN + splitterPin(0, src.bits))
            val names = arrayListOf(src.tunnelName)
            // a range of the source used several times (e.g. a sign extension) is sliced only once
            val slices = LinkedHashMap<Pair<Int, Int>, String>()
            for (r in srcRuns) {
                val lo = b[r[0]].bit
                runTunnel[r] = slices.getOrPut(lo to r[1]) {
                    val tn = if (runs.size == 1) s.tunnelName else "\$${unitName}_t${tmpCounter++}"
                    keys.add(ShapeBuilder.OUT + splitterPin(lo, r[1]))
                    names.add(tn)
                    tn
                }
            }
            val outSpec = slices.keys.joinToString(",") { (lo, len) -> "$lo-${lo + len - 1}" }
            addHiddenSplitter(src.bits.toString(), outSpec, keys, names)
        }
        if (runs.size == 1) return

        // merge the runs
        val keys = ArrayList<String>()
        val names = ArrayList<String>()
        var pos = 0
        for (r in runs) {
            keys.add(ShapeBuilder.IN + splitterPin(pos, r[1]))
            pos += r[1]
            names.add(runTunnel[r] ?: run {
                // constant run
                val tn = "\$${unitName}_t${tmpCounter++}"
                val cp = freePos()
                addHiddenConst(cp, r[1], constant(b, r[0], r[1]))
                addTunnel(cp, tn, null)
                tn
            })
        }
        keys.add(ShapeBuilder.OUT + splitterPin(0, s.bits))
        names.add(s.tunnelName)
        addHiddenSplitter(runs.joinToString(",") { it[1].toString() }, s.bits.toString(), keys, names)
    }

    private fun constant(b: List<BitRef>, from: Int, n: Int): Long {
        var v = 0L
        for (j in 0 until n) v = v or ((b[from + j].constValue and 1) shl j)
        return v
    }

    private fun describe(s: Signal) = s.nets.flatMap { it.terms }.firstOrNull()?.let { "at ${it.name}" } ?: ""

    private fun addHiddenSplitter(input: String, output: String, keys: List<String>, tunnelNames: List<String>) {
        val pos = keys.map { freePos() }
        val sb = ShapeBuilder(pos[0])
        keys.forEachIndexed { i, k -> sb.addPinDigital(k, pos[i]) }
        val ve = ShapeBuilder.createElement(Splitter.DESCRIPTION.name, pos[0])
        ve.elementAttributes.set(Keys.INPUT_SPLIT, input).set(Keys.OUTPUT_SPLIT, output).set(Keys.CUSTOM_SHAPE, sb.build())
        circuit.add(ve)
        keys.indices.forEach { addTunnel(pos[it], tunnelNames[it], null) }
    }

    // ------------------------------------------------------------------------------------
    // free graphics
    // ------------------------------------------------------------------------------------

    private fun createFreeGraphics() {
        for (g in bdf.graphics) {
            val origin = g.p1 ?: g.rect?.let { Point(it.x1, it.y1) } ?: continue
            val pos = ShapeBuilder.scale(origin)
            val sb = ShapeBuilder(pos).add(g, 0, 0)
            if (!sb.isEmpty) addDecoration(pos, sb, g.text?.text)
        }
    }

    companion object {
        private val CLOCK_NAME = Regex("(?i)(.*(clk|clock|horloge).*|h\\d+.*)")
        private const val HIDDEN_STEP = 13
        private val BITWISE = setOf(And.DESCRIPTION.name, Or.DESCRIPTION.name, NAnd.DESCRIPTION.name, NOr.DESCRIPTION.name,
            XOr.DESCRIPTION.name, XNOr.DESCRIPTION.name, Not.DESCRIPTION.name, Driver.DESCRIPTION.name)

        private fun type(s: BdfFile.Symbol): String =
            if (s.getInstanceName().isEmpty()) s.getTypeName() else "${s.getTypeName()} (${s.getInstanceName()})"

        private fun onSegment(a: Point, b: Point, p: Point): Boolean {
            val cross = (b.x - a.x).toLong() * (p.y - a.y) - (b.y - a.y).toLong() * (p.x - a.x)
            return cross == 0L && p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)
        }

        private fun dist(a: Point, b: Point): Long {
            val dx = (a.x - b.x).toLong()
            val dy = (a.y - b.y).toLong()
            return dx * dx + dy * dy
        }

        /* the name of a splitter pin as created by the splitter */
        internal fun splitterPin(pos: Int, bits: Int) = when (bits) {
            1 -> pos.toString()
            2 -> "$pos,${pos + 1}"
            else -> "$pos-${pos + bits - 1}"
        }

        private fun continues(a: BitRef, b: BitRef): Boolean {
            if (a.signal == null || b.signal == null) return a.signal == null && b.signal == null
            return a.signal === b.signal && b.bit == a.bit + 1
        }
    }
}
