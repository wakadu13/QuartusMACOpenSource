/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import java.io.File
import java.io.IOException

/**
 * The content of a Quartus block design file (bdf) or block symbol file (bsf).
 * All coordinates are Quartus units. The coordinates of ports and drawings are relative
 * to the upper left corner of the rectangle of a symbol or pin, and they are already
 * transformed (rotated/mirrored) by Quartus.
 */
class BdfFile private constructor(private val headerType: String) {
    /** the pins */
    val pins = ArrayList<Pin>()

    /** the symbols and blocks */
    val symbols = ArrayList<Symbol>()

    /** the connectors (wires and busses) */
    val connectors = ArrayList<Connector>()

    /** the junctions */
    val junctions = ArrayList<Point>()

    /** free graphics and texts drawn on the schematic */
    val graphics = ArrayList<Graphic>()

    /** true if this is a block symbol file */
    val isSymbolFile: Boolean get() = headerType == "symbol"

    companion object {
        /** reads a file */
        @JvmStatic
        @Throws(IOException::class)
        fun read(file: File): BdfFile = try {
            parse(String(file.readBytes(), Charsets.ISO_8859_1))
        } catch (e: IOException) {
            throw IOException("${file.name}: ${e.message}", e)
        }

        /** parses the given text */
        @JvmStatic
        @Throws(IOException::class)
        fun parse(text: String): BdfFile {
            val nodes = SExpr.parse(text)
            val header = nodes.firstOrNull()
            if (header == null || !header.name.equals("header", true))
                throw IOException("not a Quartus block design or symbol file: header missing")
            val f = BdfFile(header.getString(0)?.lowercase() ?: "graphic")
            for (n in nodes) {
                when (n.name.lowercase()) {
                    "pin" -> f.pins.add(Pin.of(n))
                    "symbol", "block" -> f.symbols.add(Symbol.of(n))
                    "connector" -> f.connectors.add(Connector.of(n))
                    "junction" -> n["pt"]?.let { f.junctions.add(Point.of(it)) }
                    "text", "line", "rectangle", "circle", "arc" -> Graphic.create(n)?.let { f.graphics.add(it) }
                }
            }
            return f
        }

        internal fun readDirection(n: SExpr) = when {
            n.has("output") -> Direction.OUTPUT
            n.has("bidir") -> Direction.BIDIR
            else -> Direction.INPUT
        }

        internal fun readTransform(n: SExpr): String =
            n.children.firstOrNull { it.args.isEmpty() && (it.name.startsWith("rotate", true) || it.name.startsWith("flip", true)) }
                ?.name?.lowercase() ?: ""
    }

    /** a point */
    data class Point(@JvmField val x: Int, @JvmField val y: Int) {
        /** adds an offset */
        fun add(dx: Int, dy: Int) = Point(x + dx, y + dy)

        override fun toString() = "($x,$y)"

        companion object {
            internal fun of(pt: SExpr) = Point(pt.getInt(0, 0), pt.getInt(1, 0))
        }
    }

    /** a rectangle */
    data class Rect(@JvmField val x1: Int, @JvmField val y1: Int, @JvmField val x2: Int, @JvmField val y2: Int) {
        /** the width */
        fun width() = Math.abs(x2 - x1)

        /** the height */
        fun height() = Math.abs(y2 - y1)

        companion object {
            internal fun of(n: SExpr?) =
                if (n == null) Rect(0, 0, 0, 0) else Rect(n.getInt(0, 0), n.getInt(1, 0), n.getInt(2, 0), n.getInt(3, 0))
        }
    }

    /** a text */
    class Text(
        @JvmField val text: String,
        @JvmField val rect: Rect,
        /** font size in points, 0 if not given */
        @JvmField val fontSize: Int = 0,
        @JvmField val font: String = "Arial",
        @JvmField val bold: Boolean = false,
        @JvmField val vertical: Boolean = false,
        @JvmField val invisible: Boolean = false
    ) {
        override fun toString() = text

        companion object {
            /** creates a text from a node */
            @JvmStatic
            fun of(n: SExpr): Text {
                val f = n["font"]
                return Text(
                    text = n.getString(0) ?: "",
                    rect = Rect.of(n["rect"]),
                    fontSize = f?.get("font_size")?.getInt(0, 0) ?: 0,
                    font = f?.getString(0) ?: "Arial",
                    bold = f?.has("bold") ?: false,
                    vertical = n.has("vertical"),
                    invisible = n.has("invisible")
                )
            }
        }
    }

