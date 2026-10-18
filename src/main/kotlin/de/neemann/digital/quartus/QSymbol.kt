/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.quartus.BdfFile.Direction
import de.neemann.digital.quartus.BdfFile.Graphic
import de.neemann.digital.quartus.BdfFile.GraphicType
import de.neemann.digital.quartus.BdfFile.Point
import de.neemann.digital.quartus.BdfFile.Rect
import de.neemann.digital.quartus.BdfFile.Text

/**
 * A symbol described in Quartus coordinates relative to its upper left corner.
 * It is used to draw the Quartus components in Digital and to write them to a block design file.
 *
 * @param name   the name of the symbol type
 * @param width  the width
 * @param height the height
 */
class QSymbol(val name: String, var width: Int, var height: Int) {
    /** the drawing */
    val graphics = ArrayList<Graphic>()

    /** the ports */
    val ports = ArrayList<QPort>()

    /** the position used to show the value of a pin, maybe null */
    var valuePos: Point? = null

    /** the text showing the symbol name */
    var typeText: Text? = null

    /** the text showing the instance name */
    var instanceText: Text? = null

    /** a port of the symbol */
    class QPort(
        val name: String,
        val direction: Direction,
        val pt: Point,
        val line: Graphic?,
        val label: Text?
    )

    /** adds a line */
    fun line(x1: Int, y1: Int, x2: Int, y2: Int, width: Int = 1) = apply { graphics.add(Graphic.line(x1, y1, x2, y2, width)) }

    /** adds a rectangle */
    fun rect(x1: Int, y1: Int, x2: Int, y2: Int) =
        apply { graphics.add(Graphic(GraphicType.RECTANGLE, null, null, Rect(x1, y1, x2, y2), 1, null)) }

    /** adds a circle */
    fun circle(cx: Int, cy: Int, r: Int) =
        apply { graphics.add(Graphic(GraphicType.CIRCLE, null, null, Rect(cx - r, cy - r, cx + r, cy + r), 1, null)) }

    /** adds an arc which is drawn counter clockwise from p1 to p2 */
    fun arc(x1: Int, y1: Int, x2: Int, y2: Int, r: Rect) =
        apply { graphics.add(Graphic(GraphicType.ARC, Point(x1, y1), Point(x2, y2), r, 1, null)) }

    /** adds a quadratic bezier curve approximated by lines */
    fun curve(x1: Int, y1: Int, cx: Int, cy: Int, x2: Int, y2: Int, segments: Int = 6) = apply {
        var px = x1
        var py = y1
        for (i in 1..segments) {
            val t = i.toDouble() / segments
            val u = 1 - t
            val nx = Math.round(u * u * x1 + 2 * u * t * cx + t * t * x2).toInt()
            val ny = Math.round(u * u * y1 + 2 * u * t * cy + t * t * y2).toInt()
            line(px, py, nx, ny)
            px = nx
            py = ny
        }
    }

    /** adds a text, (x,y) is the left center of the text */
    fun text(t: String, x: Int, y: Int, pt: Int = 8, bold: Boolean = false) =
        apply { graphics.add(Graphic(GraphicType.TEXT, null, null, null, 1, createText(t, x, y, pt, bold))) }

    /** adds a port */
    fun port(name: String, dir: Direction, x: Int, y: Int, lx: Int, ly: Int, bus: Boolean = false, label: Text? = null) =
        apply { ports.add(QPort(name, dir, Point(x, y), Graphic.line(x, y, lx, ly, if (bus) 3 else 1), label)) }

    /** returns the port with the given name */
    fun getPort(name: String): QPort? = ports.firstOrNull { it.name.equals(name, ignoreCase = true) }

    companion object {
        /** creates a text whose left center is at (x,y) */
        @JvmStatic
        fun createText(t: String, x: Int, y: Int, pt: Int = 8, bold: Boolean = false): Text {
            val h = if (pt <= 6) 10 else if (pt <= 8) 12 else 16
            val w = (t.length * pt * 0.75).toInt() + 2
            return Text(t, Rect(x, y - h / 2, x + w, y + h - h / 2), pt, "Arial", bold)
        }
    }
}
