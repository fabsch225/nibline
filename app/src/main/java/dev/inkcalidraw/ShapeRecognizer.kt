package dev.inkcalidraw

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * "Hold at the end of a stroke to make it a shape": recognises a hand-drawn line, triangle, rectangle (also rotated:
 * any quadrilateral), diamond or ellipse, and returns the clean geometry. Conservative on purpose: a stroke that
 * isn't close to one of these stays a stroke.
 */
object ShapeRecognizer {
    sealed class Result {
        data class Line(val ax: Double, val ay: Double, val bx: Double, val by: Double) : Result()
        data class Box(val kind: ShapeKind, val x: Double, val y: Double, val w: Double, val h: Double) : Result()
        /** A closed polygon through these corners (triangles, rotated rectangles, other quadrilaterals). */
        data class Polygon(val corners: List<DoubleArray>) : Result()
    }

    /** [pts] = x, y in scene units. */
    fun recognize(pts: List<DoubleArray>): Result? {
        if (pts.size < 3) return null
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        var length = 0.0
        for ((i, p) in pts.withIndex()) {
            x0 = min(x0, p[0]); y0 = min(y0, p[1]); x1 = max(x1, p[0]); y1 = max(y1, p[1])
            if (i > 0) length += hypot(p[0] - pts[i - 1][0], p[1] - pts[i - 1][1])
        }
        val diag = hypot(x1 - x0, y1 - y0)
        if (diag < 8 || length < 10) return null
        val first = pts.first(); val last = pts.last()
        val chord = hypot(last[0] - first[0], last[1] - first[1])

        // a line: every point close to the chord, and the pen didn't double back
        if (chord > 0.8 * length) {
            val dev = pts.maxOf { Geometry.pointSegDist(it[0], it[1], first[0], first[1], last[0], last[1]) }
            if (dev < max(2.0, chord * 0.06)) {
                val e = Shapes.snapAngle(first[0], first[1], last[0], last[1], tolDeg = 6.0)
                return Result.Line(first[0], first[1], e[0], e[1])
            }
            return null
        }

        // everything else is closed: the stroke ends near where it began
        if (chord > max(0.22 * diag, 6.0) || length < 1.7 * diag) return null
        val closed = pts + listOf(first)
        val corners = corners(closed, diag)
        val w = x1 - x0; val h = y1 - y0

        if (corners.size == 3) return Result.Polygon(corners)
        if (corners.size == 4) {
            val near = 0.2 * diag
            fun nearAll(targets: List<DoubleArray>) = corners.all { c -> targets.any { t -> hypot(c[0] - t[0], c[1] - t[1]) < near } }
            if (nearAll(listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y0), doubleArrayOf(x1, y1), doubleArrayOf(x0, y1))))
                return Result.Box(ShapeKind.RECTANGLE, x0, y0, w, h)
            if (nearAll(listOf(doubleArrayOf((x0 + x1) / 2, y0), doubleArrayOf(x1, (y0 + y1) / 2), doubleArrayOf((x0 + x1) / 2, y1), doubleArrayOf(x0, (y0 + y1) / 2))))
                return Result.Box(ShapeKind.DIAMOND, x0, y0, w, h)
            if (isConvex(corners)) return Result.Polygon(corners)
        }
        // an ellipse: points at an even "radius" in the bounding box's ellipse
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val rx = w / 2; val ry = h / 2
        if (rx < 3 || ry < 3) return null
        val err = pts.sumOf { abs(hypot((it[0] - cx) / rx, (it[1] - cy) / ry) - 1) } / pts.size
        if (err < 0.09 && corners.size !in 3..4) {
            // nearly round: make it a circle
            if (max(rx, ry) / min(rx, ry) < 1.12) { val r = (rx + ry) / 2; return Result.Box(ShapeKind.ELLIPSE, cx - r, cy - r, 2 * r, 2 * r) }
            return Result.Box(ShapeKind.ELLIPSE, x0, y0, w, h)
        }
        return null
    }

    /** Corners of a closed stroke: Ramer–Douglas–Peucker, then merge the seam where the stroke started and ended. */
    fun corners(closed: List<DoubleArray>, diag: Double): List<DoubleArray> {
        // split the loop at the point farthest from the start so RDP keeps both "halves"
        val s = closed.first()
        val far = closed.indices.maxBy { hypot(closed[it][0] - s[0], closed[it][1] - s[1]) }
        val eps = 0.055 * diag
        val a = rdp(closed.subList(0, far + 1), eps); val b = rdp(closed.subList(far, closed.size), eps)
        val ring = (a + b.drop(1)).dropLast(1).toMutableList() // last == first
        // drop corners where the outline barely turns (incl. the start point on a straight side)
        var changed = true
        while (changed && ring.size > 3) {
            changed = false
            for (i in ring.indices) {
                val p = ring[(i - 1 + ring.size) % ring.size]; val c = ring[i]; val n = ring[(i + 1) % ring.size]
                val d = Geometry.pointSegDist(c[0], c[1], p[0], p[1], n[0], n[1])
                val short = hypot(c[0] - p[0], c[1] - p[1]) < 0.12 * diag
                if (d < eps * 1.2 || short) { ring.removeAt(i); changed = true; break }
            }
        }
        return ring
    }

    private fun rdp(p: List<DoubleArray>, eps: Double): List<DoubleArray> {
        if (p.size < 3) return p.toList()
        val a = p.first(); val b = p.last()
        var idx = -1; var best = 0.0
        for (i in 1 until p.size - 1) {
            val d = Geometry.pointSegDist(p[i][0], p[i][1], a[0], a[1], b[0], b[1])
            if (d > best) { best = d; idx = i }
        }
        if (best <= eps) return listOf(a, b)
        return rdp(p.subList(0, idx + 1), eps).dropLast(1) + rdp(p.subList(idx, p.size), eps)
    }

    private fun isConvex(c: List<DoubleArray>): Boolean {
        var sign = 0
        for (i in c.indices) {
            val a = c[i]; val b = c[(i + 1) % c.size]; val d = c[(i + 2) % c.size]
            val z = (b[0] - a[0]) * (d[1] - b[1]) - (b[1] - a[1]) * (d[0] - b[0])
            val s = if (z > 0) 1 else if (z < 0) -1 else 0
            if (s != 0) { if (sign != 0 && s != sign) return false; sign = s }
        }
        return true
    }
}
