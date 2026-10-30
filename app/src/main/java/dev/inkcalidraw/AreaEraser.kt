package dev.inkcalidraw

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The area eraser removes only the part of a stroke it passes over: the stroke is cut where the eraser touched it
 * and the pieces on either side stay (with their own pressures), as separate freedraw elements.
 */
object AreaEraser {
    /**
     * Pieces of [el] left after an eraser of radius [r] swept from (ax, ay) to (bx, by) (scene units), or null if
     * it wasn't touched. An empty list means the whole stroke went.
     */
    fun cut(el: Element, ax: Double, ay: Double, bx: Double, by: Double, r: Double): List<Element>? {
        val pts = el.points
        if (pts.isEmpty()) return null
        val pr = el.pressures
        val reach = r + el.strokeWidth * 1.2 // a bit of the ink's own width: cut where the eraser covers the ink
        val lax = ax - el.x; val lay = ay - el.y; val lbx = bx - el.x; val lby = by - el.y
        // resample, so a long straight segment can be cut in the middle
        val step = (r * 0.5).coerceAtLeast(0.5)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ps = ArrayList<Double>()
        for (i in pts.indices) {
            val p = pts[i]; val pp = pr.getOrElse(i) { 0.5 }
            if (i > 0) {
                val q = pts[i - 1]; val qp = pr.getOrElse(i - 1) { 0.5 }
                val n = ceil(hypot(p[0] - q[0], p[1] - q[1]) / step).toInt()
                for (k in 1 until n) { val t = k.toDouble() / n; xs += q[0] + (p[0] - q[0]) * t; ys += q[1] + (p[1] - q[1]) * t; ps += qp + (pp - qp) * t }
            }
            xs += p[0]; ys += p[1]; ps += pp
        }
        val keep = BooleanArray(xs.size) { Geometry.pointSegDist(xs[it], ys[it], lax, lay, lbx, lby) > reach }
        if (keep.all { it }) return null
        val runs = ArrayList<IntRange>()
        var s = -1
        for (i in keep.indices) {
            if (keep[i] && s < 0) s = i
            if ((!keep[i] || i == keep.lastIndex) && s >= 0) { val e = if (keep[i]) i else i - 1; if (e > s) runs += s..e; s = -1 }
        }
        return runs.mapIndexed { k, run -> piece(el, run.map { doubleArrayOf(xs[it], ys[it], ps[it]) }, first = k == 0) }
    }

    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    private fun piece(el: Element, pts: List<DoubleArray>, first: Boolean): Element {
        val ox = el.x + pts[0][0]; val oy = el.y + pts[0][1]
        val rel = pts.map { doubleArrayOf(r2(it[0] - pts[0][0]), r2(it[1] - pts[0][1])) }
        val w = rel.maxOf { it[0] } - rel.minOf { it[0] }; val h = rel.maxOf { it[1] } - rel.minOf { it[1] }
        val changes = mutableListOf(
            "x" to JsonPrimitive(r2(ox)), "y" to JsonPrimitive(r2(oy)), "width" to JsonPrimitive(r2(w)), "height" to JsonPrimitive(r2(h)),
            "points" to buildJsonArray { rel.forEach { add(buildJsonArray { add(JsonPrimitive(it[0])); add(JsonPrimitive(it[1])) }) } },
            "pressures" to JsonArray(pts.map { JsonPrimitive(r2(it[2].coerceIn(0.0, 1.0))) }),
            "lastCommittedPoint" to JsonNull,
        )
        if (!first) {
            // a new element just above the original one
            changes += "id" to JsonPrimitive(ExcalidrawScene.randomId())
            changes += "seed" to JsonPrimitive(Random.nextInt(1, Int.MAX_VALUE))
            el.index?.let { changes += "index" to JsonPrimitive(it + suffix()) }
        }
        return el.withChanges(*changes.toTypedArray())
    }

    private const val DIGITS = "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private fun suffix() = (1..3).map { DIGITS[Random.nextInt(DIGITS.length)] }.joinToString("")
}
