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

    /** Grain coverage of each ring of a stroke, edge first (see GrainStroke.RINGS). */
    private fun densities(kind: String) = if (kind == "chalk") floatArrayOf(0.34f, 0.6f, 0.84f) else floatArrayOf(0.42f, 0.7f, 0.93f)

    private val noiseCache = HashMap<String, FloatArray>()
    const val GRAIN_SIZE = 512 // big enough that the tile doesn't visibly repeat along a stroke

    /**
     * Tileable multi-octave value noise, rank-normalised to an even 0..1 spread (so a threshold t keeps exactly a
     * fraction 1 - t of the texels). The rings of a stroke all threshold the same field, so the sparse edge grain is
     * a subset of the denser grain inside it: the paper's tooth, not a different pattern.
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

    /** Alpha of a texel with noise value v in a ring of grain coverage d. */
    fun grainAlpha(v: Float, d: Float, kind: String): Int {
        val t = 1f - d
        val soft = if (kind == "chalk") 0.06f else 0.10f
        val cover = ((v - (t - soft)) / (2 * soft)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        val tone = if (kind == "chalk") 0.72f + 0.28f * v else 0.58f + 0.42f * v // high spots of the paper take more
        return (cover * tone * 255).roundToInt().coerceIn(0, 255)
    }

    /** Repeating alpha textures for a grainy brush, one per stroke ring (edge first), in scene units. */
    fun grainShaders(kind: String): Array<Shader> {
        val nz = noise(kind)
        return densities(kind).map { d ->
            val px = ByteArray(nz.size) { grainAlpha(nz[it], d, kind).toByte() }
            val bmp = Bitmap.createBitmap(GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ALPHA_8)
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { anchorGrain(this, kind, 0f, 0f) } as Shader
        }.toTypedArray()
    }

    fun anchorGrains(shaders: Array<Shader>, kind: String, originX: Float, originY: Float) = shaders.forEach { anchorGrain(it, kind, originX, originY) }

    private val grainMatrix = ThreadLocal.withInitial { android.graphics.Matrix() }

    /**
     * Pins the grain to the paper: texel positions are fixed in scene coordinates, whatever the canvas is translated to.
     * [originX]/[originY] = scene position of the canvas origin (an element's x/y when drawing it in local coordinates).
     */
    fun anchorGrain(shader: Shader, kind: String, originX: Float, originY: Float) {
        val k = if (kind == "chalk") 0.5f else 0.25f
        val m = grainMatrix.get()!!
        m.setScale(k, k)
        m.postTranslate(-originX, -originY)
        shader.setLocalMatrix(m)
    }
}
