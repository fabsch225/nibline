package dev.inkcalidraw

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue

/** App chrome colours + dark mode. Canvas dark mode mimics Excalidraw's `invert(93%) hue-rotate(180deg)`. */
class Ui private constructor(val dark: Boolean, private val density: Float) {
    val bg = if (dark) Color.parseColor("#121212") else Color.parseColor("#f4f4f6")
    val bar = if (dark) Color.parseColor("#1f1f23") else Color.WHITE
    val surface = if (dark) Color.parseColor("#26262b") else Color.WHITE
    val text = if (dark) Color.parseColor("#e3e3e8") else Color.parseColor("#1e1e1e")
    val subtext = if (dark) Color.parseColor("#9a9aa5") else Color.parseColor("#6b6b75")
    val accent = Color.parseColor(if (dark) "#ff7657" else "#e5432a") // brand orange
    val accentSoft = if (dark) Color.parseColor("#4a241b") else Color.parseColor("#fde2db")
    val divider = if (dark) Color.parseColor("#34343b") else Color.parseColor("#e3e3ea")
    /** Area around pages in paged notes. */
    val desk = if (dark) Color.parseColor("#2e2e35") else Color.parseColor("#e6e6ec") // dark: lighter than the (dark) pages

    fun dp(v: Number) = v.toFloat() * density
    fun dpi(v: Number) = (v.toFloat() * density).toInt()

    fun rounded(color: Int, radiusDp: Number, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp)
        if (strokeColor != null) setStroke(dpi(1), strokeColor)
    }

    /** Colour transform applied to scene colours (not to images), same as Excalidraw's dark theme. */
    fun sceneColor(argb: Int): Int = if (dark) darkTransform(argb) else argb

    companion object {
        private const val PREFS = "ui"
        fun themeSetting(ctx: Context): String = ctx.getSharedPreferences(PREFS, 0).getString("theme", "system") ?: "system"
        fun setThemeSetting(ctx: Context, v: String) = ctx.getSharedPreferences(PREFS, 0).edit().putString("theme", v).apply()

        fun of(ctx: Context): Ui {
            val sys = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val dark = when (themeSetting(ctx)) { "dark" -> true; "light" -> false; else -> sys }
            return Ui(dark, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, ctx.resources.displayMetrics))
        }

        /** CSS `invert(0.93) hue-rotate(180deg)` on one sRGB colour. */
        fun darkTransform(argb: Int): Int {
            val a = Color.alpha(argb)
            val r0 = Color.red(argb) / 255.0; val g0 = Color.green(argb) / 255.0; val b0 = Color.blue(argb) / 255.0
            val inv = 0.93
            val r1 = inv + r0 * (1 - 2 * inv); val g1 = inv + g0 * (1 - 2 * inv); val b1 = inv + b0 * (1 - 2 * inv)
            // hue-rotate(180deg) matrix from the Filter Effects spec (cos = -1, sin = 0)
            val r2 = -0.574 * r1 + 1.430 * g1 + 0.144 * b1
            val g2 = 0.426 * r1 + 0.430 * g1 + 0.144 * b1
            val b2 = 0.426 * r1 + 1.430 * g1 - 0.856 * b1
            fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
            return Color.argb(a, c(r2), c(g2), c(b2))
        }
    }
}
