package dev.inkcalidraw

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shapes, stored as Excalidraw's own element types (so Excalidraw, Obsidian and the live viewer show them as real,
 * editable shapes): rectangle, ellipse, diamond, line, arrow; a triangle is a closed line. Drawn in Excalidraw's
 * clean "architect" style (roughness 0).
 */
enum class ShapeKind(val key: String, val label: String) {
    LINE("line", "Line"), ARROW("arrow", "Arrow"), RECTANGLE("rectangle", "Rectangle"),
    ELLIPSE("ellipse", "Ellipse"), TRIANGLE("triangle", "Triangle"), DIAMOND("diamond", "Diamond");

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: RECTANGLE }
}

object Shapes {
    /** Shape stroke width that looks like a line drawn with a pen of [penWidth] at a typical pressure (freedraw renders much wider than its strokeWidth). */
    fun strokeFor(penWidth: Double): Double {
        val o = PerfectFreehand.excalidrawOptions(penWidth, simulatePressure = false, last = false)
        val w = 2 * PerfectFreehand.strokeRadius(o.size, o.thinning, 0.4, o.easing)
        return w.coerceIn(0.5, 40.0).let { (it * 4).roundToInt() / 4.0 }
    }

    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    private fun base(type: String, x: Double, y: Double, w: Double, h: Double, color: String, strokeWidth: Double, opacity: Int,
                     index: String?, frameId: String?, extra: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): Element {
        val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        m["id"] = JsonPrimitive(ExcalidrawScene.randomId()); m["type"] = JsonPrimitive(type)
        m["x"] = JsonPrimitive(r2(x)); m["y"] = JsonPrimitive(r2(y)); m["width"] = JsonPrimitive(r2(w)); m["height"] = JsonPrimitive(r2(h))
        m["angle"] = JsonPrimitive(0); m["strokeColor"] = JsonPrimitive(color); m["backgroundColor"] = JsonPrimitive("transparent")
        m["fillStyle"] = JsonPrimitive("solid"); m["strokeWidth"] = JsonPrimitive(strokeWidth); m["strokeStyle"] = JsonPrimitive("solid")
        m["roughness"] = JsonPrimitive(0); m["opacity"] = JsonPrimitive(opacity); m["groupIds"] = JsonArray(emptyList())
        m["frameId"] = frameId?.let { JsonPrimitive(it) } ?: JsonNull
        if (index != null) m["index"] = JsonPrimitive(index)
        m["roundness"] = JsonNull; m["seed"] = JsonPrimitive(Random.nextInt(1, Int.MAX_VALUE)); m["version"] = JsonPrimitive(1)
        m["versionNonce"] = JsonPrimitive(Random.nextInt(1, Int.MAX_VALUE)); m["isDeleted"] = JsonPrimitive(false)
        m["boundElements"] = JsonNull; m["updated"] = JsonPrimitive(System.currentTimeMillis()); m["link"] = JsonNull; m["locked"] = JsonPrimitive(false)
        extra(m)
        return Element(JsonObject(m))
    }

    /** A shape from a drag (or recognised drawing) from (ax, ay) to (bx, by). */
    fun build(kind: ShapeKind, ax: Double, ay: Double, bx: Double, by: Double, color: String, strokeWidth: Double, opacity: Int,
              index: String?, frameId: String?): Element {
        val x0 = min(ax, bx); val y0 = min(ay, by); val w = abs(bx - ax); val h = abs(by - ay)
        return when (kind) {
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.DIAMOND ->
                base(kind.key, x0, y0, w, h, color, strokeWidth, opacity, index, frameId) {}
            ShapeKind.LINE, ShapeKind.ARROW -> polyline(if (kind == ShapeKind.ARROW) "arrow" else "line",
                listOf(doubleArrayOf(ax, ay), doubleArrayOf(bx, by)), color, strokeWidth, opacity, index, frameId)
            ShapeKind.TRIANGLE -> polyline("line", listOf(doubleArrayOf((ax + bx) / 2, y0), doubleArrayOf(max(ax, bx), y0 + h),
                doubleArrayOf(x0, y0 + h), doubleArrayOf((ax + bx) / 2, y0)), color, strokeWidth, opacity, index, frameId)
        }
    }

    /** A line/arrow through scene points (closed when first == last). */
    fun polyline(type: String, pts: List<DoubleArray>, color: String, strokeWidth: Double, opacity: Int, index: String?, frameId: String?): Element {
        val ox = pts[0][0]; val oy = pts[0][1]
        val rel = pts.map { doubleArrayOf(r2(it[0] - ox), r2(it[1] - oy)) }
        val w = rel.maxOf { it[0] } - rel.minOf { it[0] }; val h = rel.maxOf { it[1] } - rel.minOf { it[1] }
        return base(type, ox, oy, w, h, color, strokeWidth, opacity, index, frameId) { m ->
            m["points"] = buildJsonArray { rel.forEach { add(buildJsonArray { add(JsonPrimitive(it[0])); add(JsonPrimitive(it[1])) }) } }
            m["lastCommittedPoint"] = JsonNull; m["startBinding"] = JsonNull; m["endBinding"] = JsonNull
            m["startArrowhead"] = JsonNull; m["endArrowhead"] = if (type == "arrow") JsonPrimitive("arrow") else JsonNull
            if (type == "arrow") m["elbowed"] = JsonPrimitive(false)
        }
    }

    /** Snaps a line's end to 0/45/90/... degrees when it's within [tolDeg] of one. */
    fun snapAngle(ax: Double, ay: Double, bx: Double, by: Double, tolDeg: Double = 4.0): DoubleArray {
        val len = hypot(bx - ax, by - ay)
        if (len < 1e-6) return doubleArrayOf(bx, by)
        val a = atan2(by - ay, bx - ax)
        val step = PI / 4
        val snapped = Math.round(a / step) * step
        return if (abs(a - snapped) <= tolDeg * PI / 180) doubleArrayOf(ax + cos(snapped) * len, ay + sin(snapped) * len) else doubleArrayOf(bx, by)
    }

    /** The visible outline as a polyline in the element's local coordinates (for hit tests). */
    fun outline(el: Element): List<DoubleArray> {
        val w = el.width; val h = el.height
        return when (el.type) {
            "rectangle" -> listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(w, 0.0), doubleArrayOf(w, h), doubleArrayOf(0.0, h), doubleArrayOf(0.0, 0.0))
            "diamond" -> listOf(doubleArrayOf(w / 2, 0.0), doubleArrayOf(w, h / 2), doubleArrayOf(w / 2, h), doubleArrayOf(0.0, h / 2), doubleArrayOf(w / 2, 0.0))
            "ellipse" -> (0..48).map { i -> val t = i / 48.0 * 2 * PI; doubleArrayOf(w / 2 + w / 2 * cos(t), h / 2 + h / 2 * sin(t)) }
            else -> el.points
        }
    }
}
