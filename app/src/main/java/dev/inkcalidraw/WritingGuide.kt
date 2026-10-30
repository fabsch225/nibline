package dev.inkcalidraw

import kotlin.math.abs

/**
 * The writing guide: once a few words of handwriting are on a line, a faint baseline appears ahead of the pen so
 * the rest of the line stays straight. Only drawn on screen, never stored in the note.
 */
object WritingGuide {
    /** Bounds of a stroke's centre line (scene units), in the order the strokes were written. */
    class Stroke(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val h get() = maxY - minY
        val cy get() = (minY + maxY) / 2
    }

    class Line(val x0: Double, val y0: Double, val slope: Double) {
        fun yAt(x: Double) = y0 + (x - x0) * slope
    }

    /**
     * The baseline of the line of text the most recent strokes belong to, or null when they don't look like
     * handwriting on one line (a drawing, a single word, a line that just started).
     * [maxLetter] = tallest stroke still counted as a letter (scene units).
     */
    fun baseline(recent: List<Stroke>, maxLetter: Double, minStrokes: Int = 5): Line? {
        if (recent.size < minStrokes) return null
        val latest = recent.last()
        if (latest.h > maxLetter) return null
        // walk back while the strokes stay on the same line and close together
        val line = ArrayList<Stroke>()
        line += latest
        var left = latest.minX; var right = latest.maxX
        for (i in recent.size - 2 downTo 0) {
            val s = recent[i]
            if (s.h > maxLetter) break
            val hRef = median(line.map { it.h }).coerceAtLeast(maxLetter * 0.08)
            val sameLine = abs(s.cy - median(line.map { it.cy })) < hRef * 0.9
            val near = s.minX < right + hRef * 4 && s.maxX > left - hRef * 4
            if (!sameLine || !near) break
            line += s
            left = minOf(left, s.minX); right = maxOf(right, s.maxX)
            if (line.size >= 40) break
        }
        if (line.size < minStrokes) return null
        val h = median(line.map { it.h })
        if (right - left < h * 3) return null
        // letters sit on the baseline; descenders (g, p, y) hang below it: fit through the typical bottoms
        val bottoms = line.map { doubleArrayOf((it.minX + it.maxX) / 2, it.maxY) }
        val mid = median(bottoms.map { it[1] })
        val core = bottoms.filter { abs(it[1] - mid) < h * 0.35 }.ifEmpty { bottoms }
        var slope = 0.0
        val mx = core.map { it[0] }.average(); val my = core.map { it[1] }.average()
        if (core.size >= 4) {
            val sxx = core.sumOf { (it[0] - mx) * (it[0] - mx) }
            if (sxx > 1e-6) slope = core.sumOf { (it[0] - mx) * (it[1] - my) } / sxx
        }
        slope = slope.coerceIn(-0.08, 0.08)
        if (abs(slope) < 0.01) slope = 0.0
        return Line(left, my + (left - mx) * slope, slope)
    }

    private fun median(v: List<Double>): Double { val s = v.sorted(); return if (s.isEmpty()) 0.0 else s[s.size / 2] }
}
