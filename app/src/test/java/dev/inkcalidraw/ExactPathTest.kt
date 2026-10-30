package dev.inkcalidraw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sin

class ExactPathTest {
    private val o = PerfectFreehand.excalidrawOptions(2.0, simulatePressure = false, last = false)

    /** A small handwritten "i" stem with a hook: down, then a quick turn to the right. */
    private fun hookStroke(): List<DoubleArray> {
        val pts = ArrayList<DoubleArray>()
        for (i in 0..40) pts += doubleArrayOf(0.2 * sin(i / 5.0), i * 0.9, 0.5)          // down
        for (i in 1..14) pts += doubleArrayOf(i * 0.8, 36.0 + 0.15 * i, 0.6)             // hook to the right
        return pts
    }

    private fun maxErrorAfterStart(input: List<DoubleArray>, wanted: List<DoubleArray>): Double {
        val sp = PerfectFreehand.getStrokePoints(input, o)
        // every rendered point (after the first) should coincide with a wanted pen position
        return sp.drop(1).maxOf { p -> wanted.minOf { w -> hypot(w[0] - p.point[0], w[1] - p.point[1]) } }
    }

    @Test fun renderedStrokeFollowsThePenExactly() {
        val pen = hookStroke()
        val raw = maxErrorAfterStart(pen, pen)
        val exact = maxErrorAfterStart(PerfectFreehand.exactPathInput(pen, o), pen)
        println("streamline lag: raw input max off-path ${"%.2f".format(raw)} px, compensated ${"%.4f".format(exact)} px")
        assertTrue("raw input lags behind the pen ($raw)", raw > 0.3)
        assertTrue("compensated input renders on the pen path ($exact)", exact < 1e-6)
    }

    @Test fun roundedLikeFilesStillExact() {
        val pen = hookStroke().map { doubleArrayOf(Math.round(it[0] * 100) / 100.0, Math.round(it[1] * 100) / 100.0, it[2]) }
        val stored = PerfectFreehand.exactPathInput(pen, o, round = true)
        assertTrue(stored.all { p -> (0..1).all { Math.round(p[it] * 100) / 100.0 == p[it] } })
        val err = maxErrorAfterStart(stored, pen)
        assertTrue("rounded compensation stays within 0.02 px ($err)", err < 0.02)
    }

    @Test fun pressuresAndShortStrokesUntouched() {
        val pen = hookStroke()
        val out = PerfectFreehand.exactPathInput(pen, o)
        assertEquals(pen.size, out.size)
        for (i in pen.indices) assertEquals(pen[i][2], out[i][2], 0.0)
        val dot = listOf(doubleArrayOf(1.0, 1.0, 0.5), doubleArrayOf(1.0, 1.0, 0.5))
        assertEquals(dot, PerfectFreehand.exactPathInput(dot, o))
    }

    @Test fun pressureCurveSpreadsWritingRange() {
        // typical writing pressure 0.3..0.6 should use much more of the width range at the default setting
        val lo = PenPressure.curve(0.3f, PenPressure.MEDIUM); val hi = PenPressure.curve(0.6f, PenPressure.MEDIUM)
        assertTrue("spread ${hi - lo}", hi - lo > 0.35f)
        assertEquals(0.3f, PenPressure.curve(0.3f, PenPressure.LOW), 1e-6f) // Low = raw pressure, as before
        assertTrue(PenPressure.curve(0f, PenPressure.HIGH) >= 0f && PenPressure.curve(1f, PenPressure.HIGH) <= 1f)
    }
}
