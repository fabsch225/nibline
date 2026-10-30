package dev.inkcalidraw

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import kotlin.math.max
import kotlin.math.min

/**
 * The drawing surface. Two layers, managed by androidx.graphics' CanvasFrontBufferedRenderer:
 *  - multi-buffered layer: the whole committed scene (+ selection/lasso/eraser overlays)
 *  - front-buffered layer: the stroke being drawn, rendered straight to the display for minimal latency
 *
 * The wet stroke is drawn with the *same* perfect-freehand outline the committed stroke will have, so
 * lifting the pen changes nothing on screen. commit() swaps it into the scene atomically.
 */
class CanvasSurface(context: Context, private val ui: Ui) : SurfaceView(context) {

    /** Everything the multi-buffered layer needs, as an immutable snapshot (rendered on another thread). */
    class Frame(
        val input: SceneRenderer.Input,
        val matrix: Matrix,
        val lasso: List<FloatArray>? = null,
        val selection: RectF? = null,
        val handle: RectF? = null,
        val eraser: FloatArray? = null,   // screen x, y, radius
        val hint: String? = null,          // e.g. "Release to add page"
        val pageLabel: String? = null,
    )

    /** One update of the stroke in progress (world coordinates). */
    class Wet(
        val strokeId: Int,
        val samples: FloatArray, // x, y, pressure triples
        val count: Int,          // number of real samples (rest are predicted)
        val total: Int,
        val color: Int,
        val strokeWidth: Double,
        val alpha: Int,
        val matrix: Matrix,
        val grain: String? = null,
    )

    @Volatile private var frame: Frame? = null
    private var renderer: CanvasFrontBufferedRenderer<Wet>? = null
    private var frameRequested = false

