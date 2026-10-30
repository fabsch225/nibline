package dev.inkcalidraw

import kotlin.math.PI
import kotlin.math.abs

/**
 * The 1€ filter (Casiez, Roussel & Vogel, CHI 2012): removes jitter from pointer input with little lag. It smooths
 * strongly when the pen moves slowly (where jitter is visible) and hardly at all when it moves fast (where lag would
 * be). Applied to pen samples as they arrive, so the live stroke and the saved stroke get the same smoothed points.
 *
 * Units: [minCutoff] and [dCutoff] in Hz, [beta] per unit of speed (here: per screen px/s), times in milliseconds.
 */
class OneEuroFilter(private val minCutoff: Double, private val beta: Double, private val dCutoff: Double = 8.0) {
    private var started = false
    private var x = 0.0
    private var dx = 0.0
    private var lastT = 0L

    fun reset() { started = false }

    fun filter(value: Double, timeMs: Long): Double {
        if (!started) { started = true; x = value; dx = 0.0; lastT = timeMs; return value }
        val dt = ((timeMs - lastT).coerceAtLeast(1)) / 1000.0
        lastT = timeMs
        val rawDx = (value - x) / dt
        dx += alpha(dCutoff, dt) * (rawDx - dx)
        val cutoff = minCutoff + beta * abs(dx)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    companion object {
        /** Pen position in screen px: ~1 px of lag when slow, a few px at writing speed. */
        fun forPenPosition() = OneEuroFilter(minCutoff = 8.0, beta = 0.04)
        /** Pen pressure (0..1): calms sensor noise that makes stroke edges wobble, follows real changes quickly. */
        fun forPressure() = OneEuroFilter(minCutoff = 6.0, beta = 4.0)
    }
}
