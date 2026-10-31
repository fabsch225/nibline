package dev.inkcalidraw

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pencil and chalk, drawn like the real thing: as many small grains of graphite/chalk scattered along the stroke.
 *
 * The file keeps an ordinary freedraw line (Excalidraw shows it as a solid stroke); only the rendering differs.
 * Grains are scattered along perfect-freehand's stroke points (the same centre line and width Excalidraw uses):
 * denser in the middle of the stroke and sparse at its edges, darker with more pressure, and thinned out by a paper
 * texture fixed to the page, so where the paper's tooth is low, no stroke leaves grain.
 *
 * Every segment's grains come from a seed made of that segment's own points, so they're the same each time: while
 * drawing, only the segments near the pen tip are (re)drawn, and the stroke looks identical once it's committed.
 */
object GrainStroke {
    const val LEVELS = 8

    fun isGrainBrush(brush: String?) = brush == "pencil" || brush == "chalk"

    /** How strongly pressure shows: darkness factor for pressure p (0..1). */
    fun pressureFactor(brush: String, p: Double): Double = when (brush) {
        "chalk" -> 0.35 + 0.65 * p
        else -> 0.22 + 0.78 * p
    }.coerceIn(0.0, 1.0)

    /** Grain size in scene units. */
    fun grainSize(brush: String, strokeWidth: Double): Float =
        if (brush == "chalk") (0.9 + 0.12 * strokeWidth).toFloat() else (0.5 + 0.06 * strokeWidth).toFloat()

    /** Grain positions (x, y pairs) grouped by darkness level; [size] = grain diameter. */
    class Grains(val levels: Array<FloatArray?>, val size: Float)

    private class Buf { var a = FloatArray(64); var n = 0
        fun add(x: Float, y: Float) { if (n + 2 > a.size) a = a.copyOf(a.size * 2); a[n++] = x; a[n++] = y } }

