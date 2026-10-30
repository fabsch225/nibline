package dev.inkcalidraw

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Line-for-line port of perfect-freehand 1.2.0 (the version bundled by Excalidraw),
 * so a freedraw element renders with the exact same outline on Android as in Excalidraw.
 *
 * Input points are [x, y] or [x, y, pressure]. A missing / negative pressure behaves like
 * JS `undefined` in the original.
 */
object PerfectFreehand {

    class Options(
        val size: Double = 16.0,
        val thinning: Double = 0.5,
        val smoothing: Double = 0.5,
        val streamline: Double = 0.5,
        val simulatePressure: Boolean = true,
        val easing: (Double) -> Double = { it },
        val last: Boolean = false,
    )

    /** The exact options Excalidraw uses for `freedraw` elements. */
    fun excalidrawOptions(strokeWidth: Double, simulatePressure: Boolean, last: Boolean) = Options(
        size = strokeWidth * 4.25,
        thinning = 0.6,
        smoothing = 0.5,
        streamline = 0.5,
        simulatePressure = simulatePressure,
        easing = { r -> sin(r * PI / 2) },
        last = last,
    )

    class StrokePoint(
        val point: DoubleArray,
        val pressure: Double,
        var vector: DoubleArray,
        val distance: Double,
        val runningLength: Double,
    )

    private const val RATE_OF_PRESSURE_CHANGE = 0.275
    private val FIXED_PI = PI + 1e-4

