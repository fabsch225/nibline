package dev.inkcalidraw

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import kotlin.random.Random

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

    /** Repeating alpha texture for grainy brushes, in scene units (so grain stays put on the paper). */
    fun grainShader(kind: String): Shader {
        val size = 128
        val rnd = Random(if (kind == "chalk") 7 else 3)
        val px = ByteArray(size * size)
        if (kind == "chalk") {
            // coarse, patchy coverage with gaps, like chalk on a board
            val coarse = Array(size / 4) { FloatArray(size / 4) { rnd.nextFloat() } }
            for (y in 0 until size) for (x in 0 until size) {
                val c = coarse[y / 4][x / 4] * 0.6f + rnd.nextFloat() * 0.4f
                px[y * size + x] = (if (c < 0.28f) 0 else (150 + c * 105).toInt().coerceAtMost(255)).toByte()
            }
        } else {
            // fine graphite grain, mostly covered
            for (i in px.indices) { val v = rnd.nextFloat(); px[i] = (if (v < 0.12f) 70 else 190 + (v * 65).toInt()).coerceAtMost(255).toByte() }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { anchorGrain(this, kind, 0f, 0f) }
    }

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