    /** the kind of a graphic primitive */
    enum class GraphicType { LINE, RECTANGLE, CIRCLE, ARC, TEXT }

    /** a graphic primitive */
    class Graphic(
        @JvmField val type: GraphicType,
        @JvmField val p1: Point?,
        @JvmField val p2: Point?,
        @JvmField val rect: Rect?,
        @JvmField val lineWidth: Int,
        @JvmField val text: Text?
    ) {
        companion object {
            /** creates a graphic primitive from the given node or returns null */
            @JvmStatic
            fun create(n: SExpr): Graphic? {
                val lineWidth = n["line_width"]?.getInt(0, 1) ?: 1
                val pts = n.getChildren("pt")
                val p1 = pts.getOrNull(0)?.let { Point.of(it) }
                val p2 = pts.getOrNull(1)?.let { Point.of(it) }
                val r = n["rect"]?.let { Rect.of(it) }
                return when (n.name.lowercase()) {
                    "line" -> if (p1 == null || p2 == null) null else Graphic(GraphicType.LINE, p1, p2, r, lineWidth, null)
                    "rectangle" -> if (r == null) null else Graphic(GraphicType.RECTANGLE, p1, p2, r, lineWidth, null)
                    "circle" -> if (r == null) null else Graphic(GraphicType.CIRCLE, p1, p2, r, lineWidth, null)
                    "arc" -> if (r == null || p1 == null || p2 == null) null else Graphic(GraphicType.ARC, p1, p2, r, lineWidth, null)
                    "text" -> Graphic(GraphicType.TEXT, null, null, r, lineWidth, Text.of(n))
                    else -> null
                }
            }

            internal fun createAll(drawing: SExpr?): List<Graphic> = drawing?.children?.mapNotNull { create(it) } ?: emptyList()

            /** creates a line */
            @JvmStatic
            fun line(x1: Int, y1: Int, x2: Int, y2: Int, width: Int = 1) =
                Graphic(GraphicType.LINE, Point(x1, y1), Point(x2, y2), null, width, null)
        }
    }

    /** the direction of a port */
    enum class Direction { INPUT, OUTPUT, BIDIR }

    /** a port of a symbol */
    class Port(
        /** the connection point relative to the symbol */
        @JvmField val pt: Point,
        @JvmField val direction: Direction,
        /** the name of the port */
        @JvmField val name: Text?,
        /** the text drawn inside the symbol */
        @JvmField val label: Text?,
        /** the line connecting the port to the symbol body */
        @JvmField val line: Graphic?
    ) {
        /** the name of the port */
        fun getName(): String = name?.text?.trim() ?: ""

        /** true if the port is drawn as a bus */
        val isBusLine: Boolean get() = (line?.lineWidth ?: 1) >= 3

        companion object {
            internal fun of(n: SExpr): Port {
                val texts = n.getChildren("text").map { Text.of(it) }
                return Port(
                    n["pt"]?.let { Point.of(it) } ?: Point(0, 0),
                    readDirection(n),
                    texts.getOrNull(0),
                    texts.getOrNull(1) ?: texts.getOrNull(0),
                    n["line"]?.let { Graphic.create(it) })
            }
        }
    }

