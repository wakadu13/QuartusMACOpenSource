/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.element.Keys
import de.neemann.digital.core.element.PinDescription
import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.draw.elements.Pin
import de.neemann.digital.draw.elements.VisualElement
import de.neemann.digital.draw.graphics.Vector
import de.neemann.digital.draw.library.ElementLibrary
import de.neemann.digital.draw.library.ElementTypeDescriptionCustom
import de.neemann.digital.draw.model.ModelCreator
import de.neemann.digital.draw.model.Net
import de.neemann.digital.draw.model.NetList
import de.neemann.digital.quartus.BdfFile.Direction
import de.neemann.digital.quartus.BdfFile.Point
import de.neemann.digital.quartus.lib.Params
import de.neemann.digital.quartus.lib.QuartusParts
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

/**
 * Exports a Digital circuit to a Quartus block design file (bdf).
 * The Digital components are replaced by the matching Quartus primitives, megafunctions or
 * sub designs. Nets which can not be connected geometrically are connected by name.
 * Embedded circuits are exported to their own block design files.
 *
 * @param library the library used to resolve the embedded circuits
 */
class BdfExporter(private val library: ElementLibrary) {
    /** the warnings */
    val warnings = ArrayList<String>()

    /** the created files */
    val createdFiles = ArrayList<File>()

    private val exported = HashSet<String>()

    /**
     * Exports the circuit.
     *
     * @param circuit the circuit
     * @param file    the bdf file to create
     */
    @Throws(IOException::class)
    fun export(circuit: Circuit, file: File) {
        exported.add(QuartusProject.stem(file.name).lowercase())
        val text = CircuitExport(circuit, QuartusProject.stem(file.name), file.parentFile).create()
        file.writeText(text, Charsets.ISO_8859_1)
        createdFiles.add(file)
    }

    private fun warn(w: String) {
        if (w !in warnings) warnings.add(w)
    }

    /** a Quartus port of a placed symbol connected to a Digital pin */
    private class PortLink(val digitalPin: Pin?, val quartusPort: QSymbol.QPort, val invert: Boolean, val fixedNet: String? = null)

    /** the mapping of a Digital element to a Quartus symbol */
    private class Mapped(
        val symbol: QSymbol, val typeName: String,
        val links: List<PortLink>, val params: Map<String, String> = emptyMap()
    ) {
        /** the position of the symbol */
        var origin: Point? = null

        /** the direction of the labels at the ports, in units of 16 */
        val dirs = IdentityHashMap<QSymbol.QPort, Point>()
    }

    /** a connector, a wire of a net or a stub with a label */
    private class Seg(var a: Point, var b: Point, val id: String, val net: Net?, val label: String?)

