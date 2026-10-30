package dev.inkcalidraw

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pencil and chalk: pressure controls darkness/coverage as well as width.
 *
 * The stroke keeps exactly the perfect-freehand outline Excalidraw draws (used as a clip, so the silhouette, hit
 * tests and the file are unchanged). Inside it, the stroke is shaded in bands along its centerline, one band per
 * pair of stroke points, with an alpha from the pen pressure there. Bands with the same (quantized) alpha share one
 * Path, so overlapping bands at tight bends don't double up, and they're filled without anti-aliasing, so
 * neighbouring bands tile without seams; the anti-aliased clip gives the smooth edge.
 *
 * The same code draws the stroke while it's being drawn (front buffer) and once it's committed, so lifting the pen
 * changes nothing on screen.
 */
object GrainStroke {
    const val LEVELS = 16

    /** How strongly pressure shows: alpha factor for pressure p (0..1). */
    fun pressureFactor(brush: String, p: Double): Double = when (brush) {
        "chalk" -> 0.3 + 0.7 * p     // light touch leaves patchy, faint chalk
        else -> 0.18 + 0.82 * p      // pencil: from a faint line to full graphite
    }.coerceIn(0.0, 1.0)

    fun isGrainBrush(brush: String?) = brush == "pencil" || brush == "chalk"

    /** Band paths indexed by alpha level (null = level unused), from perfect-freehand stroke points. */
    fun bands(points: List<PerfectFreehand.StrokePoint>, o: PerfectFreehand.Options, brush: String): Array<Path?> {
        val out = arrayOfNulls<Path>(LEVELS)
        val n = points.size
        if (n == 0) return out
        fun level(p: Double) = (pressureFactor(brush, p) * (LEVELS - 1)).roundToInt().coerceIn(0, LEVELS - 1)
        fun path(l: Int) = out[l] ?: Path().also { it.fillType = Path.FillType.WINDING; out[l] = it }
        val cx = DoubleArray(n) { points[it].point[0] }
        val cy = DoubleArray(n) { points[it].point[1] }
        // reach past the outline everywhere (the clip trims it): local radius plus margin
        val r = DoubleArray(n) { PerfectFreehand.strokeRadius(o.size, o.thinning, points[it].pressure, o.easing) * 1.6 + 1.0 }
        if (n == 1) {
            val rr = r[0] * 2
            path(level(points[0].pressure)).addRect((cx[0] - rr).toFloat(), (cy[0] - rr).toFloat(), (cx[0] + rr).toFloat(), (cy[0] + rr).toFloat(), Path.Direction.CW)
            return out
        }
        // unit normals from central differences (shared by both bands meeting at a point: no gaps, no overlap)
        val nx = DoubleArray(n); val ny = DoubleArray(n)
        val dx = DoubleArray(n); val dy = DoubleArray(n)
        for (i in 0 until n) {
            val a = maxOf(0, i - 1); val b = minOf(n - 1, i + 1)
            var tx = cx[b] - cx[a]; var ty = cy[b] - cy[a]
            val len = hypot(tx, ty)
            if (len < 1e-9) { tx = 1.0; ty = 0.0 } else { tx /= len; ty /= len }
            dx[i] = tx; dy[i] = ty; nx[i] = -ty; ny[i] = tx
        }
        fun quad(p: Path, ax: Double, ay: Double, bx: Double, by: Double, cxx: Double, cyy: Double, ddx: Double, ddy: Double) {
            p.moveTo(ax.toFloat(), ay.toFloat()); p.lineTo(bx.toFloat(), by.toFloat()); p.lineTo(cxx.toFloat(), cyy.toFloat()); p.lineTo(ddx.toFloat(), ddy.toFloat()); p.close()
        }
        for (i in 0 until n - 1) {
            val p = path(level((points[i].pressure + points[i + 1].pressure) / 2))
            quad(p,
                cx[i] + nx[i] * r[i], cy[i] + ny[i] * r[i],
                cx[i + 1] + nx[i + 1] * r[i + 1], cy[i + 1] + ny[i + 1] * r[i + 1],
                cx[i + 1] - nx[i + 1] * r[i + 1], cy[i + 1] - ny[i + 1] * r[i + 1],
                cx[i] - nx[i] * r[i], cy[i] - ny[i] * r[i])
        }
        // round caps beyond the first and last point
        for ((i, s) in listOf(0 to -1.0, n - 1 to 1.0)) {
            val p = path(level(points[i].pressure))
            val ex = dx[i] * r[i] * s; val ey = dy[i] * r[i] * s
            quad(p,
                cx[i] + nx[i] * r[i], cy[i] + ny[i] * r[i],
                cx[i] + nx[i] * r[i] + ex, cy[i] + ny[i] * r[i] + ey,
                cx[i] - nx[i] * r[i] + ex, cy[i] - ny[i] * r[i] + ey,
                cx[i] - nx[i] * r[i], cy[i] - ny[i] * r[i])
        }
        return out
    }

    /** Draws the bands clipped to [outline] with [paint] (its alpha is the stroke's opacity). */
    fun draw(canvas: Canvas, outline: Path, bands: Array<Path?>, paint: Paint) {
        val base = paint.alpha
        val aa = paint.isAntiAlias
        canvas.save()
        canvas.clipPath(outline)
        paint.isAntiAlias = false
        for (l in bands.indices) {
            val b = bands[l] ?: continue
            paint.alpha = (base * l / (LEVELS - 1f)).roundToInt().coerceIn(0, 255)
            canvas.drawPath(b, paint)
        }
        canvas.restore()
        paint.alpha = base
        paint.isAntiAlias = aa
    }
}