    /** a symbol instance in a block design file */
    class Symbol(
        /** the bounding rectangle in absolute coordinates */
        @JvmField val rect: Rect,
        /** the name of the symbol type */
        @JvmField val type: Text?,
        /** the name of the instance */
        @JvmField val instance: Text?,
        @JvmField val ports: List<Port>,
        @JvmField val drawing: List<Graphic>,
        /** the parameters, keys in upper case */
        @JvmField val parameters: Map<String, String>,
        /** the transformation flag like rotate90 or flipx */
        @JvmField val transform: String,
        /** true if this is a block (created with the block tool) */
        @JvmField val isBlock: Boolean
    ) {
        /** the name of the symbol type */
        fun getTypeName(): String = type?.text?.trim() ?: ""

        /** the name of the instance */
        fun getInstanceName(): String = instance?.text?.trim() ?: ""

        /** the absolute position of the given port */
        fun getAbsolutePos(p: Port) = Point(rect.x1 + p.pt.x, rect.y1 + p.pt.y)

        companion object {
            internal fun of(n: SExpr): Symbol {
                val isBlock = n.name.equals("block", true)
                val rect = Rect.of(n["rect"])
                val texts = n.getChildren("text").map { Text.of(it) }
                val drawing = ArrayList(Graphic.createAll(n["drawing"]))
                if (isBlock && n.has("rect"))
                    drawing.add(Graphic(GraphicType.RECTANGLE, null, null, Rect(0, 0, rect.width(), rect.height()), 1, null))
                val params = LinkedHashMap<String, String>()
                val paramNodes = n.getChildren("parameter") + (n["annotation_block"]?.getChildren("parameter") ?: emptyList())
                for (p in paramNodes) {
                    val pn = p.getString(0)
                    val pv = p.getString(1)
                    if (pn != null && pv != null) params[pn.uppercase()] = pv
                }
                return Symbol(rect, texts.getOrNull(0), texts.getOrNull(1), n.getChildren("port").map { Port.of(it) },
                    drawing, params, readTransform(n), isBlock)
            }
        }
    }

    /** an input, output or bidir pin of a block design file */
    class Pin(
        @JvmField val direction: Direction,
        /** the bounding rectangle in absolute coordinates */
        @JvmField val rect: Rect,
        /** the pin type text (INPUT, OUTPUT, BIDIR) */
        @JvmField val typeText: Text?,
        /** the pin name */
        @JvmField val name: Text?,
        /** the default value text (VCC or GND), may be null */
        @JvmField val defaultValue: Text?,
        /** the connection point relative to the rectangle */
        @JvmField val pt: Point,
        @JvmField val drawing: List<Graphic>,
        @JvmField val transform: String,
        /** the box showing the pin location in absolute coordinates, may be null */
        @JvmField val locationBox: Rect? = null
    ) {
        /** the pins name */
        fun getName(): String = name?.text?.trim() ?: ""

        /** the absolute connection point */
        fun getAbsolutePos() = Point(rect.x1 + pt.x, rect.y1 + pt.y)

        /** true if the default value of this pin is VCC */
        val isDefaultVCC: Boolean get() = defaultValue?.text?.trim().equals("VCC", true)

        companion object {
            internal fun of(n: SExpr): Pin {
                val texts = n.getChildren("text").map { Text.of(it) }
                return Pin(readDirection(n), Rect.of(n["rect"]), texts.getOrNull(0), texts.getOrNull(1), texts.getOrNull(2),
                    n["pt"]?.let { Point.of(it) } ?: Point(0, 0), Graphic.createAll(n["drawing"]), readTransform(n),
                    n.getChildren("annotation_block").firstOrNull { it.has("location") }?.let { Rect.of(it["rect"]) })
            }
        }
    }

    /** a connector (wire or bus segment) */
    class Connector(
        @JvmField val p1: Point,
        @JvmField val p2: Point,
        /** the label, may be null */
        @JvmField val label: Text?,
        @JvmField val bus: Boolean,
        @JvmField val conduit: Boolean
    ) {
        /** the label text or null */
        fun getLabelText(): String? = label?.text?.trim()

        companion object {
            internal fun of(n: SExpr): Connector {
                val pts = n.getChildren("pt").map { Point.of(it) }
                val p1 = pts.getOrElse(0) { Point(0, 0) }
                val label = n["text"]?.let { Text.of(it) }?.takeIf { it.text.isNotBlank() }
                return Connector(p1, pts.getOrElse(1) { p1 }, label, n.has("bus"), n.has("conduit"))
            }
        }
    }
}
