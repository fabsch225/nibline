package dev.inkcalidraw

import android.content.Context
import kotlin.math.pow

/**
 * Pressure sensitivity: maps raw pen pressure to the stored pressure. Normal handwriting only uses a narrow band of
 * the S Pen's range (about 0.25-0.65), which with Excalidraw's stroke settings gives nearly constant widths. The
 * curve stretches that band, so light and firm strokes look clearly different. Stored like any pressure, so every
 * Excalidraw renderer shows the same stroke.
 */
object PenPressure {
    const val LOW = "low"       // raw pressure (the old behaviour)
    const val MEDIUM = "medium"
    const val HIGH = "high"
    val levels = listOf(LOW to "Low", MEDIUM to "Medium", HIGH to "High")

    fun curve(p: Float, level: String): Float {
        if (level == LOW) return p
        val (lo, hi, gamma) = if (level == HIGH) Triple(0.08f, 0.75f, 1.9f) else Triple(0.05f, 0.85f, 1.45f)
        return ((p - lo) / (hi - lo)).coerceIn(0f, 1f).pow(gamma)
    }

    fun level(ctx: Context): String = ctx.getSharedPreferences("ui", 0).getString("pressure", MEDIUM) ?: MEDIUM
    fun setLevel(ctx: Context, level: String) = ctx.getSharedPreferences("ui", 0).edit().putString("pressure", level).apply()
}