    // ---- render-thread state ----
    private val sceneRenderer = SceneRenderer(dark = ui.dark)
    private val wetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grains = HashMap<String, android.graphics.Shader>()
    private var wetStrokeId = -1
    private var prevTail: RectF? = null
    private val overlayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ui.accent }
    private val overlayFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = ui.dp(16) }

    init {
        // Draw under the window (normal SurfaceView z-order) so overlay Views can sit on top of it.
        holder.setFormat(PixelFormat.TRANSLUCENT)
        sceneRenderer.onImageReady = { post { requestRender() } }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                // The renderer configures its layers in its own surfaceChanged; render once that's done,
                // and once more a bit later in case the first request raced with that setup.
                post { requestRender() }
                postDelayed({ requestRender() }, 120)
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        renderer = CanvasFrontBufferedRenderer(this, object : CanvasFrontBufferedRenderer.Callback<Wet> {
            override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Wet) =
                drawWet(canvas, param)

            override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Wet>) {
                wetStrokeId = -1; prevTail = null
                drawFrame(canvas)
            }

            // The frame is on its way to the display: allow the next one once SurfaceFlinger has applied it.
            override fun onMultiBufferedLayerRenderComplete(
                frontBufferedLayerSurfaceControl: androidx.graphics.surface.SurfaceControlCompat,
                multiBufferedLayerSurfaceControl: androidx.graphics.surface.SurfaceControlCompat,
                transaction: androidx.graphics.surface.SurfaceControlCompat.Transaction,
            ) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    transaction.addTransactionCommittedListener(context.mainExecutor, object : androidx.graphics.surface.SurfaceControlCompat.TransactionCommittedListener {
                        override fun onTransactionCommitted() = frameApplied()
                    })
                } else post { postDelayed(::frameApplied, 8) }
            }
        })
    }

    fun release() { renderer?.release(true); renderer = null }

    /** Sets what the committed layer should show; it is drawn on the next vsync. */
    fun show(f: Frame) { frame = f; requestRender() }

    /*
     * At most one multi-buffered render in flight. renderMultiBufferedLayer() doesn't throttle: every call takes a
     * buffer from the renderer's small pool, and a buffer only comes back after the display has moved on to a newer
     * one. Requesting a frame on every vsync (eraser strokes, zoom animations, fast pans on a 120 Hz screen) could
     * drain the pool, and the render thread then blocked for good in BufferPool.obtainFromPool: the canvas froze
     * while input kept working. Now the next frame is only rendered once the previous one has been applied; requests
     * in between collapse into one.
     */
    private var renderInFlight = false   // main thread
    private var renderPending = false    // main thread
    private val frameAppliedRunnable = Runnable { frameApplied() }

    private fun frameApplied() {
        if (!renderInFlight) return
        renderInFlight = false
        removeCallbacks(frameAppliedRunnable)
        if (renderPending) { renderPending = false; requestRender() }
    }

    fun requestRender() {
        if (frameRequested) return
        frameRequested = true
        Choreographer.getInstance().postFrameCallback {
            frameRequested = false
            if (renderInFlight) { renderPending = true; return@postFrameCallback }
            val r = renderer ?: return@postFrameCallback
            renderInFlight = true
            // safety net in case a commit callback never arrives (surface destroyed mid-frame, ...)
            removeCallbacks(frameAppliedRunnable); postDelayed(frameAppliedRunnable, 250)
            r.renderMultiBufferedLayer(emptyList())
        }
    }

    fun renderWet(w: Wet) { renderer?.renderFrontBufferedLayer(w) }

    /** Wet stroke is now part of [f]: render the scene and hide the front buffer in one transaction. */
    fun commitWet(f: Frame) { frame = f; renderer?.commit() }

    fun cancelWet() { renderer?.cancel() }

    // ------------------------------------------------------------ render thread

    private fun drawWet(canvas: Canvas, w: Wet) {
        val input = ArrayList<DoubleArray>(w.total)
        for (i in 0 until w.total) input.add(doubleArrayOf(w.samples[i * 3].toDouble(), w.samples[i * 3 + 1].toDouble(), w.samples[i * 3 + 2].toDouble()))
        val opts = PerfectFreehand.excalidrawOptions(w.strokeWidth, simulatePressure = false, last = false)
        // Same input massaging ExcalidrawScene.newFreedraw does (a dot is stored as two equal points).
        if (input.size == 1) input.add(input[0].copyOf())
        val strokePoints = PerfectFreehand.getStrokePoints(input, opts)
        val outline = PerfectFreehand.getStrokeOutlinePoints(strokePoints, opts)
        val path = SceneRenderer.outlinePath(outline)

        // Only the tail of a perfect-freehand outline changes as points are added, so only clear and
        // redraw around the pen tip; the rest of the front buffer already shows the identical stroke.
        val pad = (opts.size * 1.2 + 4).toFloat()
        val newStroke = w.strokeId != wetStrokeId
        val full = newStroke || strokePoints.size < WetInk.FULL_REDRAW_BELOW
        val tail = RectF()
        if (full) {
            boundsOf(outline, tail)
            tail.inset(-pad, -pad)
        } else {
            val t = WetInk.tailBox(strokePoints, w.samples, w.count, w.total, opts.size)
            tail.set(t.minX.toFloat(), t.minY.toFloat(), t.maxX.toFloat(), t.maxY.toFloat())
        }
        val dirty = RectF(tail)
        if (!newStroke) prevTail?.let { dirty.union(it) }
        if (newStroke) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        prevTail = tail
        wetStrokeId = w.strokeId

        // Clip in screen space, snapped to whole pixels, so the cleared area and the redrawn
        // (anti-aliased) path cover exactly the same pixels: no seams at the dirty-rect edge.
        val screen = RectF(dirty); w.matrix.mapRect(screen)
        val px = android.graphics.Rect(); screen.roundOut(px)
        canvas.save()
        canvas.clipRect(px)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.concat(w.matrix)
        wetPaint.color = w.color
        wetPaint.alpha = w.alpha
        wetPaint.shader = w.grain?.let { k -> grains.getOrPut(k) { PenProfiles.grainShader(k) } }
        canvas.drawPath(path, wetPaint)
        canvas.restore()
    }

    private fun boundsOf(outline: List<DoubleArray>, out: RectF) {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (p in outline) { x0 = min(x0, p[0].toFloat()); y0 = min(y0, p[1].toFloat()); x1 = max(x1, p[0].toFloat()); y1 = max(y1, p[1].toFloat()) }
        out.set(x0, y0, x1, y1)
    }

    /** Number of committed-layer frames rendered (used by tests to wait for the first real frame). */
    @Volatile var framesDrawn = 0; private set

    private fun drawFrame(canvas: Canvas) {
        val f = frame ?: run { canvas.drawColor(ui.bg); return }
        framesDrawn++
        val s = scaleOf(f.matrix)
        canvas.save()
        canvas.concat(f.matrix)
        sceneRenderer.draw(canvas, f.input)
        f.selection?.let { r ->
            val rr = RectF(r); rr.inset(-6f / s, -6f / s)
            overlayStroke.strokeWidth = 2f / s; overlayStroke.pathEffect = null
            canvas.drawRoundRect(rr, 6f / s, 6f / s, overlayStroke)
        }
        f.handle?.let { h ->
            overlayFill.color = ui.surface
            canvas.drawOval(h, overlayFill); canvas.drawOval(h, overlayStroke)
        }
        f.lasso?.let { pts ->
            if (pts.size > 1) {
                val p = Path().apply { moveTo(pts[0][0], pts[0][1]); for (i in 1 until pts.size) lineTo(pts[i][0], pts[i][1]) }
                overlayFill.color = Color.argb(28, Color.red(ui.accent), Color.green(ui.accent), Color.blue(ui.accent))
                canvas.drawPath(p, overlayFill)
                overlayStroke.strokeWidth = 2f / s
                overlayStroke.pathEffect = DashPathEffect(floatArrayOf(10f / s, 8f / s), 0f)
                canvas.drawPath(p, overlayStroke)
                overlayStroke.pathEffect = null
            }
        }
        canvas.restore()
        f.eraser?.let { e ->
            overlayFill.color = Color.argb(40, 128, 128, 128)
            canvas.drawCircle(e[0], e[1], e[2], overlayFill)
            overlayStroke.strokeWidth = ui.dp(1.5f); canvas.drawCircle(e[0], e[1], e[2], overlayStroke)
        }
        f.hint?.let { h ->
            hintPaint.color = ui.subtext
            canvas.drawText(h, width / 2f, height - ui.dp(90), hintPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun performClick(): Boolean = super.performClick()

    companion object {
        fun scaleOf(m: Matrix): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }
    }
}