    private inner class CircuitExport(private val circuit: Circuit, private val name: String, private val dir: File) {
        private val out = StringBuilder()
        private val pins = IdentityHashMap<VisualElement, List<Pin>>()
        private val netOf = IdentityHashMap<Pin, Net>()
        private val nets = ArrayList<Net>()
        private val netName = IdentityHashMap<Net, String>()
        private val netBits = IdentityHashMap<Net, Int>()
        private val namedConnect = HashSet<Net>() // nets which need a label on their wires
        private val usedNames = HashSet<String>()
        private val pinNames = IdentityHashMap<VisualElement, String>()
        private val decoderBus = IdentityHashMap<VisualElement, String>()
        private var netCounter = 0
        private var instCounter = 0
        private var freeX = 0
        private var freeY = 0
        private var freeRow = 48

        // the geometry of the Quartus schematic: the ports and the connectors with the id of their net
        private val geoPoints = HashMap<Point, MutableSet<String>>()
        private val segs = ArrayList<Seg>()
        private val digitalPins = HashMap<Point, Int>()
        private var uniqueCounter = 0

        private fun idOf(n: Net) = "net:" + System.identityHashCode(n)

        private fun addPoint(p: Point, id: String) {
            geoPoints.getOrPut(p) { HashSet() }.add(id)
        }

        /* the ids of all objects touching the given point */
        private fun idsAt(p: Point): Set<String> {
            val ids = HashSet<String>(geoPoints[p] ?: emptySet())
            for (s in segs) if (onSegment(s.a, s.b, p)) ids.add(s.id)
            return ids
        }

        /* true if a new segment from a to b with the given id touches nothing with an other id */
        private fun segClear(a: Point, b: Point, id: String): Boolean {
            for ((p, ids) in geoPoints) if (onSegment(a, b, p) && ids.any { it != id }) return false
            for (s in segs) if (s.id != id &&
                (onSegment(s.a, s.b, a) || onSegment(s.a, s.b, b) || onSegment(a, b, s.a) || onSegment(a, b, s.b))) return false
            return true
        }

        /* finds a free stub starting at p, the preferred direction is given in units of 16 */
        private fun findStub(p: Point, preferred: Point, id: String, lengths: List<Int>): Point? {
            val dirs = listOf(preferred, Point(-preferred.y, preferred.x), Point(preferred.y, -preferred.x), Point(-preferred.x, -preferred.y))
            for (len in lengths)
                for (d in dirs) {
                    val e = Point(p.x + d.x * len / 16, p.y + d.y * len / 16)
                    if (segClear(p, e, id) && (idsAt(e) - id).isEmpty()) return e
                }
            return null
        }

        private fun addStub(p: Point, preferred: Point, id: String, label: String, lengths: List<Int> = STUB_LENGTHS) {
            val e = findStub(p, preferred, id, lengths)
            if (e == null) {
                warn("$name: no free place for the label '$label'")
                segs.add(Seg(p, Point(p.x + preferred.x, p.y + preferred.y), id, null, label))
                return
            }
            segs.add(Seg(p, e, id, null, label))
        }

        /*
         * Shortens the wire ending at p by 8 units, so that a port can be placed at p which is not connected
         * to the wire. Returns false if this is not possible.
         */
        private fun detach(p: Point, outward: Point, id: String): Boolean {
            if (geoPoints.containsKey(p) || (digitalPins[p] ?: 0) > 1) return false
            val touching = segs.filter { onSegment(it.a, it.b, p) }
            val s = touching.singleOrNull() ?: return false
            if (s.id != id || s.label != null) return false
            val other = if (s.a == p) s.b else if (s.b == p) s.a else return false
            val dx = other.x - p.x
            val dy = other.y - p.y
            if (dx.toLong() * outward.y != dy.toLong() * outward.x || dx * sign(outward.x) + dy * sign(outward.y) < 16) return false
            val np = Point(p.x + sign(outward.x) * 8, p.y + sign(outward.y) * 8)
            if (s.a == p) s.a = np else s.b = np
            return true
        }

        fun create(): String {
            analyze()
            out.append(HEADER)
            var maxY = 0
            for (ve in circuit.elements) maxY = maxOf(maxY, ShapeBuilder.unscale(ve.pos.y))
            for (w in circuit.wires) maxY = maxOf(maxY, ShapeBuilder.unscale(maxOf(w.p1.y, w.p2.y)))
            freeY = (maxY + 120) / 8 * 8
            freeX = 0
            for (n in nets) for (w in n.wires) segs.add(Seg(q(w.p1), q(w.p2), idOf(n), n, null))
            for (ps in pins.values) for (p in ps) digitalPins.merge(q(p.pos), 1, Int::plus)
            mergeCollinear()
            for (ve in circuit.elements) exportElement(ve)
            exportConnectors()
            return out.toString()
        }

        // ---------------------------------------------------------------------------------
        // nets
        // ---------------------------------------------------------------------------------

        private fun analyze() {
            val nl = NetList(circuit)
            for (ve in circuit.elements) {
                val p = try {
                    ve.pins.toList()
                } catch (e: Exception) {
                    emptyList<Pin>()
                }
                pins[ve] = p
                p.forEach { nl.add(it) }
            }
            for (n in nl) {
                nets.add(n)
                n.pins.forEach { netOf[it] = n }
            }
            // the widths are taken from the model
            try {
                ModelCreator(circuit, library).createModel(true)
                for (n in nets) n.wires.firstOrNull()?.value?.let { netBits[n] = it.bits }
            } catch (e: Exception) {
                warn("$name: the bus widths could not be determined: ${e.message}")
            }
            for (ve in circuit.elements) {
                val bits = ve.elementAttributes.get(Keys.BITS)
                for (p in pins[ve]!!) netOf[p]?.let { if (!netBits.containsKey(it)) netBits[it] = bits }
            }
            nameNets()
        }

        private fun bits(n: Net?) = if (n == null) 1 else netBits[n] ?: 1

        private fun freshName(prefix: String): String {
            var b: String
            do {
                b = "$prefix${netCounter++}"
            } while (b in usedNames)
            usedNames.add(b)
            return b
        }

        /* returns a name not used so far */
        private fun uniqueName(base: String): String {
            var b = sanitize(base)
            if (BusName.parse(b).base.lowercase() in usedNames) {
                var k = 1
                while (BusName.parse("${b}_$k").base.lowercase() in usedNames) k++
                warn("$name: the name '$b' is used twice, it is renamed to '${b}_$k'")
                b = "${b}_$k"
            }
            usedNames.add(BusName.parse(b).base.lowercase())
            return b
        }

        /*
         * Names all nets. Splitters and the outputs of decoders do not exist in Quartus, they are replaced by
         * bus names: every bit gets a name and the name of a net is the list of the names of its bits,
         * e.g. "a[3..0],b[3..0]". The names of the pins are used first, then the names of the tunnels.
         */
        private fun nameNets() {
            val slot = IdentityHashMap<Any, Int>()
            var count = 0
            for (n in nets) {
                slot[n] = count
                count += bits(n)
            }
            val decoders = circuit.elements.filter { it.elementName == "Decoder" }
            for (ve in decoders) {
                slot[ve] = count
                count += 1 shl ve.elementAttributes.get(Keys.SELECTOR_BITS)
            }
            val parent = IntArray(count) { it }
            fun root(i: Int): Int {
                var r = i
                while (parent[r] != r) r = parent[r]
                parent[i] = r
                return r
            }
            fun union(a: Int, b: Int) {
                parent[root(a)] = root(b)
            }

            for (ve in circuit.elements.filter { it.elementName == "Splitter" }) {
                val ins = HashMap<Int, Int>()
                val outs = HashMap<Int, Int>()
                for (p in pins[ve]!!) {
                    val n = netOf[p] ?: continue
                    val (lo, hi) = try {
                        range(p.name)
                    } catch (e: NumberFormatException) {
                        continue
                    }
                    val m = if (p.direction == PinDescription.Direction.input) ins else outs
                    for (b in lo..hi) if (b - lo < bits(n)) m[b] = slot[n]!! + b - lo
                }
                for ((b, s) in ins) outs[b]?.let { union(s, it) }
            }
            for (ve in decoders) for (p in pins[ve]!!.filter { it.name.startsWith("out_") }) {
                val n = netOf[p] ?: continue
                union(slot[n]!!, slot[ve]!! + p.name.substring(4).toInt())
            }

            // the names of the bits
            val atom = HashMap<Int, String>()
            fun name(owner: Any, bits: Int, base: String) {
                val bn = BusName.parse(base)
                val s = slot[owner]!!
                for (i in 0 until bits) {
                    val a = if (bits == 1) base else if (base.contains('[') && bn.bits == bits) bn.atoms[bits - 1 - i] else "$base[$i]"
                    atom.putIfAbsent(root(s + i), a)
                }
            }
            for (ve in circuit.elements) {
                if (ve.elementName in INPUTS || ve.elementName in OUTPUTS) {
                    val n = pins[ve]!!.firstOrNull()?.let { netOf[it] } ?: continue
                    val l = ve.elementAttributes.label
                    val pn = if (l.isNullOrBlank()) pinName(ve) else uniqueName(l)
                    pinNames[ve] = pn
                    name(n, bits(n), pn)
                }
            }
            for (n in nets) n.labels.firstOrNull()?.let { l ->
                val lbl = sanitize(l)
                val base = BusName.parse(lbl).base.lowercase()
                val s = slot[n]!!
                // the name is already used by this net if a pin has the same name
                if ((0 until bits(n)).all { atom[root(s + it)]?.let { a -> BusName.parse(a).base.lowercase() == base } == true }) return@let
                val nm = if (base in usedNames) uniqueName(lbl) else lbl.also { usedNames.add(base) }
                name(n, bits(n), nm)
            }
            for (n in nets) {
                val s = slot[n]!!
                if ((0 until bits(n)).any { !atom.containsKey(root(s + it)) }) name(n, bits(n), freshName("net"))
            }
            for (ve in decoders) {
                val b = 1 shl ve.elementAttributes.get(Keys.SELECTOR_BITS)
                val s = slot[ve]!!
                if ((0 until b).any { !atom.containsKey(root(s + it)) }) name(ve, b, freshName("dec"))
                decoderBus[ve] = compose((b - 1 downTo 0).map { atom[root(s + it)]!! })
            }

            // the nets sharing bits with other nets are connected by name
            val owners = HashMap<Int, MutableSet<Any>>()
            for ((o, s) in slot) {
                val b = if (o is Net) bits(o) else 1 shl (o as VisualElement).elementAttributes.get(Keys.SELECTOR_BITS)
                for (i in 0 until b) owners.getOrPut(root(s + i)) { HashSet() }.add(o)
            }
            for (n in nets) {
                val s = slot[n]!!
                netName[n] = compose((bits(n) - 1 downTo 0).map { atom[root(s + it)]!! })
                if ((0 until bits(n)).any { owners[root(s + it)]!!.size > 1 }) namedConnect.add(n)
            }
        }

        private fun nameOf(n: Net): String = netName[n]!!

        /* the bit range of a splitter pin like "0-3", "0,1" or "4" */
        private fun range(pinName: String): Pair<Int, Int> {
            val p = pinName.split('-', ',').map { it.trim().toInt() }
            return p.first() to p.last()
        }

        // ---------------------------------------------------------------------------------
        // elements
        // ---------------------------------------------------------------------------------

        private fun pinName(ve: VisualElement): String = pinNames.getOrPut(ve) {
            val l = ve.elementAttributes.label
            if (l.isNullOrBlank()) freshName(if (ve.elementName in INPUTS) "in" else "out") else l
        }

        private fun exportElement(ve: VisualElement) {
            val en = ve.elementName
            when {
                en in INPUTS -> exportPin(ve, true)
                en in OUTPUTS -> exportPin(ve, false)
                en == "Splitter" || en in IGNORED -> {}
                en == "Tunnel" -> exportTunnel(ve)
                en == "Text" -> ve.elementAttributes.get(Keys.DESCRIPTION).takeIf { it.isNotBlank() }?.let {
                    val p = q(ve.pos)
                    val t = QSymbol.createText(it.lines().first(), p.x, p.y, 10)
                    out.append("(text \"${esc(t.text)}\" ${rect(t.rect)}(font \"Arial\" (font_size 10)))\n")
                }
                else -> {
                    val m = map(ve)
                    if (m == null) {
                        warn("$name: the element '$en' can not be exported, it is replaced by an empty block")
                        writeSymbol(ve, generic(ve, en))
                    } else writeSymbol(ve, m)
                }
            }
        }

        private fun exportPin(ve: VisualElement, input: Boolean) {
            val p = pins[ve]!!.firstOrNull() ?: return
            val n = netOf[p]
            // the name of the pin is the name of the port of the design, it is kept even if the net is named differently
            val pinLabel = n?.let { netName[it] }?.takeIf { BusName.parse(it).base.equals(sanitize(pinName(ve)), true) }
                ?: sanitize(pinName(ve))
            val pq = q(p.pos)
            val bits = bits(n)
            var label = pinLabel
            if (bits > 1 && !label.contains('[')) label = "$label[${bits - 1}..0]"
            if (input) {
                out.append("(pin\n\t(input)\n\t(rect ${pq.x - 168} ${pq.y - 8} ${pq.x} ${pq.y + 8})\n")
                out.append("\t(text \"INPUT\" (rect 133 0 161 10)(font \"Arial\" (font_size 6)))\n")
                out.append("\t(text \"${esc(label)}\" (rect 5 0 ${5 + label.length * 6} 12)(font \"Arial\" ))\n")
                out.append("\t(pt 168 8)\n\t(drawing\n")
                for (l in INPUT_PIN_LINES) out.append("\t\t(line (pt ${l[0]} ${l[1]})(pt ${l[2]} ${l[3]})(line_width 1))\n")
                out.append("\t)\n\t(text \"VCC\" (rect 136 7 156 17)(font \"Arial\" (font_size 6)))\n)\n")
            } else {
                out.append("(pin\n\t(output)\n\t(rect ${pq.x} ${pq.y - 8} ${pq.x + 176} ${pq.y + 8})\n")
                out.append("\t(text \"OUTPUT\" (rect 1 0 39 10)(font \"Arial\" (font_size 6)))\n")
                out.append("\t(text \"${esc(label)}\" (rect 90 0 ${90 + label.length * 6} 12)(font \"Arial\" ))\n")
                out.append("\t(pt 0 8)\n\t(drawing\n")
                for (l in OUTPUT_PIN_LINES) out.append("\t\t(line (pt ${l[0]} ${l[1]})(pt ${l[2]} ${l[3]})(line_width 1))\n")
                out.append("\t)\n)\n")
            }
            addPoint(pq, if (n != null) idOf(n) else "pin:" + uniqueCounter++)
            // a pin named differently than its net is connected by name
            if (n != null && netName[n] != null && BusName.parse(netName[n]!!).key != BusName.parse(label).key) {
                namedConnect.add(n)
                addStub(pq, if (input) Point(16, 0) else Point(-16, 0), idOf(n), netName[n]!!)
            }
        }

        private fun exportTunnel(ve: VisualElement) {
            val p = pins[ve]!!.firstOrNull() ?: return
            val n = netOf[p] ?: return
            namedConnect.add(n)
            addStub(q(p.pos), Point(-16, 0), idOf(n), nameOf(n))
        }

        /* maps a Digital element to a Quartus symbol, null if not possible */
        private fun map(ve: VisualElement): Mapped? {
            val a = ve.elementAttributes
            val ps = pins[ve]!!
            fun pin(n: String) = ps.firstOrNull { it.name == n }
            fun links(vararg m: Pair<String, String>, inverted: Set<String> = emptySet()) =
                { s: QSymbol -> m.mapNotNull { (d, qn) -> s.getPort(qn)?.let { PortLink(pin(d), it, d in inverted) } } }
            val inv = a.get(Keys.INVERTER_CONFIG)
            val invSet = ps.map { it.name }.filter { inv.contains(it) }.toSet()
            return when (ve.elementName) {
                "And", "NAnd", "Or", "NOr", "XOr", "XNOr" -> {
                    val n = a.get(Keys.INPUT_COUNT)
                    val isXor = ve.elementName.endsWith("XOr") || ve.elementName == "XNOr"
                    if (isXor && n != 2) return null
                    val base = ve.elementName.uppercase()
                    val size = if (isXor) 2 else GATE_SIZES.firstOrNull { it >= n } ?: return null
                    val qname = if (isXor) base else "$base$size"
                    val gate = when {
                        isXor -> SymbolFactory.Gate.XOR
                        base.contains("AND") -> SymbolFactory.Gate.AND
                        else -> SymbolFactory.Gate.OR
                    }
                    val s = SymbolFactory.gate(qname, gate, size, base.startsWith("N") || base == "XNOR", false)
                    val l = ArrayList<PortLink>()
                    for (i in 1..size) {
                        val port = s.getPort("IN$i")!!
                        if (i <= n) l.add(PortLink(pin("In_$i"), port, "In_$i" in invSet))
                        else l.add(PortLink(null, port, false, if (gate == SymbolFactory.Gate.AND) "VCC" else "GND"))
                    }
                    l.add(PortLink(pin("out"), s.getPort("OUT")!!, false))
                    if (a.bits > 1) warn("$name: the Quartus gate $qname is used for a ${a.bits} bit bus")
                    Mapped(s, qname, l)
                }
                "Not" -> if (a.bits > 1) part(ve, "LPM_INV", mapOf("LPM_WIDTH" to "${a.bits}"), "in" to "data", "out" to "result")
                else SymbolFactory.inverter().let { Mapped(it, "NOT", links("in" to "IN", "out" to "OUT")(it)) }
                "Delay" -> SymbolFactory.buffer("WIRE").let { Mapped(it, "WIRE", links("in" to "IN", "out" to "OUT")(it)) }
                "Driver", "DriverInvSel" -> SymbolFactory.buffer("TRI", "OE").let {
                    Mapped(it, "TRI", links("in" to "IN", "sel" to "OE", "out" to "OUT",
                        inverted = if (ve.elementName == "DriverInvSel") setOf("sel") else emptySet())(it))
                }
                "Const", "VDD", "Ground" -> {
                    val bits = a.bits
                    val v = if (ve.elementName == "VDD") -1L else if (ve.elementName == "Ground") 0L else a.get(Keys.VALUE)
                    val all = if (bits >= 64) -1L else (1L shl bits) - 1
                    when (v and all) {
                        all -> SymbolFactory.vcc().let { Mapped(it, "VCC", links("out" to "1")(it)) }
                        0L -> SymbolFactory.gnd().let { Mapped(it, "GND", links("out" to "1")(it)) }
                        else -> part(ve, "LPM_CONSTANT", mapOf("LPM_WIDTH" to "$bits", "LPM_CVALUE" to "${v and all}"), "out" to "result")
                    }
                }
                "D_FF" -> if (a.bits > 1) part(ve, "LPM_FF", mapOf("LPM_WIDTH" to "${a.bits}"), "D" to "data", "C" to "clock", "Q" to "q")
                else ff(ve, "DFF", "D" to "D", "C" to "CLK")
                "D_FF_AS" -> ff(ve, "DFF", "D" to "D", "C" to "CLK", async = true)
                "T_FF" -> ff(ve, "TFF", "T" to "T", "C" to "CLK", tVcc = pin("T") == null)
                "JK_FF" -> ff(ve, "JKFF", "J" to "J", "K" to "K", "C" to "CLK")
                "JK_FF_AS" -> ff(ve, "JKFF", "J" to "J", "K" to "K", "C" to "CLK", async = true)
                "RS_FF" -> ff(ve, "SRFF", "S" to "S", "R" to "R", "C" to "CLK")
                "Register" -> part(ve, "LPM_FF", mapOf("LPM_WIDTH" to "${a.bits}"), "D" to "data", "C" to "clock", "en" to "enable", "Q" to "q")
                "Counter" -> part(ve, "LPM_COUNTER", mapOf("LPM_WIDTH" to "${a.bits}"),
                    "en" to "cnt_en", "C" to "clock", "clr" to "sclr", "out" to "q", "ovf" to "cout")
                "Add" -> part(ve, "LPM_ADD_SUB", mapOf("LPM_WIDTH" to "${a.bits}", "LPM_DIRECTION" to "ADD"),
                    "a" to "dataa", "b" to "datab", "c_i" to "cin", "s" to "result", "c_o" to "cout")
                "Sub" -> part(ve, "LPM_ADD_SUB", mapOf("LPM_WIDTH" to "${a.bits}", "LPM_DIRECTION" to "SUB"),
                    "a" to "dataa", "b" to "datab", "c_i" to "cin", "s" to "result", "c_o" to "cout", inverted = setOf("c_i", "c_o"))
                "Comparator" -> part(ve, "LPM_COMPARE", mapOf("LPM_WIDTH" to "${a.bits}",
                    "LPM_REPRESENTATION" to if (a.get(Keys.SIGNED)) "SIGNED" else "UNSIGNED"),
                    "a" to "dataa", "b" to "datab", ">" to "agb", "=" to "aeb", "<" to "alb")
                "Mul" -> part(ve, "LPM_MULT", mapOf("LPM_WIDTHA" to "${a.bits}", "LPM_WIDTHB" to "${a.bits}", "LPM_WIDTHP" to "${a.bits * 2}"),
                    "a" to "dataa", "b" to "datab", "mul" to "result")
                "Multiplexer" -> if (a.get(Keys.SELECTOR_BITS) == 1)
                    part(ve, "BUSMUX", mapOf("WIDTH" to "${a.bits}"), "sel" to "sel", "in_0" to "dataa", "in_1" to "datab", "out" to "result")
                else a.get(Keys.SELECTOR_BITS).let { sb ->
                    val n = 1 shl sb
                    if (n > 16) null
                    else part(ve, "LPM_MUX", mapOf("LPM_WIDTH" to "${a.bits}", "LPM_SIZE" to "$n", "LPM_WIDTHS" to "$sb"),
                        "sel" to "sel", *Array(n) { "in_$it" to "data${it}x" }, "out" to "result")
                }
                "Decoder" -> {
                    val sb = a.get(Keys.SELECTOR_BITS)
                    val n = 1 shl sb
                    val m = part(ve, "LPM_DECODE", mapOf("LPM_WIDTH" to "$sb", "LPM_DECODES" to "$n"), "sel" to "data", "" to "eq")
                    Mapped(m.symbol, m.typeName, m.links.map {
                        if (it.quartusPort.name == "eq") PortLink(null, it.quartusPort, false, decoderBus[ve]!!) else it
                    }, m.params)
                }
                "ROM" -> exportRom(ve)
                else -> mapQuartusPart(ve) ?: mapSubCircuit(ve)
            }
        }

        private fun part(ve: VisualElement, qname: String, params: Map<String, String>, vararg m: Pair<String, String>,
                         inverted: Set<String> = emptySet()): Mapped {
            val part = QuartusParts.get(qname)!!
            val p = Params(part.defaultParams)
            params.forEach { (k, v) -> p.set(k, v) }
            p.set(Params.PORTS, m.joinToString(",") { it.second })
            val s = part.getSymbol(p)
            val ps = pins[ve]!!
            val l = m.mapNotNull { (d, qn) -> s.getPort(qn)?.let { PortLink(ps.firstOrNull { it.name == d }, it, d in inverted) } }
            return Mapped(s, qname.lowercase(), l, params)
        }

        private fun ff(ve: VisualElement, qname: String, vararg m: Pair<String, String>, async: Boolean = false, tVcc: Boolean = false): Mapped {
            if (ve.elementAttributes.get(Keys.DEFAULT) != 0L)
                warn("$name: the initial value of the flip-flop ${ve.elementAttributes.label ?: ve.elementName} is not exported, Quartus flip-flops start with 0")
            val ports = m.map { it.second }.toMutableList()
            if (async) ports.addAll(listOf("PRN", "CLRN"))
            if (tVcc) ports.add(0, "T")
            val s = SymbolFactory.flipFlop(qname, ports, setOf("CLK"), listOf("Q"))
            val ps = pins[ve]!!
            fun pin(n: String) = ps.firstOrNull { it.name == n }
            val l = ArrayList<PortLink>()
            m.forEach { (d, qn) -> l.add(PortLink(pin(d), s.getPort(qn)!!, false)) }
            if (tVcc) l.add(PortLink(null, s.getPort("T")!!, false, "VCC"))
            if (async) {
                l.add(PortLink(pin("Set"), s.getPort("PRN")!!, true))
                l.add(PortLink(pin("Clr"), s.getPort("CLRN")!!, true))
            }
            l.add(PortLink(pin("Q"), s.getPort("Q")!!, false))
            pin("~Q")?.let { nq -> if (netOf[nq]?.let { it.pins.size > 1 || it.wires.isNotEmpty() } == true) l.add(PortLink(nq, s.getPort("Q")!!, true)) }
            return Mapped(s, qname, l)
        }

        private fun exportRom(ve: VisualElement): Mapped {
            val a = ve.elementAttributes
            val bits = a.bits
            val addrBits = a.get(Keys.ADDR_BITS)
            val label = a.label.takeIf { !it.isNullOrBlank() } ?: "rom${instCounter}"
            var mif = File(dir, sanitize("${name}_$label") + ".mif")
            var k = 1
            while (createdFiles.contains(mif)) mif = File(dir, sanitize("${name}_${label}_${k++}") + ".mif")
            val data = a.get(Keys.DATA)
            val sb = StringBuilder("WIDTH=$bits;\nDEPTH=${1 shl addrBits};\nADDRESS_RADIX=HEX;\nDATA_RADIX=HEX;\nCONTENT BEGIN\n")
            sb.append("\t[0..${Integer.toHexString((1 shl addrBits) - 1).uppercase()}] : 0;\n")
            for (i in 0 until minOf(data.hgsArraySize(), 1 shl addrBits))
                data.getDataWord(i).takeIf { it != 0L }?.let { sb.append("\t${Integer.toHexString(i).uppercase()} : ${java.lang.Long.toHexString(it).uppercase()};\n") }
            sb.append("END;\n")
            mif.writeText(sb.toString())
            createdFiles.add(mif)
            return part(ve, "LPM_ROM", mapOf("LPM_WIDTH" to "$bits", "LPM_WIDTHAD" to "$addrBits", "LPM_FILE" to mif.name,
                "LPM_ADDRESS_CONTROL" to "UNREGISTERED", "LPM_OUTDATA" to "UNREGISTERED"),
                "A" to "address", "sel" to "memenab", "D" to "q")
        }

        private fun mapQuartusPart(ve: VisualElement): Mapped? {
            val part = QuartusParts.getAll().firstOrNull { it.digitalName == ve.elementName } ?: return null
            val p = part.getParams(ve.elementAttributes)
            val s = part.getSymbol(p)
            val l = pins[ve]!!.mapNotNull { pin -> s.getPort(pin.name)?.let { PortLink(pin, it, false) } }
            return Mapped(s, part.quartusName, l, p.map.filterKeys { it != Params.PORTS })
        }

        private fun mapSubCircuit(ve: VisualElement): Mapped? {
            val d = try {
                library.getElementType(ve.elementName)
            } catch (e: Exception) {
                return null
            }
            if (d !is ElementTypeDescriptionCustom) return null
            val sub = sanitize(QuartusProject.stem(File(ve.elementName).name))
            if (exported.add(sub.lowercase())) {
                try {
                    val f = File(dir, "$sub.bdf")
                    f.writeText(CircuitExport(d.circuit, sub, dir).create(), Charsets.ISO_8859_1)
                    createdFiles.add(f)
                } catch (e: Exception) {
                    warn("$sub: could not be exported: ${e.message}")
                }
            }
            return generic(ve, sub)
        }

        private fun generic(ve: VisualElement, title: String): Mapped {
            val ps = pins[ve]!!
            fun port(p: Pin): SymbolFactory.Port {
                val b = bits(netOf[p])
                val n = sanitize(p.name)
                return SymbolFactory.Port(n, if (b > 1) "$n[${b - 1}..0]" else n, b > 1)
            }
            val s = SymbolFactory.block(sanitize(title),
                ps.filter { it.direction == PinDescription.Direction.input }.map { port(it) },
                ps.filter { it.direction != PinDescription.Direction.input }.map { port(it) })
            // the names of the ports include the bus range
            val l = ps.mapNotNull { p -> s.getPort(sanitize(p.name))?.let { PortLink(p, it, false) } }
            return Mapped(s, sanitize(title), l)
        }

        // ---------------------------------------------------------------------------------
        // writing
        // ---------------------------------------------------------------------------------

        private fun writeSymbol(ve: VisualElement, m0: Mapped) {
            val libOrigin = libraryOrigin(ve, m0)
            val m = if (libOrigin != null) m0 else adapt(ve, m0)
            val origin = libOrigin ?: m.origin!!
            val ids = portIds(m)
            for (port in m.symbol.ports) addPoint(origin.add(port.pt.x, port.pt.y), ids[port]!!)

            val portNames = HashMap<String, String>()
            if (m.typeName != "VCC" && m.typeName != "GND") for (l in m.links) {
                val b = if (l.digitalPin != null) bits(netOf[l.digitalPin]) else l.fixedNet?.let { BusName.parse(it).bits } ?: 1
                if (b > 1 && !l.quartusPort.name.contains('[')) portNames[l.quartusPort.name] = "${l.quartusPort.name}[${b - 1}..0]"
            }
            writeSymbolText(m.symbol, origin, m.typeName, instanceName(ve), m.params, portNames)
            for (l in m.links) {
                val qp = origin.add(l.quartusPort.pt.x, l.quartusPort.pt.y)
                val dir = m.dirs[l.quartusPort] ?: stubDir(m.symbol, l.quartusPort)
                val id = ids[l.quartusPort]!!
                when {
                    l.fixedNet != null -> addStub(qp, dir, id, l.fixedNet)
                    l.digitalPin == null -> {}
                    l.invert -> {
                        // connected by name via an inverter
                        val n = netOf[l.digitalPin] ?: continue
                        val src = nameOf(n)
                        namedConnect.add(n)
                        val b = bits(n)
                        val tmp = freshName("inv").let { if (b > 1) "$it[${b - 1}..0]" else it }
                        addStub(qp, dir, id, tmp, INV_STUB_LENGTHS)
                        if (l.quartusPort.direction == Direction.INPUT) writeInverter(src, tmp, idOf(n), id, b)
                        else writeInverter(tmp, src, id, idOf(n), b)
                    }
                    else -> {
                        val n = netOf[l.digitalPin] ?: continue
                        if (n.pins.size <= 1 && n.wires.isEmpty() && n.labels.isEmpty()) continue
                        // the port is connected if it touches a wire of the net
                        if (segs.any { it.id == id && it.label == null && it.a != it.b && onSegment(it.a, it.b, qp) }) continue
                        namedConnect.add(n)
                        addStub(qp, dir, id, nameOf(n))
                    }
                }
            }
        }

        /* the ids of the ports: the id of the net or a unique id */
        private fun portIds(m: Mapped): Map<QSymbol.QPort, String> = m.symbol.ports.associateWith { port ->
            val l = m.links.firstOrNull { it.quartusPort === port }
            when {
                l == null -> "unused:" + uniqueCounter++
                l.fixedNet != null -> "name:" + l.fixedNet
                l.invert || l.digitalPin == null -> "inv:" + uniqueCounter++
                else -> netOf[l.digitalPin]?.let { idOf(it) } ?: ("open:" + uniqueCounter++)
            }
        }

        /*
         * Returns the position of the library symbol if its ports match the positions of the Digital pins.
         * This is the case if the circuit was imported from Quartus. Returns null if the symbol has to be adapted.
         */
        private fun libraryOrigin(ve: VisualElement, m: Mapped): Point? {
            if (m.links.any { it.invert }) return null
            val anchored = m.links.filter { it.digitalPin != null }
            val first = anchored.firstOrNull() ?: return if (m.links.isEmpty()) q(ve.pos) else null
            val o = q(first.digitalPin!!.pos).add(-first.quartusPort.pt.x, -first.quartusPort.pt.y)
            if (anchored.any { q(it.digitalPin!!.pos) != o.add(it.quartusPort.pt.x, it.quartusPort.pt.y) }) return null
            val ids = portIds(m)
            for (port in m.symbol.ports) {
                val p = o.add(port.pt.x, port.pt.y)
                if ((idsAt(p) - ids[port]!!).isNotEmpty()) return null
                if (m.links.none { it.quartusPort === port && it.digitalPin != null } && digitalPins.containsKey(p)) return null
            }
            for (l in m.links)
                if (l.fixedNet != null &&
                    findStub(o.add(l.quartusPort.pt.x, l.quartusPort.pt.y), stubDir(m.symbol, l.quartusPort), "name:" + l.fixedNet, STUB_LENGTHS) == null)
                    return null
            return o
        }

        /*
         * Creates a symbol whose ports are placed at the positions of the Digital pins.
         * Gates and buffers are drawn like the Quartus primitives, all other components as a box.
         */
        private fun adapt(ve: VisualElement, m: Mapped): Mapped {
            val mm = ve.getMinMax(false)
            var x1 = ShapeBuilder.unscale(mm.min.x)
            var y1 = ShapeBuilder.unscale(mm.min.y)
            var x2 = ShapeBuilder.unscale(mm.max.x)
            var y2 = ShapeBuilder.unscale(mm.max.y)
            val pos = IdentityHashMap<PortLink, Point>()
            for (l in m.links) l.digitalPin?.let { pos[l] = q(it.pos) }
            for (p in pos.values) {
                x1 = minOf(x1, p.x)
                y1 = minOf(y1, p.y)
                x2 = maxOf(x2, p.x)
                y2 = maxOf(y2, p.y)
            }
            val ins = m.links.filter { pos[it] != null && it.quartusPort.direction == Direction.INPUT }
            val outs = m.links.filter { pos[it] != null && it.quartusPort.direction != Direction.INPUT }
            val tn = m.typeName.uppercase()
            val gate = when {
                tn == "XOR" || tn == "XNOR" -> 'X'
                GATE_NAME.matches(tn) -> if (tn.contains("AND")) 'A' else 'O'
                tn == "NOT" || tn == "WIRE" || tn == "TRI" -> 'T'
                else -> ' '
            }
            val invOut = tn.startsWith("N") || tn == "XNOR"
            val main = if (gate == 'T') ins.filter { it.quartusPort.name == "IN" } else ins
            val xi = main.firstOrNull()?.let { pos[it]!!.x }
            val out = outs.singleOrNull()?.let { pos[it]!! }
            val isGate = gate != ' ' && xi != null && out != null && out.x > xi + 8 && main.all { pos[it]!!.x == xi } &&
                (gate != 'T' || pos[main[0]]!!.y == out.y)

            // the ports without a Digital pin are placed below the other ports
            for (l in m.links.filter { pos[it] == null }) {
                val input = l.quartusPort.direction == Direction.INPUT
                val x = if (input) (if (isGate) xi!! else x1) else x2
                var y = (pos.filter { (k, _) -> (k.quartusPort.direction == Direction.INPUT) == input }.values.maxOfOrNull { it.y } ?: y1) + 16
                var tries = 0
                while ((idsAt(Point(x, y)).isNotEmpty() || digitalPins.containsKey(Point(x, y))) && tries++ < 32) y += 16
                pos[l] = Point(x, y)
                y2 = maxOf(y2, y + if (isGate) 0 else 8)
            }
            if (x2 - x1 < 16) x2 = x1 + 16
            if (y2 - y1 < 16) y2 = y1 + 16

            val s = QSymbol(m.symbol.name, 0, 0)
            val newPorts = IdentityHashMap<PortLink, QSymbol.QPort>()
            val dirs = IdentityHashMap<PortLink, Point>()
            val inward = IdentityHashMap<PortLink, Point>()
            for (l in m.links) {
                val p = pos[l]!!
                val d = when {
                    isGate && l.quartusPort.direction == Direction.INPUT && pos[l]!!.x == xi -> Point(-16, 0)
                    isGate && l.quartusPort.direction != Direction.INPUT -> Point(16, 0)
                    p.x <= x1 -> Point(-16, 0)
                    p.x >= x2 -> Point(16, 0)
                    p.y <= y1 -> Point(0, -16)
                    p.y >= y2 -> Point(0, 16)
                    else -> listOf(p.x - x1 to Point(-16, 0), x2 - p.x to Point(16, 0), p.y - y1 to Point(0, -16), y2 - p.y to Point(0, 16))
                        .minBy { it.first }.second
                }
                dirs[l] = d
                inward[l] = Point(-sign(d.x), -sign(d.y))
            }

            val labels: Boolean
            if (isGate) {
                val ys = m.links.filter { it.quartusPort.direction == Direction.INPUT && pos[it]!!.x == xi }.map { pos[it]!!.y }
                val yTop = minOf(ys.minOrNull() ?: out!!.y, out!!.y) - 8
                val yBot = maxOf(ys.maxOrNull() ?: out.y, out.y) + 8
                drawGate(s, gate, xi!!, yTop, yBot, out.x, out.y, invOut || tn == "NOT")
                s.typeText = QSymbol.createText(m.typeName, xi, yTop - 6, 6)
                s.instanceText = QSymbol.createText("inst", xi, yBot + 6, 6)
                labels = false
            } else {
                s.rect(x1, y1, x2, y2)
                s.typeText = QSymbol.createText(m.typeName, x1, y1 - 6, 6)
                s.instanceText = QSymbol.createText("inst", x1, y2 + 6, 6)
                val left = m.links.filter { dirs[it]!!.x < 0 }.maxOfOrNull { it.quartusPort.name.length } ?: 0
                val right = m.links.filter { dirs[it]!!.x > 0 }.maxOfOrNull { it.quartusPort.name.length } ?: 0
                labels = (left + right) * 4.5 + 10 <= x2 - x1
            }
            for (l in m.links) {
                var p = pos[l]!!
                val inw = inward[l]!!
                val qp = l.quartusPort
                if (l.invert) {
                    s.circle(p.x + inw.x * 4, p.y + inw.y * 4, 4)
                    // the port is placed inside the symbol if the wire can not be shortened
                    val n = l.digitalPin?.let { netOf[it] }
                    if (n == null || !detach(p, Point(-inw.x, -inw.y), idOf(n))) p = Point(p.x + inw.x * 8, p.y + inw.y * 8)
                }
                var line: BdfFile.Graphic? = null
                if (isGate && qp.direction == Direction.INPUT && !l.invert && gate != 'A') {
                    val len = if (gate == 'X') 8 else 3
                    line = BdfFile.Graphic.line(p.x, p.y, p.x + len, p.y)
                } else if (isGate && gate == 'T' && qp.name != "IN" && qp.direction == Direction.INPUT) {
                    val ty = out!!.y + if (p.y < out.y) -4 else 4
                    line = BdfFile.Graphic.line(p.x, p.y, p.x, ty)
                }
                if (!isGate && !l.invert && CLOCK.matches(qp.name) && dirs[l]!!.x < 0) {
                    s.line(p.x, p.y - 4, p.x + 6, p.y)
                    s.line(p.x + 6, p.y, p.x, p.y + 4)
                }
                var label: BdfFile.Text? = null
                if (labels) {
                    val t = qp.name
                    val w = (t.length * 4.5).toInt() + 2
                    val d = dirs[l]!!
                    val shift = if (l.invert) 8 else if (CLOCK.matches(t) && d.x < 0) 7 else 2
                    label = when {
                        d.x < 0 -> QSymbol.createText(t, pos[l]!!.x + shift, pos[l]!!.y, 6)
                        d.x > 0 -> QSymbol.createText(t, pos[l]!!.x - shift - w, pos[l]!!.y, 6)
                        d.y < 0 -> QSymbol.createText(t, pos[l]!!.x - w / 2, pos[l]!!.y + 6 + shift, 6)
                        else -> QSymbol.createText(t, pos[l]!!.x - w / 2, pos[l]!!.y - 6 - shift, 6)
                    }
                }
                val np = QSymbol.QPort(qp.name, qp.direction, p, line, label)
                s.ports.add(np)
                newPorts[l] = np
            }

            val b = s.bounds()
            val t = s.translated(-b.x1, -b.y1)
            t.width = b.x2 - b.x1
            t.height = b.y2 - b.y1
            val byName = t.ports.associateBy { it.name }
            val links = m.links.map { l ->
                PortLink(l.digitalPin, byName[newPorts[l]!!.name]!!, l.invert, l.fixedNet)
            }
            val r = Mapped(t, m.typeName, links, m.params)
            r.origin = Point(b.x1, b.y1)
            m.links.forEachIndexed { i, l -> r.dirs[links[i].quartusPort] = dirs[l]!! }
            return r
        }

        /* draws a gate, the inputs are at x=xi, the output at (xo,yo) */
        private fun drawGate(s: QSymbol, gate: Char, xi: Int, yTop: Int, yBot: Int, xo: Int, yo: Int, invOut: Boolean) {
            val xr = if (invOut) xo - 8 else xo
            when (gate) {
                'A' -> {
                    val xm = (xi + xr) / 2
                    s.line(xi, yTop, xm, yTop)
                    s.line(xi, yBot, xm, yBot)
                    s.line(xi, yTop, xi, yBot)
                    s.arc(xm, yBot, xm, yTop, BdfFile.Rect(xi, yTop, xr, yBot))
                }
                'T' -> {
                    s.line(xi, yo - 8, xi, yo + 8)
                    s.line(xi, yo - 8, xr, yo)
                    s.line(xi, yo + 8, xr, yo)
                }
                else -> {
                    val xl = if (gate == 'X') xi + 6 else xi
                    s.arc(xl, yBot, xl, yTop, BdfFile.Rect(xl - 6, yTop, xl + 6, yBot))
                    if (gate == 'X') s.arc(xi, yBot, xi, yTop, BdfFile.Rect(xi - 6, yTop, xi + 6, yBot))
                    val c = xl + (xr - xl) * 2 / 3
                    s.curve(xl, yTop, c, yTop, xr, yo)
                    s.curve(xl, yBot, c, yBot, xr, yo)
                }
            }
            if (invOut) s.circle(xr + 4, yo, 4)
        }

        private fun stubDir(s: QSymbol, port: QSymbol.QPort): Point = when {
            port.pt.x <= 0 -> Point(-16, 0)
            port.pt.x >= s.width -> Point(16, 0)
            port.pt.y <= 0 -> Point(0, -16)
            else -> Point(0, 16)
        }

        private fun writeInverter(inNet: String, outNet: String, inId: String, outId: String, bits: Int) {
            if (bits > 1) {
                val part = QuartusParts.get("LPM_INV")!!
                val params = mapOf("LPM_WIDTH" to "$bits")
                val s = part.getSymbol(Params(part.defaultParams).also { p -> params.forEach { (k, v) -> p.set(k, v) } })
                val o = nextFree(s.width + 48, s.height + 16)
                writeSymbolText(s, o, "lpm_inv", "inst${instCounter++}", params,
                    mapOf("data" to "data[${bits - 1}..0]", "result" to "result[${bits - 1}..0]"))
                val pin = s.getPort("data")!!.pt
                val pout = s.getPort("result")!!.pt
                addStub(o.add(pin.x, pin.y), Point(-16, 0), inId, inNet)
                addStub(o.add(pout.x, pout.y), Point(16, 0), outId, outNet)
                return
            }
            val s = SymbolFactory.inverter()
            val o = nextFree(96, 48)
            writeSymbolText(s, o, "NOT", "inst${instCounter++}", emptyMap(), emptyMap())
            val pin = s.getPort("IN")!!.pt
            val pout = s.getPort("OUT")!!.pt
            addStub(o.add(pin.x, pin.y), Point(-16, 0), inId, inNet)
            addStub(o.add(pout.x, pout.y), Point(16, 0), outId, outNet)
        }

        /* the position of the next helper symbol placed below the circuit */
        private fun nextFree(w: Int, h: Int): Point {
            if (freeX + w > 1600) {
                freeX = 0
                freeY += freeRow
                freeRow = 0
            }
            val o = Point(freeX + 32, freeY)
            freeX += w + 32
            freeRow = maxOf(freeRow, (h + 32) / 8 * 8)
            return o
        }

        private fun instanceName(ve: VisualElement): String {
            val l = ve.elementAttributes.label
            if (!l.isNullOrBlank() && sanitize(l) == l && usedNames.add("inst:$l")) return l
            return "inst${instCounter++}"
        }

        private fun writeSymbolText(s: QSymbol, o: Point, type: String, inst: String, params: Map<String, String>, portNames: Map<String, String>) {
            out.append("(symbol\n\t(rect ${o.x} ${o.y} ${o.x + s.width} ${o.y + s.height})\n")
            val tt = s.typeText ?: QSymbol.createText(type, 1, 3, 6)
            out.append("\t(text \"${esc(type)}\" ${rect(tt.rect)}(font \"Arial\" (font_size ${maxOf(6, tt.fontSize)})))\n")
            val it = s.instanceText ?: QSymbol.createText(inst, 3, s.height - 4)
            out.append("\t(text \"${esc(inst)}\" ${rect(it.rect)}(font \"Arial\" ))\n")
            for (p in s.ports) {
                val pn = portNames[p.name] ?: p.name
                out.append("\t(port\n\t\t(pt ${p.pt.x} ${p.pt.y})\n\t\t(${p.direction.name.lowercase()})\n")
                val lt = p.label
                if (lt == null) {
                    val r = BdfFile.Rect(p.pt.x + 2, p.pt.y - 9, p.pt.x + 2 + pn.length * 6, p.pt.y + 3)
                    repeat(2) { out.append("\t\t(text \"${esc(pn)}\" ${rect(r)}(font \"Courier New\" (bold))(invisible))\n") }
                } else repeat(2) { out.append("\t\t(text \"${esc(pn)}\" ${rect(lt.rect)}(font \"Arial\" (font_size 8)))\n") }
                p.line?.let { out.append("\t\t(line (pt ${it.p1!!.x} ${it.p1.y})(pt ${it.p2!!.x} ${it.p2.y})(line_width ${it.lineWidth}))\n") }
                out.append("\t)\n")
            }
            for ((k, v) in params) out.append("\t(parameter \"${esc(k)}\" \"${esc(v)}\" \"\" )\n")
            out.append("\t(drawing\n")
            for (g in s.graphics) out.append("\t\t").append(graphic(g)).append('\n')
            out.append("\t)\n)\n")
        }

        private fun graphic(g: BdfFile.Graphic): String = when (g.type) {
            BdfFile.GraphicType.LINE -> "(line (pt ${g.p1!!.x} ${g.p1.y})(pt ${g.p2!!.x} ${g.p2.y})(line_width ${g.lineWidth}))"
            BdfFile.GraphicType.RECTANGLE -> "(rectangle ${rect(g.rect!!)}(line_width ${g.lineWidth}))"
            BdfFile.GraphicType.CIRCLE -> "(circle ${rect(g.rect!!)}(line_width ${g.lineWidth}))"
            BdfFile.GraphicType.ARC -> "(arc (pt ${g.p1!!.x} ${g.p1.y})(pt ${g.p2!!.x} ${g.p2.y})${rect(g.rect!!)}(line_width ${g.lineWidth}))"
            BdfFile.GraphicType.TEXT -> g.text!!.let { "(text \"${esc(it.text)}\" ${rect(it.rect)}(font \"Arial\" (font_size ${maxOf(6, it.fontSize)})))" }
        }

        /*
         * In Digital wires are only connected at their end points. Two collinear wires of a net are joined,
         * so that they cross a wire of an other net without touching it.
         */
        private fun mergeCollinear() {
            var changed = true
            while (changed) {
                changed = false
                val ends = HashMap<Pair<String, Point>, MutableList<Seg>>()
                for (s in segs) if (s.net != null) {
                    ends.getOrPut(s.id to s.a) { ArrayList() }.add(s)
                    ends.getOrPut(s.id to s.b) { ArrayList() }.add(s)
                }
                for ((k, l) in ends) {
                    if (l.size != 2 || digitalPins.containsKey(k.second)) continue
                    val (s1, s2) = l
                    if (s1 === s2) continue
                    val p = k.second
                    val o1 = if (s1.a == p) s1.b else s1.a
                    val o2 = if (s2.a == p) s2.b else s2.a
                    val cross = (o1.x - p.x).toLong() * (o2.y - p.y) - (o1.y - p.y).toLong() * (o2.x - p.x)
                    if (cross != 0L || !onSegment(o1, o2, p)) continue
                    s1.a = o1
                    s1.b = o2
                    segs.remove(s2)
                    changed = true
                    break
                }
            }
        }

        /*
         * In Quartus a connection point in the middle of a connector is connected to it, in Digital it is not.
         * Such connectors are split, the parts are connected by name.
         */
        private fun separateForeignPoints() {
            var tries = 0
            var found = true
            while (found && tries++ < 1000) {
                found = false
                for (s in segs) {
                    val n = s.net ?: continue
                    val p = foreignPointOn(s) ?: continue
                    val len = Math.abs(s.b.x - s.a.x) + Math.abs(s.b.y - s.a.y)
                    val dx = (s.b.x - s.a.x) * 4 / len
                    val dy = (s.b.y - s.a.y) * 4 / len
                    val b = s.b
                    s.b = Point(p.x - dx, p.y - dy)
                    segs.add(Seg(Point(p.x + dx, p.y + dy), b, s.id, n, null))
                    namedConnect.add(n)
                    found = true
                    break
                }
            }
        }

        private fun foreignPointOn(s: Seg): Point? {
            fun inner(p: Point) = p != s.a && p != s.b && onSegment(s.a, s.b, p)
            for ((p, ids) in geoPoints) if (ids.any { it != s.id } && inner(p)) return p
            for (o in segs) if (o.id != s.id) {
                if (inner(o.a)) return o.a
                if (inner(o.b)) return o.b
            }
            return null
        }

        /* writes the wires and the labeled stubs, the wires are split at the connection points */
        private fun exportConnectors() {
            separateForeignPoints()
            // every connected part of a net connected by name needs a label
            val toLabel = HashSet<Seg>()
            for ((id, l) in segs.filter { it.net != null && it.a != it.b && it.net in namedConnect }.groupBy { it.id }) {
                val parent = IntArray(l.size) { it }
                fun root(i: Int): Int = if (parent[i] == i) i else root(parent[i]).also { parent[i] = it }
                for (i in l.indices) for (j in i + 1 until l.size) {
                    val a = l[i]
                    val b = l[j]
                    if (onSegment(a.a, a.b, b.a) || onSegment(a.a, a.b, b.b) || onSegment(b.a, b.b, a.a) || onSegment(b.a, b.b, a.b))
                        parent[root(i)] = root(j)
                }
                val done = HashSet<Int>()
                for (i in l.indices) if (done.add(root(i))) toLabel.add(l[i])
            }
            val cuts = HashMap<String, MutableSet<Point>>()
            for (sg in segs) cuts.getOrPut(sg.id) { HashSet() }.apply {
                add(sg.a)
                add(sg.b)
            }
            for ((p, ids) in geoPoints) for (id in ids) cuts.getOrPut(id) { HashSet() }.add(p)
            val count = HashMap<Point, Int>()
            for ((p, ids) in geoPoints) count.merge(p, ids.size, Int::plus)
            for (sg in ArrayList(segs)) {
                if (sg.a == sg.b) continue
                val n = sg.net
                if (n == null) {
                    writeConnector(sg.a, sg.b, sg.label, false)
                    count.merge(sg.a, 1, Int::plus)
                    count.merge(sg.b, 1, Int::plus)
                    continue
                }
                val inner = cuts[sg.id]!!.filter { it != sg.a && it != sg.b && onSegment(sg.a, sg.b, it) }
                    .sortedBy { Math.abs(it.x - sg.a.x) + Math.abs(it.y - sg.a.y) }
                var prev = sg.a
                for (p in inner + sg.b) {
                    val label = if (prev == sg.a && sg in toLabel) nameOf(n) else null
                    writeConnector(prev, p, label, bits(n) > 1)
                    count.merge(prev, 1, Int::plus)
                    count.merge(p, 1, Int::plus)
                    prev = p
                }
            }
            for ((p, c) in count) if (c >= 3) out.append("(junction (pt ${p.x} ${p.y}))\n")
        }

        private fun writeConnector(p1: Point, p2: Point, label: String?, bus: Boolean) {
            if (p1 == p2) return
            out.append("(connector\n")
            if (label != null) {
                val x = minOf(p1.x, p2.x) + 2
                val y = minOf(p1.y, p2.y)
                out.append("\t(text \"${esc(label)}\" (rect $x ${y - 12} ${x + label.length * 6} $y)(font \"Arial\" ))\n")
            }
            out.append("\t(pt ${p1.x} ${p1.y})\n\t(pt ${p2.x} ${p2.y})\n")
            if (bus || (label != null && BusName.parse(label).bits > 1)) out.append("\t(bus)\n")
            out.append(")\n")
        }
    }

