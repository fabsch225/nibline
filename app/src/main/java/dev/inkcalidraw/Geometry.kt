package dev.inkcalidraw

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Small, allocation-light geometry helpers (pure Kotlin so they're unit-testable on the JVM). */
object Geometry {
    /** Axis-aligned box: [minX, minY, maxX, maxY]. */
    class Box(var minX: Double, var minY: Double, var maxX: Double, var maxY: Double) {
        fun intersects(o: Box) = minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
        fun inflate(r: Double) = Box(minX - r, minY - r, maxX + r, maxY + r)
        fun contains(x: Double, y: Double) = x in minX..maxX && y in minY..maxY
        fun union(o: Box) = Box(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))
        val width get() = maxX - minX
        val height get() = maxY - minY
        val centerX get() = (minX + maxX) / 2
        val centerY get() = (minY + maxY) / 2
        override fun toString() = "Box($minX, $minY, $maxX, $maxY)"
    }

    fun pointSegDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun segmentsIntersect(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double,
    ): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    /** Minimum distance between segments AB and CD (0 if they cross). */
    fun segSegDist(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double,
    ): Double {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0.0
        return min(
            min(pointSegDist(ax, ay, cx, cy, dx, dy), pointSegDist(bx, by, cx, cy, dx, dy)),
            min(pointSegDist(cx, cy, ax, ay, bx, by), pointSegDist(dx, dy, ax, ay, bx, by)),
        )
    }

    fun pointInPolygon(x: Double, y: Double, poly: List<DoubleArray>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i][0]; val yi = poly[i][1]
            val xj = poly[j][0]; val yj = poly[j][1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Scene-space bounding box of an element (cached on the element). */
    fun bounds(el: Element): Box = el.bounds

    /** Rotation ignored; good enough for hit tests and culling. */
    fun computeBounds(el: Element): Box {
        if (el.type == "freedraw" || el.type == "line" || el.type == "arrow") {
            val pts = el.points
            if (pts.isNotEmpty()) {
                var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
                for (p in pts) { x0 = min(x0, p[0]); y0 = min(y0, p[1]); x1 = max(x1, p[0]); y1 = max(y1, p[1]) }
                val pad = if (el.type == "freedraw") el.strokeWidth * 4.25 else el.strokeWidth
                return Box(el.x + x0 - pad, el.y + y0 - pad, el.x + x1 + pad, el.y + y1 + pad)
            }
        }
        return Box(el.x, el.y, el.x + el.width, el.y + el.height)
    }

    fun contentBounds(elements: List<Element>, includeFrames: Boolean = true): Box? {
        var r: Box? = null
        for (e in elements) {
            if (e.isDeleted || (!includeFrames && e.type == "frame")) continue
            val b = bounds(e)
            r = r?.union(b) ?: Box(b.minX, b.minY, b.maxX, b.maxY)
        }
        return r
    }

    /**
     * Does an eraser that swept from (ax,ay) to (bx,by) with radius r touch this element?
     * Sweeping (instead of sampling only event positions) is what makes fast eraser strokes reliable.
     */
    fun eraserHits(el: Element, ax: Double, ay: Double, bx: Double, by: Double, r: Double): Boolean {
        val sweep = Box(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by)).inflate(r)
        if (!bounds(el).intersects(sweep)) return false
        return when (el.type) {
            "freedraw", "line", "arrow", "rectangle", "diamond", "ellipse" -> {
                if (el.type != "freedraw" && el.type != "line" && el.type != "arrow" && el.backgroundColor != "transparent") return true
                val pts = if (el.type == "freedraw" || el.type == "line" || el.type == "arrow") el.points else Shapes.outline(el)
                val reach = r + (if (el.type == "freedraw") el.strokeWidth * 2.0 else el.strokeWidth / 2)
                val lax = ax - el.x; val lay = ay - el.y; val lbx = bx - el.x; val lby = by - el.y
                if (pts.size == 1) return pointSegDist(pts[0][0], pts[0][1], lax, lay, lbx, lby) <= reach
                for (k in 0 until pts.size - 1) {
                    val p = pts[k]; val q = pts[k + 1]
                    if (segSegDist(p[0], p[1], q[0], q[1], lax, lay, lbx, lby) <= reach) return true
                }
                false
            }
            else -> true // box overlap is the hit test for shapes, text and images
        }
    }
}
