package dev.inkcalidraw

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class OneEuroFilterTest {
    private fun rms(a: List<Double>) = sqrt(a.sumOf { it * it } / a.size)

    /** Slow writing with sensor jitter (240 Hz samples): jitter is removed, the line stays where it is. */
    @Test fun removesJitterWhenSlow() {
        val f = OneEuroFilter.forPenPosition(); val rnd = Random(1)
        val noise = ArrayList<Double>(); val left = ArrayList<Double>()
        for (i in 0 until 480) { // 2 s at 240 Hz, moving 60 px/s
            val t = i * 1000L / 240; val truth = 100 + 0.25 * i
            val n = (rnd.nextDouble() - 0.5) * 1.6
            val out = f.filter(truth + n, t)
            if (i > 60) { noise += n; left += out - (truth - 60.0 / 240 * 0) }
        }
        val before = rms(noise); val after = rms(left.map { it - left.average() })
        println("jitter rms ${"%.2f".format(before)} px -> ${"%.2f".format(after)} px, mean offset ${"%.2f".format(left.average())} px")
        assertTrue("jitter at least halved ($before -> $after)", after < before * 0.5)
        assertTrue("stays on the line when slow (lag ${left.average()})", abs(left.average()) < 1.5)
    }

    /** Fast handwriting (1500 px/s): almost no lag. */
    @Test fun littleLagWhenFast() {
        val f = OneEuroFilter.forPenPosition()
        var lag = 0.0
        for (i in 0 until 120) { val t = i * 1000L / 240; val truth = 1500.0 * t / 1000; lag = truth - f.filter(truth, t) }
        println("lag at 1500 px/s: ${"%.2f".format(lag)} px")
        assertTrue("lag at writing speed under 6 px ($lag)", lag < 6)
    }

    @Test fun pressureFollowsRealChanges() {
        val f = OneEuroFilter.forPressure()
        var out = 0.0
        for (i in 0 until 24) out = f.filter(if (i < 4) 0.2 else 0.8, i * 1000L / 240) // step after ~17 ms
        assertTrue("reaches a pressure step within 80 ms ($out)", out > 0.7)
    }
}
