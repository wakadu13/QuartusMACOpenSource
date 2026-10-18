/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.element.ElementAttributes
import de.neemann.digital.core.element.Keys
import de.neemann.digital.draw.elements.VisualElement
import de.neemann.digital.draw.graphics.Orientation
import de.neemann.digital.draw.graphics.Polygon
import de.neemann.digital.draw.graphics.Vector
import de.neemann.digital.draw.shapes.GenericShape
import de.neemann.digital.draw.shapes.custom.CustomShapeDescription
import java.awt.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Converts Quartus drawings to a [CustomShapeDescription].
 * The Quartus coordinates are scaled by 2.5, so that the Quartus grid of 8 units
 * maps to the Digital grid of 20 units.
 *
 * @param pos the position of the Digital element in Digital coordinates
 */
class ShapeBuilder(private val pos: Vector) {
    private val builder = CustomShapeDescription.Builder()

    /** true if nothing is drawn */
    var isEmpty = true
        private set

    companion object {
        /** prefix of input pins */
        const val IN = "i:"

        /** prefix of output pins */
        const val OUT = "o:"

        /** name of the pseudo pin which marks the position used to show a value */
        const val VALUE_POS = "v:value"

        private val COLOR: Color = Color.BLACK
        private const val THIN = 3
        private const val BUS = 8
        private const val ARC_SEGMENTS = 24

        /** scales a Quartus coordinate to a Digital coordinate */
        @JvmStatic
        fun scale(q: Int): Int = Math.round(q * 2.5).toInt()

        /** scales a Quartus point to a Digital vector */
        @JvmStatic
        fun scale(p: BdfFile.Point) = Vector(scale(p.x), scale(p.y))

        /** scales a Digital coordinate to a Quartus coordinate */
        @JvmStatic
        fun unscale(d: Int): Int = (d / 2.5).roundToInt()

        /**
         * Creates a visual element at the given position.
         * If the position is not on the grid, the snap to grid is disabled.
         */
        @JvmStatic
        @JvmOverloads
        fun createElement(name: String, pos: Vector, attr: ElementAttributes? = null): VisualElement {
            val ve = VisualElement(name)
            if (attr != null) ve.elementAttributes.getValuesFrom(attr)
            if (pos.x % GenericShape.SIZE != 0 || pos.y % GenericShape.SIZE != 0)
                ve.setAttribute(Keys.SNAP_TO_GRID, false)
            return ve.setPos(pos)
        }

        /** escapes the characters which are interpreted by the Digital text formatter */
        @JvmStatic
        fun escape(text: String): String = text.replace("\\", "\\\\").replace("_", "\\_").replace("^", "\\^")

        private fun fontSize(t: BdfFile.Text, rectHeight: Int): Int {
            val pt = if (t.fontSize > 0) t.fontSize else 8
            val fromPt = (pt * 3.2).roundToInt()
            if (rectHeight > 0) return maxOf(10, minOf(fromPt, (rectHeight * 2.5 * 0.85).roundToInt()))
            return fromPt
        }
    }

    private fun v(qx: Double, qy: Double) = Vector((qx * 2.5).roundToInt() - pos.x, (qy * 2.5).roundToInt() - pos.y)
    private fun v(qx: Int, qy: Int) = v(qx.toDouble(), qy.toDouble())

    /** adds a list of graphics with the given offset in Quartus coordinates */
    fun add(graphics: List<BdfFile.Graphic>, ox: Int, oy: Int): ShapeBuilder {
        graphics.forEach { add(it, ox, oy) }
        return this
    }

    /** adds a graphic with the given offset in Quartus coordinates */
    fun add(g: BdfFile.Graphic, ox: Int, oy: Int): ShapeBuilder {
        val th = if (g.lineWidth >= 2) BUS else THIN
        when (g.type) {
            BdfFile.GraphicType.LINE -> builder.addLine(v(g.p1!!.x + ox, g.p1.y + oy), v(g.p2!!.x + ox, g.p2.y + oy), th, COLOR)
            BdfFile.GraphicType.RECTANGLE -> {
                val r = g.rect!!
                builder.addPolygon(Polygon(true)
                    .add(v(r.x1 + ox, r.y1 + oy)).add(v(r.x2 + ox, r.y1 + oy))
                    .add(v(r.x2 + ox, r.y2 + oy)).add(v(r.x1 + ox, r.y2 + oy)), th, COLOR, false)
            }
            BdfFile.GraphicType.CIRCLE -> {
                val r = g.rect!!
                builder.addCircle(v(r.x1 + ox, r.y1 + oy), v(r.x2 + ox, r.y2 + oy), th, COLOR, false)
            }
            BdfFile.GraphicType.ARC -> addArc(g, ox, oy, th)
            BdfFile.GraphicType.TEXT -> addText(g.text, ox, oy)
        }
        isEmpty = false
        return this
    }