    companion object {
        /*
         * creates a name from the names of the bits, the first one is the msb:
         * [a[3], a[2], b] -> "a[3..2],b"
         */
        private fun compose(atoms: List<String>): String {
            class A(val base: String, val idx: Int?)
            val parsed = atoms.map { a ->
                val m = INDEXED.matchEntire(a)
                if (m == null) A(a, null) else A(m.groupValues[1], m.groupValues[2].toInt())
            }
            val parts = ArrayList<String>()
            var i = 0
            while (i < parsed.size) {
                val a = parsed[i]
                var j = i + 1
                if (a.idx != null)
                    while (j < parsed.size && parsed[j].base == a.base && parsed[j].idx == a.idx - (j - i)) j++
                parts.add(when {
                    a.idx == null -> a.base
                    j - i == 1 -> "${a.base}[${a.idx}]"
                    else -> "${a.base}[${a.idx}..${a.idx - (j - i - 1)}]"
                })
                i = j
            }
            return parts.joinToString(",")
        }

        private val INDEXED = Regex("(.+)\\[(\\d+)]")
        private val GATE_SIZES = listOf(2, 3, 4, 6, 8, 12)
        private val INPUTS = setOf("In", "Clock", "Button", "DipSwitch")
        private val OUTPUTS = setOf("Out", "LED", "PolarityAwareLED", "RGBLED", "LightBulb")
        private val IGNORED = setOf("Probe", "Testcase", "Data", "Rectangle", "ScopeTrigger", "Break", "Stop", "Reset",
            "AsyncSeq", "PowerSupply", "NotConnected", "GenericInitCode", "GenericCode")
        private val INPUT_PIN_LINES = listOf(intArrayOf(92, 12, 117, 12), intArrayOf(92, 4, 117, 4), intArrayOf(121, 8, 168, 8),
            intArrayOf(92, 12, 92, 4), intArrayOf(117, 4, 121, 8), intArrayOf(117, 12, 121, 8))
        private val OUTPUT_PIN_LINES = listOf(intArrayOf(0, 8, 52, 8), intArrayOf(52, 4, 78, 4), intArrayOf(52, 12, 78, 12),
            intArrayOf(52, 12, 52, 4), intArrayOf(78, 4, 82, 8), intArrayOf(82, 8, 78, 12))

        private const val HEADER = "/*\nWARNING: Do NOT edit the input and output ports in this file in a text\n" +
            "editor if you plan to continue editing the block that represents it in\n" +
            "the Block Editor! File corruption is VERY likely to occur.\n*/\n" +
            "/*\nCreated by QuartusMACOpenSource (Digital)\n*/\n" +
            "(header \"graphic\" (version \"1.3\"))\n"

        private fun q(v: Vector) = Point(ShapeBuilder.unscale(v.x), ShapeBuilder.unscale(v.y))

        private val STUB_LENGTHS = listOf(16, 8, 24, 32, 48, 64)
        private val INV_STUB_LENGTHS = listOf(4) + STUB_LENGTHS
        private val GATE_NAME = Regex("N?(AND|OR)\\d+")
        private val CLOCK = Regex("(?i)(in|out|wr|rd)?cl(oc)?k\\d?")

        private fun sign(v: Int) = Integer.signum(v)

        private fun onSegment(a: Point, b: Point, p: Point): Boolean {
            val cross = (b.x - a.x).toLong() * (p.y - a.y) - (b.y - a.y).toLong() * (p.x - a.x)
            return cross == 0L && p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)
        }

        private fun rect(r: BdfFile.Rect) = "(rect ${r.x1} ${r.y1} ${r.x2} ${r.y2})"

        private fun esc(s: String) = s.replace("\"", "'")

        /** creates a valid Quartus name */
        @JvmStatic
        fun sanitize(s: String): String {
            val n = s.trim().replace("~", "n").replace("<=", "le").replace(">=", "ge").replace("!=", "ne")
                .replace("<", "lt").replace(">", "gt").replace("=", "eq")
                .replace(Regex("[^A-Za-z0-9_\\[\\].]"), "_")
            return if (n.isEmpty() || !n[0].isLetter()) "n$n" else n
        }
    }
}
