package dev.inkcalidraw

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import kotlin.random.Random
import kotlin.math.roundToInt

/**
 * Pen profiles. All of them are stored as normal Excalidraw freedraw elements (so excalidraw.com shows
 * them as solid strokes); the app additionally records customData.inkcalidrawBrush and renders
 * pencil/chalk with a paper grain.
 */
data class PenProfile(
    val id: String,
    val label: String,
    /** false = constant width: pressures are stored as 0.5, which Excalidraw also draws at constant width. */
    val pressure: Boolean,
    val grain: String?,
    val default: PenConfig,
    val widths: List<Double>,
)

object PenProfiles {
    private val FINE = listOf(0.25, 0.35, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 6.0, 8.0)
    val all = listOf(
        PenProfile("pen", "Pen", pressure = true, grain = null, default = PenConfig("#1e1e1e", 1.5, 100), widths = FINE),
        PenProfile("fineliner", "Fineliner", pressure = false, grain = null, default = PenConfig("#1e1e1e", 0.75, 100), widths = FINE),
        PenProfile("pencil", "Pencil", pressure = true, grain = "pencil", default = PenConfig("#495057", 1.0, 90), widths = FINE),
        PenProfile("chalk", "Chalk", pressure = true, grain = "chalk", default = PenConfig("#1e1e1e", 3.0, 95), widths = listOf(1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0)),
    )
    val highlighter = PenProfile("highlighter", "Highlighter", pressure = false, grain = null, default = PenConfig("#ffd43b", 8.0, 40), widths = listOf(3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0))

    fun byId(id: String?): PenProfile = all.firstOrNull { it.id == id } ?: if (id == "highlighter") highlighter else all[0]

    private val noiseCache = HashMap<String, FloatArray>()
    const val GRAIN_SIZE = 512 // big enough that the tile doesn't visibly repeat along a stroke

    /**
     * Tileable multi-octave value noise, rank-normalised to an even 0..1 spread (so a threshold t keeps exactly a
     * fraction 1 - t of the texels). Pencil and chalk grains sample it at their position on the page: the paper's tooth.
     */
    @Synchronized fun noise(kind: String): FloatArray = noiseCache.getOrPut(kind) {
        val n = GRAIN_SIZE
        val rnd = Random(if (kind == "chalk") 7 else 3)
        val octaves = if (kind == "chalk") listOf(2 to 0.30f, 4 to 0.28f, 16 to 0.24f, 64 to 0.18f) else listOf(1 to 0.40f, 2 to 0.27f, 8 to 0.18f, 32 to 0.15f)
        val f = FloatArray(n * n)
        for ((period, weight) in octaves) {
            val g = n / period
            val lattice = FloatArray(g * g) { rnd.nextFloat() }
            for (y in 0 until n) for (x in 0 until n) {
                val fx = x.toFloat() / period; val fy = y.toFloat() / period
                val x0 = fx.toInt(); val y0 = fy.toInt()
                var tx = fx - x0; var ty = fy - y0
                tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty)
                fun l(a: Int, b: Int) = lattice[(b % g) * g + (a % g)]
                val v = (l(x0, y0) * (1 - tx) + l(x0 + 1, y0) * tx) * (1 - ty) + (l(x0, y0 + 1) * (1 - tx) + l(x0 + 1, y0 + 1) * tx) * ty
                f[y * n + x] += v * weight
            }
        }
        // rank without boxing: (value bits, index) packed in a long; positive floats sort like their bits
        val keys = LongArray(f.size) { (java.lang.Float.floatToIntBits(f[it]).toLong() shl 32) or it.toLong() }
        keys.sort()
        val out = FloatArray(f.size)
        keys.forEachIndexed { rank, k -> out[(k and 0xffffffffL).toInt()] = rank / (f.size - 1f) }
        out
    }

}