    private fun addArc(g: BdfFile.Graphic, ox: Int, oy: Int, th: Int) {
        val r = g.rect!!
        val cx = (r.x1 + r.x2) / 2.0
        val cy = (r.y1 + r.y2) / 2.0
        val rx = abs(r.x2 - r.x1) / 2.0
        val ry = abs(r.y2 - r.y1) / 2.0
        if (rx == 0.0 || ry == 0.0) return
        // Quartus draws arcs counter clockwise (as seen on the screen) from p1 to p2
        val a1 = atan2((g.p1!!.y - cy) / ry, (g.p1.x - cx) / rx)
        var a2 = atan2((g.p2!!.y - cy) / ry, (g.p2.x - cx) / rx)
        while (a2 >= a1) a2 -= 2 * Math.PI
        val p = Polygon(false)
        for (i in 0..ARC_SEGMENTS) {
            val a = a1 + (a2 - a1) * i / ARC_SEGMENTS
            p.add(v(cx + rx * cos(a) + ox, cy + ry * sin(a) + oy))
        }
        builder.addPolygon(p, th, COLOR, false)
    }

    /** adds a text with the given offset in Quartus coordinates */
    fun addText(t: BdfFile.Text?, ox: Int, oy: Int): ShapeBuilder {
        if (t == null || t.invisible || t.text.isBlank()) return this
        val r = t.rect
        val size = fontSize(t, if (t.vertical) r.width() else r.height())
        if (t.vertical) {
            val p = v((r.x1 + r.x2) / 2.0 + ox, (r.y2 + oy).toDouble())
            builder.addText(p, p.add(0, -1), escape(t.text), Orientation.LEFTCENTER, size, COLOR)
        } else {
            val p = v((r.x1 + ox).toDouble(), (r.y1 + r.y2) / 2.0 + oy)
            builder.addText(p, p.add(1, 0), escape(t.text), Orientation.LEFTCENTER, size, COLOR)
        }
        isEmpty = false
        return this
    }

    /** adds a line given in absolute Quartus coordinates */
    fun line(x1: Int, y1: Int, x2: Int, y2: Int, bus: Boolean): ShapeBuilder {
        builder.addLine(v(x1, y1), v(x2, y2), if (bus) BUS else THIN, COLOR)
        isEmpty = false
        return this
    }

    /** adds a closed polygon given in absolute Quartus coordinates x1, y1, x2, y2, ... */
    fun polygon(vararg xy: Int): ShapeBuilder {
        val p = Polygon(true)
        for (i in 0 until xy.size - 1 step 2) p.add(v(xy[i], xy[i + 1]))
        builder.addPolygon(p, THIN, COLOR, false)
        isEmpty = false
        return this
    }

    /** adds a text given in absolute Quartus coordinates */
    fun text(text: String, x: Int, y: Int, pt: Int, orientation: Orientation): ShapeBuilder {
        val p = v(x, y)
        builder.addText(p, p.add(1, 0), escape(text), orientation, (pt * 3.2).roundToInt(), COLOR)
        isEmpty = false
        return this
    }

    /** adds a pin given in absolute Quartus coordinates, the name includes the prefix */
    fun addPin(name: String, qx: Int, qy: Int): ShapeBuilder {
        builder.addPin(name, v(qx, qy), false)
        return this
    }

    /** adds a pin at the given absolute position in Digital coordinates */
    fun addPinDigital(name: String, abs: Vector): ShapeBuilder {
        builder.addPin(name, abs.sub(pos), false)
        return this
    }

    /**
     * Adds a symbol which is located at (qx,qy) in Quartus coordinates.
     *
     * @param symbol the symbol
     * @param key    maps a port to the key of the Digital pin, if null is returned the pin is not added
     */
    fun addSymbol(symbol: QSymbol, qx: Int, qy: Int, key: (QSymbol.QPort) -> String?): ShapeBuilder {
        add(symbol.graphics, qx, qy)
        for (p in symbol.ports) {
            val k = key(p) ?: continue
            p.line?.let { add(it, qx, qy) }
            p.label?.let { addText(it, qx, qy) }
            addPin(k, qx + p.pt.x, qy + p.pt.y)
        }
        symbol.valuePos?.let { addPin(VALUE_POS, qx + it.x, qy + it.y) }
        return this
    }

    /** sets the position of the label (left top) given in absolute Quartus coordinates */
    fun setLabel(qx: Int, qy: Int): ShapeBuilder {
        val p = v(qx, qy)
        builder.setLabel(p, p.add(1, 0), Orientation.LEFTTOP, 24, COLOR)
        return this
    }

    /** returns the shape description */
    fun build(): CustomShapeDescription = builder.build()
}