    // --- vec helpers (2D, mirror the JS ones) ---
    private fun neg(a: DoubleArray) = doubleArrayOf(-a[0], -a[1])
    private fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1])
    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1])
    private fun mul(a: DoubleArray, n: Double) = doubleArrayOf(a[0] * n, a[1] * n)
    private fun per(a: DoubleArray) = doubleArrayOf(a[1], -a[0])
    private fun dpr(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1]
    private fun isEqual(a: DoubleArray, b: DoubleArray) = a[0] == b[0] && a[1] == b[1]
    private fun len(a: DoubleArray) = hypot(a[0], a[1])
    private fun dist2(a: DoubleArray, b: DoubleArray): Double { val d = sub(a, b); return d[0] * d[0] + d[1] * d[1] }
    private fun uni(a: DoubleArray): DoubleArray { val l = len(a); return doubleArrayOf(a[0] / l, a[1] / l) }
    private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[1] - b[1], a[0] - b[0])
    private fun rotAround(a: DoubleArray, c: DoubleArray, r: Double): DoubleArray {
        val s = sin(r); val co = cos(r)
        val px = a[0] - c[0]; val py = a[1] - c[1]
        val nx = px * co - py * s; val ny = px * s + py * co
        return doubleArrayOf(nx + c[0], ny + c[1])
    }
    private fun lrp(a: DoubleArray, b: DoubleArray, t: Double) = add(a, mul(sub(b, a), t))
    private fun prj(a: DoubleArray, b: DoubleArray, c: Double) = add(a, mul(b, c))

    private fun hasPressure(p: DoubleArray) = p.size > 2 && p[2] >= 0.0

    fun strokeRadius(size: Double, thinning: Double, pressure: Double, easing: (Double) -> Double) =
        size * easing(0.5 - thinning * (0.5 - pressure))

    fun getStroke(points: List<DoubleArray>, o: Options): List<DoubleArray> =
        getStrokeOutlinePoints(getStrokePoints(points, o), o)

    /**
     * Pre-compensates perfect-freehand's streamline so the rendered stroke goes exactly through the given points.
     *
     * getStrokePoints() pulls every point towards the previous one (point = prev + (input - prev) * t), which makes
     * strokes lag and cut corners - small features like the hook of an "i" get rounded off. Storing
     * input' = prev + (wanted - prev) / t instead makes that step land exactly on the pen position, in any renderer
     * that uses Excalidraw's algorithm (Excalidraw itself, Obsidian, the live viewer, this app). Points that
     * perfect-freehand skips anyway (the start of a stroke, before it reaches its minimum length) are left as they are.
     *
     * [round] = round each result to 0.01, as stored in files, and continue from the rounded value (so the
     * compensation matches what a renderer will compute from the file).
     */
    fun exactPathInput(points: List<DoubleArray>, o: Options, round: Boolean = false): List<DoubleArray> {
        if (points.size <= 2 || o.streamline == 0.0) return points // perfect-freehand special-cases tiny strokes
        val t = 0.15 + (1 - o.streamline) * 0.85
        fun r(v: Double) = if (round) Math.round(v * 100) / 100.0 else v
        val out = ArrayList<DoubleArray>(points.size)
        out += points[0]
        var px = points[0][0]; var py = points[0][1]
        var running = 0.0
        var reached = false
        val maxI = points.size - 1
        for (i in 1..maxI) {
            val a = points[i]
            val rest = if (a.size > 2) a.copyOfRange(2, a.size) else DoubleArray(0)
            val accept = reached || i == maxI || running + hypot(a[0] - px, a[1] - py) >= o.size
            if (!accept) {
                // still inside the skipped start: store as is, and track what perfect-freehand will compute
                out += a
                val lx = px + (a[0] - px) * t; val ly = py + (a[1] - py) * t
                if (lx != px || ly != py) running += hypot(lx - px, ly - py)
                continue
            }
            val cx = r(px + (a[0] - px) / t); val cy = r(py + (a[1] - py) / t)
            out += doubleArrayOf(cx, cy, *rest)
            val lx = px + (cx - px) * t; val ly = py + (cy - py) * t
            if (lx == px && ly == py) continue
            running += hypot(lx - px, ly - py)
            reached = true
            px = lx; py = ly
        }
        return out
    }

    fun getStrokePoints(input: List<DoubleArray>, o: Options): List<StrokePoint> {
        if (input.isEmpty()) return emptyList()
        val t = 0.15 + (1 - o.streamline) * 0.85
        var pts: List<DoubleArray> = input
        if (pts.size == 2) {
            val last = pts[1]
            val l = pts.subList(0, 1).toMutableList()
            for (i in 1 until 5) l.add(lrp(l[0], last, i / 4.0)) // note: lrp drops pressure, as in JS
            pts = l
        }
        if (pts.size == 1) {
            val p0 = pts[0]
            val off = add(p0, doubleArrayOf(1.0, 1.0))
            pts = listOf(p0, if (p0.size > 2) doubleArrayOf(off[0], off[1], *p0.copyOfRange(2, p0.size)) else off)
        }
        val strokePoints = mutableListOf(
            StrokePoint(
                point = doubleArrayOf(pts[0][0], pts[0][1]),
                pressure = if (hasPressure(pts[0])) pts[0][2] else 0.25,
                vector = doubleArrayOf(1.0, 1.0),
                distance = 0.0,
                runningLength = 0.0,
            )
        )
        var hasReachedMinimumLength = false
        var runningLength = 0.0
        var prev = strokePoints[0]
        val maxI = pts.size - 1
        for (i in 1 until pts.size) {
            val point = if (o.last && i == maxI) doubleArrayOf(pts[i][0], pts[i][1]) else lrp(prev.point, pts[i], t)
            if (isEqual(prev.point, point)) continue
            val distance = dist(point, prev.point)
            runningLength += distance
            if (i < maxI && !hasReachedMinimumLength) {
                if (runningLength < o.size) continue
                hasReachedMinimumLength = true
            }
            prev = StrokePoint(
                point = point,
                pressure = if (hasPressure(pts[i])) pts[i][2] else 0.5,
                vector = uni(sub(prev.point, point)),
                distance = distance,
                runningLength = runningLength,
            )
            strokePoints.add(prev)
        }
        strokePoints[0].vector = strokePoints.getOrNull(1)?.vector ?: doubleArrayOf(0.0, 0.0)
        return strokePoints
    }

    fun getStrokeOutlinePoints(points: List<StrokePoint>, o: Options): List<DoubleArray> {
        val size = o.size
        val thinning = o.thinning
        val simulate = o.simulatePressure
        val easing = o.easing
        // Excalidraw passes no start/end options -> taper 0, caps on, default cap easings unused.
        val taperStart = 0.0
        val taperEnd = 0.0
        if (points.isEmpty() || size <= 0) return emptyList()

        val totalLength = points.last().runningLength
        val minDistance = (size * o.smoothing).pow(2)
        val leftPts = mutableListOf<DoubleArray>()
        val rightPts = mutableListOf<DoubleArray>()

        var prevPressure = points.take(10).fold(points[0].pressure) { acc, curr ->
            var pressure = curr.pressure
            if (simulate) {
                val sp = min(1.0, curr.distance / size)
                val rp = min(1.0, 1 - sp)
                pressure = min(1.0, acc + (rp - acc) * (sp * RATE_OF_PRESSURE_CHANGE))
            }
            (acc + pressure) / 2
        }

        var radius = strokeRadius(size, thinning, points.last().pressure, easing)
        var firstRadius: Double? = null
        var prevVector = points[0].vector
        var pl = points[0].point
        var pr = pl
        var tl = pl
        var tr = pr
        var isPrevPointSharpCorner = false

        for (i in points.indices) {
            var pressure = points[i].pressure
            val point = points[i].point
            val vector = points[i].vector
            val distance = points[i].distance
            val runningLength = points[i].runningLength

            if (i < points.size - 1 && totalLength - runningLength < 3) continue

            if (thinning != 0.0) {
                if (simulate) {
                    val sp = min(1.0, distance / size)
                    val rp = min(1.0, 1 - sp)
                    pressure = min(1.0, prevPressure + (rp - prevPressure) * (sp * RATE_OF_PRESSURE_CHANGE))
                }
                radius = strokeRadius(size, thinning, pressure, easing)
            } else {
                radius = size / 2
            }
            if (firstRadius == null) firstRadius = radius

            val ts = if (runningLength < taperStart) 1.0 else 1.0
            val te = if (totalLength - runningLength < taperEnd) 1.0 else 1.0
            radius = max(0.01, radius * min(ts, te))

            val nextVector = (if (i < points.size - 1) points[i + 1] else points[i]).vector
            val nextDpr = if (i < points.size - 1) dpr(vector, nextVector) else 1.0
            val prevDpr = dpr(vector, prevVector)
            val isPointSharpCorner = prevDpr < 0 && !isPrevPointSharpCorner
            val isNextPointSharpCorner = nextDpr < 0

            if (isPointSharpCorner || isNextPointSharpCorner) {
                val offset = mul(per(prevVector), radius)
                val step = 1.0 / 13
                var t = 0.0
                while (t <= 1) {
                    tl = rotAround(sub(point, offset), point, FIXED_PI * t)
                    leftPts.add(tl)
                    tr = rotAround(add(point, offset), point, FIXED_PI * -t)
                    rightPts.add(tr)
                    t += step
                }
                pl = tl
                pr = tr
                if (isNextPointSharpCorner) isPrevPointSharpCorner = true
                continue
            }
            isPrevPointSharpCorner = false

            if (i == points.size - 1) {
                val offset = mul(per(vector), radius)
                leftPts.add(sub(point, offset))
                rightPts.add(add(point, offset))
                continue
            }

            val offset = mul(per(lrp(nextVector, vector, nextDpr)), radius)
            tl = sub(point, offset)
            if (i <= 1 || dist2(pl, tl) > minDistance) { leftPts.add(tl); pl = tl }
            tr = add(point, offset)
            if (i <= 1 || dist2(pr, tr) > minDistance) { rightPts.add(tr); pr = tr }
            prevPressure = pressure
            prevVector = vector
        }

        val firstPoint = doubleArrayOf(points[0].point[0], points[0].point[1])
        val lastPoint = if (points.size > 1) doubleArrayOf(points.last().point[0], points.last().point[1])
        else add(points[0].point, doubleArrayOf(1.0, 1.0))
        val startCap = mutableListOf<DoubleArray>()
        val endCap = mutableListOf<DoubleArray>()

        if (points.size == 1) {
            // !(taperStart || taperEnd) is always true here
            val start = prj(firstPoint, uni(per(sub(firstPoint, lastPoint))), -(firstRadius ?: radius).let { if (it == 0.0) radius else it })
            val dot = mutableListOf<DoubleArray>()
            val step = 1.0 / 13
            var t = step
            while (t <= 1) { dot.add(rotAround(start, firstPoint, FIXED_PI * 2 * t)); t += step }
            return dot
        } else {
            // start cap (rounded)
            run {
                val step = 1.0 / 13
                var t = step
                while (t <= 1) { startCap.add(rotAround(rightPts[0], firstPoint, FIXED_PI * t)); t += step }
            }
            val direction = per(neg(points.last().vector))
            // end cap (rounded)
            val start = prj(lastPoint, direction, radius)
            val step = 1.0 / 29
            var t = step
            while (t < 1) { endCap.add(rotAround(start, lastPoint, FIXED_PI * 3 * t)); t += step }
        }
        return leftPts + endCap + rightPts.reversed() + startCap
    }

    /**
     * Builds the same path string Excalidraw's getFreeDrawSvgPath produces (before its 2-decimal
     * truncation): M p0 Q p0 mid(p0,p1) p1 mid(p1,p2) ... pn mid(pn,p0) L p0 Z
     * Returned as a list of commands so it can be fed into android.graphics.Path or compared in tests.
     */
    fun interface PathSink {
        fun moveTo(x: Double, y: Double)
        fun quadTo(cx: Double, cy: Double, x: Double, y: Double) {}
        fun lineTo(x: Double, y: Double) {}
        fun close() {}
    }

    fun emitPath(outline: List<DoubleArray>, sink: PathSink) {
        if (outline.isEmpty()) return
        val n = outline.size
        sink.moveTo(outline[0][0], outline[0][1])
        for (i in 0 until n) {
            val p = outline[i]
            val q = outline[(i + 1) % n]
            sink.quadTo(p[0], p[1], (p[0] + q[0]) / 2, (p[1] + q[1]) / 2)
        }
        sink.lineTo(outline[0][0], outline[0][1])
        sink.close()
    }
}
