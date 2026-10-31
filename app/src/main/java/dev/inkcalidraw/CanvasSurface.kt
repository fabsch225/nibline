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
        val guide: FloatArray? = null,     // writing guide line, world x0, y0, x1, y1
        val laser: List<FloatArray>? = null, // laser trail, world x, y, alpha 0..1
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
    private var wetStrokeId = -1
    private var prevTail: RectF? = null
    private val overlayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ui.accent }
    private val overlayFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(150, Color.red(ui.accent), Color.green(ui.accent), Color.blue(ui.accent)) }
    private val laserGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE
        setShadowLayer(ui.dp(2f), 0f, 0f, Color.argb(70, 0, 0, 0)) }
    private val laserCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.rgb(240, 30, 30) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = ui.dp(16) }

    init {
        // Draw under the window (normal SurfaceView z-order) so overlay Views can sit on top of it.
        holder.setFormat(PixelFormat.TRANSLUCENT)
        sceneRenderer.onImageReady = { post { requestRender() } }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { applyFrameRate() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                // The renderer configures its layers in its own surfaceChanged; render once that's done,
                // and once more a bit later in case the first request raced with that setup.
                post { requestRender() }
                postDelayed({ requestRender() }, 120)
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        renderer = CanvasFrontBufferedRenderer(this, object : CanvasFrontBufferedRenderer.Callback<Wet> {
            override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Wet) {
                val t0 = System.nanoTime(); drawWet(canvas, param); wetNanos += System.nanoTime() - t0; wetFrames++
            }

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

    /** Frame rate this surface wants (the display's fastest mode while active, 0 = no preference); set by the activity. */
    var preferredFrameRate = 0f
        set(v) { field = v; applyFrameRate() }

    private fun applyFrameRate() {
        if (android.os.Build.VERSION.SDK_INT < 30) return // 0 = no preference (lets the display slow down)
        val s = holder.surface
        if (s.isValid) runCatching { s.setFrameRate(preferredFrameRate, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

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
        // Same input massaging ExcalidrawScene.newFreedraw does (a dot is stored as two equal points; exact path).
        if (input.size == 1) input.add(input[0].copyOf())
        // Compute in the same frame the file uses (relative to the first point, rounded to 0.01) so the wet stroke
        // and the saved one are the same to the pixel.
        val ox = input[0][0]; val oy = input[0][1]
        fun r2(v: Double) = Math.round(v * 100) / 100.0
        val rel = input.map { doubleArrayOf(r2(it[0] - ox), r2(it[1] - oy), r2(it[2].coerceIn(0.0, 1.0))) }
        // only the real samples go on the exact path: predicted ones keep perfect-freehand's damping, so a wrong
        // guess about where the pen goes next doesn't make the tip jitter
        val real = rel.subList(0, w.count.coerceIn(1, rel.size))
        val relExact = PerfectFreehand.exactPathInput(real, opts, round = true) + rel.subList(real.size, rel.size)
        val grain = w.grain?.takeIf { GrainStroke.isGrainBrush(it) }
        // Pencil/chalk grains are computed in the stroke's own frame (like the committed element), the pen outline in scene coordinates.
        val strokePoints: List<PerfectFreehand.StrokePoint>
        var path: Path? = null
        val bx: Double; val by: Double  // offset of strokePoints' frame in the scene
        val full0 = RectF()
        if (grain != null) {
            strokePoints = PerfectFreehand.getStrokePoints(relExact, opts)
            bx = ox; by = oy
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (p in strokePoints) { x0 = min(x0, p.point[0]); y0 = min(y0, p.point[1]); x1 = max(x1, p.point[0]); y1 = max(y1, p.point[1]) }
            full0.set((x0 + ox - opts.size).toFloat(), (y0 + oy - opts.size).toFloat(), (x1 + ox + opts.size).toFloat(), (y1 + oy + opts.size).toFloat())
        } else {
            strokePoints = PerfectFreehand.getStrokePoints(relExact.map { doubleArrayOf(it[0] + ox, it[1] + oy, it[2]) }, opts)
            bx = 0.0; by = 0.0
            val outline = PerfectFreehand.getStrokeOutlinePoints(strokePoints, opts)
            path = SceneRenderer.outlinePath(outline)
            boundsOf(outline, full0)
        }

        // Only the tail of a stroke changes as points are added, so only clear and redraw around the pen tip;
        // the rest of the front buffer already shows the identical stroke.
        val pad = (opts.size * 1.2 + 4).toFloat()
        val newStroke = w.strokeId != wetStrokeId
        val full = newStroke || strokePoints.size < WetInk.FULL_REDRAW_BELOW
        val tail = RectF()
        if (full) {
            tail.set(full0)
            tail.inset(-pad, -pad)
        } else {
            val t = WetInk.tailBox(strokePoints, w.samples, w.count, w.total, opts.size, bx, by)
            tail.set(t.minX.toFloat(), t.minY.toFloat(), t.maxX.toFloat(), t.maxY.toFloat())
        }
        val dirty = RectF(tail)
        if (!newStroke) prevTail?.let { dirty.union(it) }
        if (newStroke) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        prevTail = tail
        wetStrokeId = w.strokeId

        // Clip in screen space, snapped to whole pixels, so the cleared area and the redrawn
        // (anti-aliased) ink cover exactly the same pixels: no seams at the dirty-rect edge.
        val screen = RectF(dirty); w.matrix.mapRect(screen)
        val px = android.graphics.Rect(); screen.roundOut(px)
        canvas.save()
        canvas.clipRect(px)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.concat(w.matrix)
        wetPaint.color = w.color
        wetPaint.alpha = w.alpha
        if (grain != null) {
            // exactly what SceneRenderer draws for the committed stroke, but only the grains near the redrawn area
            val only = Geometry.Box(dirty.left - ox - 2, dirty.top - oy - 2, dirty.right - ox + 2, dirty.bottom - oy + 2)
            canvas.translate(ox.toFloat(), oy.toFloat())
            GrainStroke.draw(canvas, GrainStroke.grains(strokePoints, opts, grain, w.strokeWidth, ox, oy, only), wetPaint)
        } else {
            canvas.drawPath(path!!, wetPaint)
        }
        canvas.restore()
    }

    private fun boundsOf(outline: List<DoubleArray>, out: RectF) {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (p in outline) { x0 = min(x0, p[0].toFloat()); y0 = min(y0, p[1].toFloat()); x1 = max(x1, p[0].toFloat()); y1 = max(y1, p[1].toFloat()) }
        out.set(x0, y0, x1, y1)
    }

    /** Time spent drawing wet ink (render thread), read by the device tests. */
    @Volatile var wetNanos = 0L
    @Volatile var wetFrames = 0

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
        f.guide?.let { g ->
            guidePaint.strokeWidth = ui.dp(1.5f) / s
            guidePaint.pathEffect = DashPathEffect(floatArrayOf(ui.dp(6f) / s, ui.dp(5f) / s), 0f)
            canvas.drawLine(g[0], g[1], g[2], g[3], guidePaint)
        }
        f.laser?.let { pts ->
            // one smooth red line with a white border (drawn as whole paths, so segments don't show as beads)
            if (pts.isEmpty()) return@let
            val alpha = pts.last()[2]
            val p = Path()
            p.moveTo(pts[0][0], pts[0][1])
            if (pts.size == 1) p.lineTo(pts[0][0] + 0.01f, pts[0][1])
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i][0] + pts[i + 1][0]) / 2; val my = (pts[i][1] + pts[i + 1][1]) / 2
                p.quadTo(pts[i][0], pts[i][1], mx, my)
            }
            if (pts.size > 1) p.lineTo(pts.last()[0], pts.last()[1])
            val layer = alpha < 1f
            if (layer) canvas.saveLayerAlpha(null, (255 * alpha).toInt().coerceIn(0, 255))
            laserGlow.strokeWidth = ui.dp(7.5f) / s; laserGlow.alpha = 255
            canvas.drawPath(p, laserGlow)
            laserCore.strokeWidth = ui.dp(4f) / s; laserCore.alpha = 255
            canvas.drawPath(p, laserCore)
            if (layer) canvas.restore()
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