    /**
     * Grains of the stroke with these stroke points (in the element's own coordinates; [originX]/[originY] = the
     * element's position, for the paper texture). Only segments whose area touches [only] (same coordinates) when given.
     */
    fun grains(points: List<PerfectFreehand.StrokePoint>, o: PerfectFreehand.Options, brush: String, strokeWidth: Double,
               originX: Double, originY: Double, only: Geometry.Box? = null): Grains {
        val size = grainSize(brush, strokeWidth)
        val bufs = Array(LEVELS) { Buf() }
        val n = points.size
        if (n == 0) return Grains(arrayOfNulls(LEVELS), size)
        val noise = PenProfiles.noise(brush)
        val chalk = brush == "chalk"
        val cover = if (chalk) 0.55 else 0.85
        fun radius(i: Int) = PerfectFreehand.strokeRadius(o.size, o.thinning, points[i].pressure, o.easing)
        val area = (size * size).toDouble()

        fun emit(x: Double, y: Double, pressure: Double, edge: Double, rnd: Rng) {
            // paper texture (fixed to the page): low spots of the paper take no grain, high spots take more
            val k = if (chalk) 0.5 else 0.25
            val gx = Math.floorMod(((x + originX) / k).toInt(), PenProfiles.GRAIN_SIZE)
            val gy = Math.floorMod(((y + originY) / k).toInt(), PenProfiles.GRAIN_SIZE)
            val paper = noise[gy * PenProfiles.GRAIN_SIZE + gx]
            val floor = if (chalk) 0.32 + 0.25 * edge else 0.10 + 0.35 * edge  // the edge only catches the highest tooth
            if (paper < floor) return
            val tone = (if (chalk) 0.55 + 0.45 * paper else 0.45 + 0.55 * paper) * (0.75 + 0.25 * rnd.next()) * (1 - 0.45 * edge)
            val v = pressureFactor(brush, pressure) * tone
            val l = (v * (LEVELS - 1) + rnd.next() * 0.999).toInt().coerceIn(0, LEVELS - 1) // dithered quantisation
            if (l > 0) bufs[l].add(x.toFloat(), y.toFloat())
        }

        if (n == 1) {
            val p = points[0]; val r = radius(0)
            if (only != null && !Box(p.point[0] - r, p.point[1] - r, p.point[0] + r, p.point[1] + r).intersects(only)) return Grains(arrayOfNulls(LEVELS), size)
            val rnd = Rng(seed(p.point[0], p.point[1], p.point[0], p.point[1]))
            repeat(ceil(Math.PI * r * r / area * cover).toInt()) {
                val a = rnd.next() * 2 * Math.PI; val d = r * (rnd.next() + rnd.next()) / 2
                emit(p.point[0] + Math.cos(a) * d, p.point[1] + Math.sin(a) * d, p.pressure, d / r, rnd)
            }
            return pack(bufs, size)
        }
        for (i in 0 until n - 1) {
            val a = points[i]; val b = points[i + 1]
            val ax = a.point[0]; val ay = a.point[1]; val bx = b.point[0]; val by = b.point[1]
            val ra = radius(i); val rb = radius(i + 1)
            val rr = max(ra, rb) + size
            if (only != null && !Box(min(ax, bx) - rr, min(ay, by) - rr, max(ax, bx) + rr, max(ay, by) + rr).intersects(only)) continue
            val dx = bx - ax; val dy = by - ay
            val len = hypot(dx, dy)
            val rnd = Rng(seed(ax, ay, bx, by))
            val tx: Double; val ty: Double
            if (len < 1e-9) { tx = 1.0; ty = 0.0 } else { tx = dx / len; ty = dy / len }
            // a little past both ends, so bends don't leave gaps on their outer side; round ends on the first and last
            val ext = if (i == 0 || i == n - 2) rr else min(rr, len * 0.5)
            val count = ceil((len + ext) * 2 * rr / area * cover * 0.5).toInt()
            for (k in 0 until count) {
                val t = rnd.next() * (len + ext) - ext / 2
                val f = (t / max(len, 1e-9)).coerceIn(0.0, 1.0)
                val r = ra + (rb - ra) * f
                val u = (rnd.next() + rnd.next() - 1) // -1..1, most grains near the middle of the stroke
                val px = ax + tx * t - ty * u * r; val py = ay + ty * t + tx * u * r
                // round ends: skip grains beyond the end caps' circles
                if (t < 0 && hypot(px - ax, py - ay) > ra) continue
                if (t > len && hypot(px - bx, py - by) > rb) continue
                emit(px, py, a.pressure + (b.pressure - a.pressure) * f, kotlin.math.abs(u), rnd)
            }
        }
        return pack(bufs, size)
    }

    private fun pack(bufs: Array<Buf>, size: Float) = Grains(Array(LEVELS) { l -> bufs[l].takeIf { it.n > 0 }?.let { it.a.copyOf(it.n) } }, size)

    private class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        fun intersects(o: Geometry.Box) = minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
    }

    private fun seed(ax: Double, ay: Double, bx: Double, by: Double): Long {
        var h = 1469598103934665603L
        for (v in longArrayOf(Math.round(ax * 64), Math.round(ay * 64), Math.round(bx * 64), Math.round(by * 64))) { h = (h xor v) * 1099511628211L; h = h xor (h ushr 29) }
        return h
    }

    /** Small, fast, deterministic random numbers (xorshift). */
    private class Rng(seed: Long) {
        private var s = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed
        fun next(): Double { s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17); return (s ushr 11) * (1.0 / (1L shl 53)) }
    }

    /** Draws grains with [paint] (its colour and alpha = the stroke's colour and opacity). */
    fun draw(canvas: Canvas, g: Grains, paint: Paint) {
        val base = paint.alpha
        val style = paint.style; val cap = paint.strokeCap; val w = paint.strokeWidth
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = g.size
        for (l in 1 until LEVELS) {
            val pts = g.levels[l] ?: continue
            paint.alpha = (base * l / (LEVELS - 1f)).roundToInt().coerceIn(0, 255)
            canvas.drawPoints(pts, paint)
        }
        paint.alpha = base; paint.style = style; paint.strokeCap = cap; paint.strokeWidth = w
    }
}
