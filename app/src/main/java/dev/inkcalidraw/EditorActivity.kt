package dev.inkcalidraw

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.input.motionprediction.MotionEventPredictor
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class EditorActivity : ComponentActivity(), Live.Source {

    enum class Tool(val key: String) { PEN("pen"), HIGHLIGHTER("highlighter"), ERASER("eraser"), SELECT("select") }

    private lateinit var ui: Ui
    private lateinit var store: NoteStore
    private lateinit var colors: ColorHistory
    private lateinit var surface: CanvasSurface
    private lateinit var predictor: MotionEventPredictor
    private lateinit var titleView: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var pageNav: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var menuAnchor: View
    private lateinit var colorDot: View
    private lateinit var recentRow: LinearLayout
    private lateinit var topBar: View
    private lateinit var topDivider: View
    private lateinit var exitFullscreen: View
    private lateinit var pdfs: PdfPages
    private lateinit var folder: androidx.documentfile.provider.DocumentFile
    private var fullscreen = false

    private val toolButtons = mutableMapOf<Tool, ImageView>()

    private var scene = ExcalidrawScene.empty()
    private var meta = NoteMeta()
    private var noteUri: Uri? = null
    private var dirty = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveNow() }
    private val undo = ArrayDeque<List<Element>>()
    private val redo = ArrayDeque<List<Element>>()

    /** world -> screen */
    private val view = Matrix()
    private var tool = Tool.PEN
    private var lassoArmed = false
    private var penOnly = true

    // ---- drawing-pointer gesture state ----
    private enum class G { NONE, INK, ERASE, LASSO, DRAG, RESIZE, BUTTON }
    private var g = G.NONE
    private var drawPointer = -1
    private var strokeCounter = 0
    private var samples = FloatArray(3 * 512)
    private var sampleCount = 0
    private var downX = 0f
    private var downY = 0f
    private var lastWorld = FloatArray(2)
    private var eraseSnapshot = false
    private val lasso = ArrayList<FloatArray>()
    private var selected: Set<String> = emptySet()
    private var dragDx = 0f
    private var dragDy = 0f
    private var resizeIndex = -1
    private var eraserCursor: FloatArray? = null
    private var hint: String? = null
    private val holdForMenu = Runnable { onButtonHold() }

    // ---- finger navigation ----
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var lastFingerCount = 0
    private var fingerVx = 0f
    private var lastMoveTime = 0L
    private var zoomedThisGesture = false
    private var animator: ValueAnimator? = null
    /** After "Fit width": finger drags only move vertically (until a pinch or another fit). */
    private var widthLocked = false
    private var velocity: android.view.VelocityTracker? = null
    private var fingerVy = 0f   // finger speed for scroll acceleration (px/s)
    @Volatile var lastReleaseVelocity = 0f; private set // for tests
    private val fling by lazy { android.widget.OverScroller(this, DecelerateInterpolator(1.2f)) }
    private var flingAnim: ValueAnimator? = null

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { insertPdf(it) } }
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let { writePdf(it) } }

    // =============================================================== lifecycle

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui.of(this)
        store = NoteStore(this)
        colors = ColorHistory(this)
        penOnly = getSharedPreferences("ui", 0).getBoolean("penOnly", true)

        folder = intent.getStringExtra(EXTRA_FOLDER)?.let { store.folder(Uri.parse(it)) } ?: externalFolder(intent?.data) ?: store.root()
        // PDFs annotated by this note live next to it (or in app storage for notes opened from elsewhere).
        pdfs = PdfPages(this) { name -> store.findFile(folder, name) ?: java.io.File(filesDir, "pdfs/$name").takeIf { it.exists() }?.let(Uri::fromFile) }
        surface = CanvasSurface(this, ui)
        pdfs.onReady = { surface.post { surface.requestRender() } }
        predictor = MotionEventPredictor.newInstance(surface)
        surface.setOnTouchListener { v, ev -> onTouch(v, ev); true }
        surface.setOnHoverListener { _, ev -> onHover(ev); false }
        menuAnchor = View(this)

        val canvasLayer = FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            addView(menuAnchor, FrameLayout.LayoutParams(1, 1))
            addView(buildSelectionBar(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dpi(12) })
            addView(buildPageNav(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dpi(16) })
        }
        topBar = buildTopBar()
        topDivider = View(this).apply { setBackgroundColor(ui.divider) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bar)
            addView(topBar, LinearLayout.LayoutParams(-1, ui.dpi(52)))
            addView(topDivider, LinearLayout.LayoutParams(-1, ui.dpi(1)))
            addView(buildDrawBar(), LinearLayout.LayoutParams(-1, ui.dpi(48)))
            addView(View(context).apply { setBackgroundColor(ui.divider) }, LinearLayout.LayoutParams(-1, ui.dpi(1)))
            addView(canvasLayer, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        setContentView(root)
        window.statusBarColor = ui.bar
        window.navigationBarColor = ui.bar
        setFullscreen(getSharedPreferences("ui", 0).getBoolean("fullscreen", false))
        setupDragAndDrop(surface)

        val uri = intent?.data ?: store.create(folder).uri
        if (intent?.data != null && intent.getStringExtra(EXTRA_FOLDER) == null) {
            // Opened from another app ("Open with"): keep access if the provider allows it.
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        open(uri, intent.getStringExtra(EXTRA_NAME) ?: queryName(uri)?.let { NoteStore.displayName(it) })
    }

    override fun onResume() { super.onResume(); redraw() }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(autosave)
        saveNow()
    }

    override fun onDestroy() { stopFling(); velocity?.recycle(); velocity = null; Live.detach(this); Live.unlisten(liveListener); surface.release(); pdfs.close(); super.onDestroy() }

    // =============================================================== live mirror

    override fun liveTitle(): String = titleView.text.toString()
    override fun liveScene(): ExcalidrawScene = scene
    override fun livePdfPage(file: String, page: Int, w: Int, h: Int): android.graphics.Bitmap? = pdfs.renderNow(file, page, w, h)

    private var liveButton: ImageView? = null
    private val liveListener: () -> Unit = { updateLiveButton() }

    private fun updateLiveButton() {
        val b = liveButton ?: return
        val on = Live.state == Live.State.LIVE
        val color = when (Live.state) { Live.State.LIVE -> Color.parseColor("#2f9e44"); Live.State.CONNECTING -> Color.parseColor("#f08c00"); Live.State.FAILED -> Color.parseColor("#e03131"); else -> ui.text }
        b.setImageDrawable(IconDrawable(Icon.LIVE, color))
        b.contentDescription = if (on) "Live (${Live.viewers} watching)" else "Live"
    }

    /** The stroke being drawn, as an Excalidraw element (for viewers). */
    private fun wetElement(): kotlinx.serialization.json.JsonObject {
        val pen = activePen()
        val n = sampleCount
        val pts = (0 until n).map { floatArrayOf(samples[it * 3], samples[it * 3 + 1], samples[it * 3 + 2]) }
        val frameId = if (meta.paged && n > 0) Pages.pageAt(Pages.pages(scene), pts[0][0].toDouble(), pts[0][1].toDouble())?.id else null
        val brush = activeProfile().id.takeIf { it != "pen" }
        val pts2 = if (pts.size == 1) listOf(pts[0], pts[0]) else pts
        return ExcalidrawScene.newFreedraw(pts2, pen.color, pen.strokeWidth, FractionalIndex.after(scene.lastIndex()), pen.opacity, frameId, brush).json
    }

    private fun showLivePanel() {
        val pad = ui.dpi(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, ui.dpi(8), pad, 0) }
        val codeView = TextView(this).apply {
            textSize = 40f; typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.25f; gravity = Gravity.CENTER; setTextColor(ui.text)
            setTextIsSelectable(true)
        }
        val status = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(0, ui.dpi(4), 0, ui.dpi(10)) }
        val link = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(ui.accent); setTextIsSelectable(true) }
        val help = TextView(this).apply {
            textSize = 13f; gravity = Gravity.CENTER; setPadding(0, ui.dpi(12), 0, 0); alpha = 0.7f; setTextColor(ui.text)
            text = "Anyone with this code can watch the note you have open (read-only). Nothing is stored online."
        }
        box.addView(codeView); box.addView(status); box.addView(link); box.addView(help)
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Live").setView(box)
            .setPositiveButton(" ", null).setNeutralButton("New code", null).setNegativeButton("Share link", null).create()
        fun refresh() {
            codeView.text = Live.code(this)
            link.text = Live.viewerLink(this)
            status.text = when (Live.state) {
                Live.State.OFF -> "Not sharing"
                Live.State.CONNECTING -> "Connecting…"
                Live.State.LIVE -> if (Live.viewers == 1) "Live · 1 watching" else "Live · ${Live.viewers} watching"
                Live.State.FAILED -> Live.problem ?: "Couldn't connect"
            }
            status.setTextColor(when (Live.state) { Live.State.LIVE -> Color.parseColor("#2f9e44"); Live.State.FAILED -> Color.parseColor("#e03131"); else -> ui.text })
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.text = if (Live.wanted(this)) "Stop" else "Start"
        }
        val l: () -> Unit = { refresh() }
        dialog.setOnShowListener {
            refresh()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (Live.wanted(this)) Live.stop(this)
                else if (!Live.configured(this)) askRelayUrl { Live.start(this); Live.attach(this, this) }
                else { Live.start(this); Live.attach(this, this) }
                refresh()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { Live.newCode(this); refresh() }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, Live.viewerLink(this)), "Share live link"))
            }
        }
        link.setOnLongClickListener { askRelayUrl { if (Live.wanted(this)) { Live.stop(this); Live.start(this); Live.attach(this, this) } ; refresh() }; true }
        Live.listen(l)
        dialog.setOnDismissListener { Live.unlisten(l) }
        dialog.show()
    }

    /** The relay address is built in; this lets you point the app at another one (long-press the link). */
    private fun askRelayUrl(then: () -> Unit) {
        val input = android.widget.EditText(this).apply { setText(Live.relayUrl(this@EditorActivity)); hint = "https://inkcalidraw-live.<you>.workers.dev" }
        android.app.AlertDialog.Builder(this).setTitle("Live relay address").setView(input)
            .setPositiveButton("Save") { _, _ -> Live.setRelayUrl(this, input.text.toString()); then() }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen) setFullscreen(true) // re-hide after dialogs/popups
    }

    /** Fullscreen hides Android's status/navigation bars and the upper (note) bar; swipe from an edge shows the system bars briefly. */
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        getSharedPreferences("ui", 0).edit().putBoolean("fullscreen", on).apply()
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, !on)
        if (on) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        topDivider.visibility = topBar.visibility
        exitFullscreen.visibility = if (on) View.VISIBLE else View.GONE
    }

    // =============================================================== frame building

    private fun scale() = CanvasSurface.scaleOf(view)

    private fun visibleWorld(): Geometry.Box {
        val inv = Matrix().also { view.invert(it) }
        val r = RectF(0f, 0f, surface.width.toFloat(), surface.height.toFloat()); inv.mapRect(r)
        return Geometry.Box(r.left.toDouble(), r.top.toDouble(), r.right.toDouble(), r.bottom.toDouble())
    }

    private fun buildFrame(): CanvasSurface.Frame {
        val files = scene.files.toMap()
        val elements = scene.snapshot()
        val sel = selectionBox()
        return CanvasSurface.Frame(
            input = SceneRenderer.Input(
                elements, { id -> (files[id]?.get("dataURL") as? JsonPrimitive)?.content }, scene.backgroundColor,
                visible = if (surface.width > 0) visibleWorld().inflate(50.0) else null,
                offsetIds = selected, dx = dragDx, dy = dragDy, paged = meta.paged, deskColor = ui.desk,
                scale = scale(),
                pageImage = { f, px -> pdfs.get(f.pdfFile!!, f.pdfPage ?: 0, f.width, f.height, px) },
            ),
            matrix = Matrix(view),
            lasso = if (g == G.LASSO) lasso.toList() else null,
            selection = sel,
            handle = if (sel != null && singleSelectedImage() != null) RectF(sel.right - 9f / scale(), sel.bottom - 9f / scale(), sel.right + 9f / scale(), sel.bottom + 9f / scale()) else null,
            eraser = eraserCursor,
            hint = hint,
        )
    }

    private fun redraw() {
        if (!::surface.isInitialized) return
        surface.show(buildFrame())
        updatePageNav()
        if (surface.width > 0) {
            val a = toWorld(0f, 0f); val b = toWorld(surface.width.toFloat(), surface.height.toFloat())
            Live.viewChanged(a[0], a[1], b[0] - a[0], b[1] - a[1])
        }
    }

    // =============================================================== input: dispatch

    private fun isPen(t: Int) = t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    private fun draws(t: Int) = isPen(t) || (!penOnly && t == MotionEvent.TOOL_TYPE_FINGER)

    /** Main-thread time spent handling touch events (read by the device tests). */
    @Volatile var touchNanos = 0L      // wall time
    @Volatile var touchCpuNanos = 0L   // main-thread CPU time (excludes time the emulator/OS gave other threads)
    @Volatile var touchEvents = 0

    private fun onTouch(v: View, ev: MotionEvent) {
        val t0 = System.nanoTime(); val c0 = android.os.Debug.threadCpuTimeNanos()
        try { handleTouch(v, ev) } finally {
            touchNanos += System.nanoTime() - t0; touchCpuNanos += android.os.Debug.threadCpuTimeNanos() - c0; touchEvents++
        }
    }

    private fun handleTouch(v: View, ev: MotionEvent) {
        val idx = ev.actionIndex
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                animator?.cancel(); stopFling()
                val type = ev.getToolType(idx)
                // With finger drawing on, a second finger turns a fresh finger stroke into navigation.
                val drawIdx = if (drawPointer != -1) ev.findPointerIndex(drawPointer) else -1
                if (!penOnly && drawIdx >= 0 && type == MotionEvent.TOOL_TYPE_FINGER && ev.getToolType(drawIdx) == MotionEvent.TOOL_TYPE_FINGER) {
                    abortDrawing(ev); resetFingers(ev); return
                }
                // The pen always draws (palm or navigating fingers may be down); a finger only draws alone.
                if (drawPointer == -1 && draws(type) && (isPen(type) || fingerCount(ev, idx) == 0)) {
                    drawPointer = ev.getPointerId(idx)
                    drawDown(v, ev, idx, type)
                    return
                }
                if (drawPointer == -1) resetFingers(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawPointer != -1) {
                    val p = ev.findPointerIndex(drawPointer)
                    if (p >= 0) drawMove(ev, p)
                    return
                }
                fingerMove(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (ev.getPointerId(idx) == drawPointer) {
                    drawUp(ev, idx, canceled = (ev.flags and MotionEvent.FLAG_CANCELED) != 0)
                    return
                }
                if (drawPointer == -1) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) fingersReleased() else resetFingers(ev, exclude = idx)
                }
            }
            MotionEvent.ACTION_CANCEL -> if (drawPointer != -1) abortDrawing(ev) else fingersReleased()
        }
    }

    /** Fingers other than [exclude] currently down that navigate. */
    private fun fingerCount(ev: MotionEvent, exclude: Int = -1) =
        (0 until ev.pointerCount).count { it != exclude && ev.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }

    private fun abortDrawing(ev: MotionEvent) {
        if (g == G.INK) { surface.cancelWet(); Live.wetEnd() }
        main.removeCallbacks(holdForMenu)
        g = G.NONE; drawPointer = -1; eraserCursor = null; lasso.clear(); dragDx = 0f; dragDy = 0f
        redraw()
    }

    private var lastPenNear = 0L

    private fun onHover(ev: MotionEvent) {
        if (!isPen(ev.getToolType(0))) return
        lastPenNear = SystemClock.uptimeMillis()
        val erasing = tool == Tool.ERASER || ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val show = erasing && ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        if (!show && eraserCursor == null) return // nothing to update: don't re-render the scene on every hover move
        eraserCursor = if (show) floatArrayOf(ev.x, ev.y, eraserRadiusPx()) else null
        redraw()
    }

    private fun eraserRadiusPx() = ui.dp(14)

    // =============================================================== input: drawing pointer

    private val inverse = Matrix()
    private fun toWorld(sx: Float, sy: Float) = floatArrayOf(sx, sy).also { view.invert(inverse); inverse.mapPoints(it) }

    private fun drawDown(v: View, ev: MotionEvent, idx: Int, toolType: Int) {
        val sx = ev.getX(idx); val sy = ev.getY(idx)
        downX = sx; downY = sy
        val w = toWorld(sx, sy)
        lastWorld = w
        val button = toolType == MotionEvent.TOOL_TYPE_STYLUS && (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val sel = selectionBox()
        g = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> G.ERASE
            button -> G.BUTTON
            resizeHandleHit(sx, sy) -> G.RESIZE
            sel != null && sel.contains(w[0], w[1]) -> G.DRAG
            lassoArmed || tool == Tool.SELECT -> G.LASSO
            tool == Tool.ERASER -> G.ERASE
            else -> G.INK
        }
        when (g) {
            G.BUTTON -> { main.postDelayed(holdForMenu, 450); eraserCursor = floatArrayOf(sx, sy, eraserRadiusPx()); redraw() }
            G.ERASE -> { eraseSnapshot = false; eraseSweep(w, w); eraserCursor = floatArrayOf(sx, sy, eraserRadiusPx()); redraw() }
            G.DRAG -> { lastWorld = w }
            G.RESIZE -> { pushUndo(); resizeIndex = scene.elements.indexOfFirst { it.id == selected.single() } }
            G.LASSO -> { setSelection(emptySet()); lasso.clear(); lasso.add(w); redraw() }
            G.INK -> {
                if (meta.paged && Pages.pageAt(Pages.pages(scene), w[0].toDouble(), w[1].toDouble()) == null) { g = G.NONE; return }
                if (selected.isNotEmpty()) setSelection(emptySet())
                v.requestUnbufferedDispatch(ev)
                strokeCounter++
                sampleCount = 0
                addSample(w[0], w[1], pressureOf(ev, idx, -1))
                predictor.record(ev)
                renderWet(null)
            }
            else -> {}
        }
    }

    private fun pressureOf(ev: MotionEvent, idx: Int, h: Int): Float {
        val p = if (h < 0) ev.getPressure(idx) else ev.getHistoricalPressure(idx, h)
        // Constant-width profiles store 0.5 everywhere, which Excalidraw also renders at constant width.
        return if (ev.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER || !activeProfile().pressure) 0.5f else p.coerceIn(0f, 1f)
    }

    private fun addSample(x: Float, y: Float, p: Float) {
        if (sampleCount > 0 && samples[(sampleCount - 1) * 3] == x && samples[(sampleCount - 1) * 3 + 1] == y) return
        if ((sampleCount + 1) * 3 > samples.size) samples = samples.copyOf(samples.size * 2)
        samples[sampleCount * 3] = x; samples[sampleCount * 3 + 1] = y; samples[sampleCount * 3 + 2] = p
        sampleCount++
    }

    private fun renderWet(predicted: MotionEvent?) {
        val pen = activePen()
        var arr = samples.copyOf(sampleCount * 3 + (predicted?.let { (it.historySize + 1) * 3 } ?: 0))
        var total = sampleCount
        if (predicted != null) {
            val pi = predicted.findPointerIndex(drawPointer).takeIf { it >= 0 } ?: 0
            fun push(sx: Float, sy: Float, p: Float) {
                val w = toWorld(sx, sy)
                if ((total + 1) * 3 > arr.size) arr = arr.copyOf(arr.size * 2 + 3)
                arr[total * 3] = w[0]; arr[total * 3 + 1] = w[1]; arr[total * 3 + 2] = p; total++
            }
            for (h in 0 until predicted.historySize) push(predicted.getHistoricalX(pi, h), predicted.getHistoricalY(pi, h), pressureOf(predicted, pi, h))
            push(predicted.getX(pi), predicted.getY(pi), pressureOf(predicted, pi, -1))
        }
        surface.renderWet(CanvasSurface.Wet(
            strokeCounter, arr, sampleCount, total,
            ui.sceneColor(SceneRenderer.parseColor(pen.color, Color.BLACK)), pen.strokeWidth, pen.opacity * 255 / 100, Matrix(view),
            activeProfile().grain,
        ))
        Live.wet { wetElement() }
    }

    private fun drawMove(ev: MotionEvent, p: Int) {
        when (g) {
            G.INK -> {
                for (h in 0 until ev.historySize) { val w = toWorld(ev.getHistoricalX(p, h), ev.getHistoricalY(p, h)); addSample(w[0], w[1], pressureOf(ev, p, h)) }
                val w = toWorld(ev.getX(p), ev.getY(p)); addSample(w[0], w[1], pressureOf(ev, p, -1))
                predictor.record(ev)
                val predicted = predictor.predict()
                try { renderWet(predicted) } finally { predicted?.recycle() }
            }
            G.BUTTON -> {
                if (hypot(ev.getX(p) - downX, ev.getY(p) - downY) > ui.dp(6)) {
                    main.removeCallbacks(holdForMenu)
                    g = G.ERASE; eraseSnapshot = false
                    eraseAlong(ev, p)
                } else { eraserCursor = floatArrayOf(ev.getX(p), ev.getY(p), eraserRadiusPx()); }
            }
            G.ERASE -> eraseAlong(ev, p)
            G.LASSO -> {
                for (h in 0 until ev.historySize) lassoTo(toWorld(ev.getHistoricalX(p, h), ev.getHistoricalY(p, h)))
                lassoTo(toWorld(ev.getX(p), ev.getY(p)))
                redraw()
            }
            G.DRAG -> {
                val w = toWorld(ev.getX(p), ev.getY(p))
                dragDx = w[0] - lastWorld[0]; dragDy = w[1] - lastWorld[1]
                redraw()
            }
            G.RESIZE -> resizeTo(toWorld(ev.getX(p), ev.getY(p)))
            G.NONE -> {}
        }
    }

    private fun drawUp(ev: MotionEvent, idx: Int, canceled: Boolean) {
        main.removeCallbacks(holdForMenu)
        if (isPen(ev.getToolType(idx))) lastPenNear = SystemClock.uptimeMillis()
        when (g) {
            G.INK -> if (canceled) { surface.cancelWet(); Live.wetEnd() } else {
                for (h in 0 until ev.historySize) { val w = toWorld(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h)); addSample(w[0], w[1], pressureOf(ev, idx, h)) }
                commitStroke()
            }
            G.BUTTON -> if (!canceled) { eraseSnapshot = false; val w = toWorld(ev.getX(idx), ev.getY(idx)); eraseSweep(w, w) }
            G.LASSO -> { if (!canceled) setSelection(elementsInLasso()); lassoArmed = false; updateToolButtons() }
            G.DRAG -> commitDrag(canceled)
            G.RESIZE -> { resizeIndex = -1; markDirty() }
            else -> {}
        }
        g = G.NONE; drawPointer = -1; eraserCursor = null; lasso.clear()
        redraw()
    }

    private fun commitStroke() {
        if (sampleCount == 0) { surface.cancelWet(); Live.wetEnd(); return }
        val pen = activePen()
        val pts = (0 until sampleCount).map { floatArrayOf(samples[it * 3], samples[it * 3 + 1], samples[it * 3 + 2]) }
        val frameId = if (meta.paged) Pages.pageAt(Pages.pages(scene), pts[0][0].toDouble(), pts[0][1].toDouble())?.id else null
        pushUndo()
        val brush = activeProfile().id.takeIf { it != "pen" }
        scene.elements.add(ExcalidrawScene.newFreedraw(pts, pen.color, pen.strokeWidth, FractionalIndex.after(scene.lastIndex()), pen.opacity, frameId, brush))
        markDirty()
        Live.wetEnd()
        surface.commitWet(buildFrame()) // scene with the new stroke replaces the wet ink atomically
    }

    // =============================================================== pen button menu

    private fun onButtonHold() {
        if (g != G.BUTTON) return
        g = G.NONE; eraserCursor = null; redraw()
        val at = toWorld(downX, downY)
        menuAnchor.x = downX; menuAnchor.y = downY
        PopupMenu(this, menuAnchor).apply {
            menu.add("Lasso select")
            menu.add("Paste here")
            menu.add(if (meta.paged) "Select all on page" else "Select all")
            if (selected.isNotEmpty()) { menu.add("Duplicate selection"); menu.add("Delete selection") }
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Lasso select" -> { lassoArmed = true; toast("Draw a lasso around what you want to select") }
                    "Paste here" -> pasteFromClipboard(at)
                    "Select all" -> setSelection(scene.elements.filter { e -> !e.isDeleted && !e.locked && e.type != "frame" }.map { e -> e.id }.toSet())
                    "Select all on page" -> {
                        val page = Pages.pageAt(Pages.pages(scene), at[0].toDouble(), at[1].toDouble())
                        setSelection(scene.elements.filter { e -> !e.isDeleted && !e.locked && e.type != "frame" && (page == null || e.frameId == page.id) }.map { e -> e.id }.toSet())
                    }
                    "Duplicate selection" -> duplicateSelection()
                    "Delete selection" -> deleteSelection()
                }
                true
            }
        }.show()
    }

    // =============================================================== eraser

    private fun eraseAlong(ev: MotionEvent, p: Int) {
        var prev = lastWorld
        for (h in 0 until ev.historySize) { val w = toWorld(ev.getHistoricalX(p, h), ev.getHistoricalY(p, h)); eraseSweep(prev, w, redrawNow = false); prev = w }
        val w = toWorld(ev.getX(p), ev.getY(p)); eraseSweep(prev, w, redrawNow = false)
        eraserCursor = floatArrayOf(ev.getX(p), ev.getY(p), eraserRadiusPx())
        redraw()
    }

    /** Deletes every element the eraser circle touched while sweeping from a to b. */
    private fun eraseSweep(a: FloatArray, b: FloatArray, redrawNow: Boolean = true) {
        lastWorld = b
        val r = (eraserRadiusPx() / scale()).toDouble()
        val ax = a[0].toDouble(); val ay = a[1].toDouble(); val bx = b[0].toDouble(); val by = b[1].toDouble()
        var erased: MutableSet<String>? = null
        val els = scene.elements
        for (i in els.indices) {
            val el = els[i]
            if (el.isDeleted || el.locked || el.type == "frame") continue
            if (Geometry.eraserHits(el, ax, ay, bx, by, r)) {
                if (!eraseSnapshot) { pushUndo(); eraseSnapshot = true }
                els[i] = el.withChanges("isDeleted" to JsonPrimitive(true))
                (erased ?: HashSet<String>().also { erased = it }).add(el.id)
            }
        }
        val gone = erased ?: return
        if (selected.isNotEmpty()) selected = selected - gone
        Live.sceneChanged()
        dirty = true; main.removeCallbacks(autosave); main.postDelayed(autosave, 1500)
        if (redrawNow) redraw()
    }

    // =============================================================== selection

    private fun lassoTo(w: FloatArray) {
        val last = lasso.last()
        if (hypot(w[0] - last[0], w[1] - last[1]) * scale() < 2f) return
        lasso.add(w)
    }

    private fun elementsInLasso(): Set<String> {
        if (lasso.size < 3) return emptySet()
        val poly = lasso.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }
        val ids = mutableSetOf<String>()
        for (el in scene.elements) {
            if (el.isDeleted || el.locked || el.type == "frame") continue
            val pts: List<DoubleArray> = when (el.type) {
                "freedraw", "line", "arrow" -> el.points.map { doubleArrayOf(el.x + it[0], el.y + it[1]) }
                else -> listOf(doubleArrayOf(el.x + el.width / 2, el.y + el.height / 2))
            }
            if (pts.isEmpty()) continue
            if (pts.count { Geometry.pointInPolygon(it[0], it[1], poly) } >= pts.size * 0.6) ids += el.id
        }
        return ids
    }

    private fun selectionBox(): RectF? {
        if (selected.isEmpty()) return null
        val b = Geometry.contentBounds(scene.elements.filter { it.id in selected }) ?: return null
        return RectF(b.minX.toFloat() + dragDx, b.minY.toFloat() + dragDy, b.maxX.toFloat() + dragDx, b.maxY.toFloat() + dragDy)
    }

    private fun singleSelectedImage(): Element? =
        selected.singleOrNull()?.let { id -> scene.elements.firstOrNull { it.id == id && it.type == "image" } }

    private fun resizeHandleHit(sx: Float, sy: Float): Boolean {
        if (singleSelectedImage() == null) return false
        val r = selectionBox() ?: return false
        val pt = floatArrayOf(r.right, r.bottom); view.mapPoints(pt)
        return hypot(pt[0] - sx, pt[1] - sy) < ui.dp(28)
    }

    private fun resizeTo(w: FloatArray) {
        val i = resizeIndex
        if (i < 0) return
        val el = scene.elements[i]
        val aspect = if (el.width > 0) el.height / el.width else 1.0
        val nw = maxOf(16.0 / scale(), maxOf(w[0] - el.x, (w[1] - el.y) / aspect))
        scene.elements[i] = el.withChanges("width" to JsonPrimitive(nw), "height" to JsonPrimitive(nw * aspect))
        redraw()
    }

    private fun setSelection(ids: Set<String>) {
        selected = ids
        dragDx = 0f; dragDy = 0f
        selectionBar.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        selectionCount.text = "${ids.size} selected"
        redraw()
    }

    private fun commitDrag(canceled: Boolean) {
        val dx = dragDx; val dy = dragDy
        dragDx = 0f; dragDy = 0f
        if (canceled || (dx == 0f && dy == 0f)) return
        pushUndo()
        val pages = Pages.pages(scene)
        for (i in scene.elements.indices) {
            val el = scene.elements[i]
            if (el.id !in selected) continue
            val moved = el.withChanges("x" to JsonPrimitive(el.x + dx), "y" to JsonPrimitive(el.y + dy))
            scene.elements[i] = if (meta.paged) moved.withChanges("frameId" to (Pages.frameFor(pages, moved)?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)) else moved
        }
        markDirty()
    }

    private fun deleteSelection() {
        if (selected.isEmpty()) return
        pushUndo()
        for (i in scene.elements.indices) if (scene.elements[i].id in selected) scene.elements[i] = scene.elements[i].withChanges("isDeleted" to JsonPrimitive(true))
        setSelection(emptySet()); markDirty()
    }

    private fun duplicateSelection() {
        if (selected.isEmpty()) return
        pushUndo()
        val offset = 24.0 / scale()
        val copies = scene.elements.filter { it.id in selected && !it.isDeleted }.map { el ->
            el.copyAsNew(FractionalIndex.after(scene.lastIndex())).withChanges("x" to JsonPrimitive(el.x + offset), "y" to JsonPrimitive(el.y + offset))
                .also { scene.elements.add(it) }
        }
        setSelection(copies.map { it.id }.toSet()); markDirty()
    }

    private fun recolorSelection() {
        if (selected.isEmpty()) return
        pushUndo()
        val c = activePen().color
        for (i in scene.elements.indices) if (scene.elements[i].id in selected && scene.elements[i].type != "image") scene.elements[i] = scene.elements[i].withChanges("strokeColor" to JsonPrimitive(c))
        markDirty()
    }

    // =============================================================== finger navigation

    private fun navFingers(ev: MotionEvent, exclude: Int = -1) =
        (0 until ev.pointerCount).filter { it != exclude && ev.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }

    /** Scroll gain for a finger moving at [v] px/s (vertical). */
    private fun scrollGain(v: Float): Float {
        val speed = abs(v) / resources.displayMetrics.density // dp/s
        return 1f + 1.5f * ((speed - 450f) / 1350f).coerceIn(0f, 1f)
    }

    private fun stopFling() {
        flingAnim?.cancel(); flingAnim = null
        fling.forceFinished(true)
    }

    /** Vertical scroll range (matrix translateY) that keeps some content on screen; null = nothing to scroll. */
    private fun verticalRange(): Pair<Int, Int>? {
        val s = scale()
        val (top, bottom) = if (meta.paged) {
            val pages = Pages.pages(scene).ifEmpty { return null }
            pages.minOf { it.y } to pages.maxOf { it.y + it.h }
        } else {
            val b = Geometry.contentBounds(scene.elements) ?: return null
            b.minY - surface.height / s to b.maxY + surface.height / s * 0.5
        }
        val margin = ui.dp(16)
        val maxTy = margin - top.toFloat() * s                          // first page's top at the top of the screen
        val minTy = surface.height - margin - bottom.toFloat() * s      // last page's bottom at the bottom
        if (minTy >= maxTy) return null
        return minTy.toInt() to maxTy.toInt()
    }

    /** Keeps scrolling after a fast vertical swipe, slowing down; stops at the first/last page or on touch. */
    private fun startFling(vy: Float) {
        val range = verticalRange() ?: return
        val v = FloatArray(9).also { view.getValues(it) }
        val ty = v[Matrix.MTRANS_Y].toInt()
        if (ty < range.first || ty > range.second) return
        stopFling()
        fling.fling(0, ty, 0, vy.toInt(), 0, 0, range.first, range.second)
        var last = ty
        flingAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                if (!fling.computeScrollOffset()) { stopFling(); return@addUpdateListener }
                val y = fling.currY
                view.postTranslate(0f, (y - last).toFloat()); last = y
                redraw()
            }
            start()
        }
    }

    private fun resetFingers(ev: MotionEvent, exclude: Int = -1) {
        val f = navFingers(ev, exclude)
        lastFingerCount = f.size
        if (f.isEmpty()) return
        lastFocusX = f.map { ev.getX(it) }.average().toFloat()
        lastFocusY = f.map { ev.getY(it) }.average().toFloat()
        lastSpan = span(ev, f, lastFocusX, lastFocusY)
        lastMoveTime = SystemClock.uptimeMillis()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            zoomedThisGesture = false; fingerVx = 0f; fingerVy = 0f
            velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
        }
        velocity?.addMovement(ev)
    }

    private fun span(ev: MotionEvent, f: List<Int>, cx: Float, cy: Float) =
        if (f.size < 2) 0f else f.map { hypot(ev.getX(it) - cx, ev.getY(it) - cy) }.average().toFloat()

    private fun fingerMove(ev: MotionEvent) {
        val f = navFingers(ev)
        if (f.isEmpty()) return
        (velocity ?: android.view.VelocityTracker.obtain().also { velocity = it }).addMovement(ev)
        if (f.size != lastFingerCount) { resetFingers(ev); return }
        // Palm rejection: a hand resting while the pen hovers shouldn't scroll the page.
        if (SystemClock.uptimeMillis() - lastPenNear < 400) { lastFocusX = f.map { ev.getX(it) }.average().toFloat(); lastFocusY = f.map { ev.getY(it) }.average().toFloat(); return }
        // Finger drawing on: one finger draws, so navigation needs two.
        if (!penOnly && f.size < 2) return
        val fx = f.map { ev.getX(it) }.average().toFloat()
        val fy = f.map { ev.getY(it) }.average().toFloat()
        val s = span(ev, f, fx, fy)
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastMoveTime).coerceAtLeast(1)
        fingerVx = 0.7f * fingerVx + 0.3f * ((fx - lastFocusX) * 1000f / dt)
        // finger speed from the events' own timestamps (touch moves arrive batched, so handling time is unreliable)
        velocity?.let { vt -> vt.computeCurrentVelocity(1000, 30000f); fingerVy = f.map { vt.getYVelocity(ev.getPointerId(it)) }.average().toFloat() }
        lastMoveTime = now
        // Vertical acceleration: 1:1 when moving slowly (precise), up to 2.5x for fast swipes. Horizontal stays 1:1,
        // and is off entirely while the width is locked ("Fit width").
        val dy = (fy - lastFocusY) * (if (f.size == 1) scrollGain(fingerVy) else 1f) // pinching: keep content under the fingers
        val dx = if (widthLocked) 0f else fx - lastFocusX
        view.postTranslate(dx, dy)
        if (f.size >= 2 && lastSpan > 0f && s > 0f) {
            val cur = scale()
            val k = (cur * (s / lastSpan)).coerceIn(0.05f, 30f) / cur
            if (abs(k - 1f) > 0.002f) { zoomedThisGesture = true; widthLocked = false }
            view.postScale(k, k, fx, fy)
        }
        lastFocusX = fx; lastFocusY = fy; lastSpan = s
        hint = if (meta.paged && overscrollPastLastPage() > 0.28f) "Release to add a page" else null
        redraw()
    }

    /** How far (as a fraction of the viewport) the view is pulled past the end of the last page. */
    private fun overscrollPastLastPage(): Float {
        val last = Pages.pages(scene).lastOrNull() ?: return 0f
        val pt = floatArrayOf((last.x + last.w).toFloat(), (last.y + last.h).toFloat()); view.mapPoints(pt)
        return if (meta.direction == PageDirection.VERTICAL) (surface.height - pt[1]) / surface.height
        else (surface.width - pt[0]) / surface.width
    }

    private fun fingersReleased() {
        lastFingerCount = 0
        // vertical momentum (not after pinching, not while "release to add a page" is showing)
        val vt = velocity; velocity = null
        var vy = 0f
        if (vt != null) { vt.computeCurrentVelocity(1000, 30000f); vy = if (abs(fingerVy) > abs(vt.yVelocity)) fingerVy else vt.yVelocity; vt.recycle() }
        lastReleaseVelocity = vy
        val flingUp = !zoomedThisGesture && hint == null && abs(vy) > 900 * resources.displayMetrics.density &&
            (!meta.paged || meta.direction == PageDirection.VERTICAL || abs(vy) > abs(fingerVx) * 1.5f)
        if (flingUp) startFling(vy * scrollGain(vy))
        if (!meta.paged) { hint = null; redraw(); return }
        if (hint != null) {
            hint = null
            addPage(goTo = true)
            return
        }
        // Horizontal page mode: swipe/snap page by page, like Samsung Notes. Skip when zoomed into a page.
        if (meta.direction == PageDirection.HORIZONTAL && !zoomedThisGesture && !flingUp) {
            val pages = Pages.pages(scene)
            if (pages.isEmpty()) return
            val cur = currentPageIndex()
            val p = pages[cur]
            val fitsWidth = p.w * scale() <= surface.width * 1.05f
            if (fitsWidth) {
                val target = when {
                    fingerVx < -900 -> (cur + 1).coerceAtMost(pages.size - 1)
                    fingerVx > 900 -> (cur - 1).coerceAtLeast(0)
                    else -> cur
                }
                goToPage(target, keepScale = true)
            }
        }
    }

    // =============================================================== pages

    private fun currentPageIndex(): Int {
        val c = toWorld(surface.width / 2f, surface.height / 2f)
        return Pages.nearest(Pages.pages(scene), c[0].toDouble(), c[1].toDouble()).coerceAtLeast(0)
    }

    private fun updatePageNav() {
        if (!::pageNav.isInitialized) return
        val pages = if (meta.paged) Pages.pages(scene) else emptyList()
        pageNav.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
        if (pages.isNotEmpty()) pageLabel.text = "${currentPageIndex() + 1} / ${pages.size}"
    }

    private fun animateTo(target: Matrix) {
        animator?.cancel()
        val from = FloatArray(9).also { view.getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260; interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                view.setValues(FloatArray(9) { from[it] + (to[it] - from[it]) * t })
                redraw()
            }
            start()
        }
    }

    private fun goToPage(i: Int, keepScale: Boolean) {
        if (!keepScale) widthLocked = false
        val pages = Pages.pages(scene)
        val p = pages.getOrNull(i) ?: return
        val vw = surface.width.toFloat(); val vh = surface.height.toFloat()
        val s = if (keepScale) scale() else minOf(vw / p.w.toFloat(), vh / p.h.toFloat()) * 0.94f
        val m = Matrix()
        m.setScale(s, s)
        val pw = p.w.toFloat() * s; val ph = p.h.toFloat() * s
        val tx = (vw - pw) / 2 - p.x.toFloat() * s
        val ty = if (meta.direction == PageDirection.VERTICAL || ph > vh) ui.dp(16) - p.y.toFloat() * s else (vh - ph) / 2 - p.y.toFloat() * s
        m.postTranslate(tx, ty)
        animateTo(m)
    }

    private fun fitPage() { widthLocked = false; goToPage(currentPageIndex(), keepScale = false) }

    private fun fitWidth() {
        val p = Pages.pages(scene).getOrNull(currentPageIndex()) ?: return
        val vw = surface.width.toFloat()
        val s = vw / p.w.toFloat()
        val centerWorldY = toWorld(0f, surface.height / 2f)[1]
        val m = Matrix().apply { setScale(s, s); postTranslate((vw - p.w.toFloat() * s) / 2 - p.x.toFloat() * s, surface.height / 2f - centerWorldY * s) }
        widthLocked = true
        animateTo(m)
    }

    private fun fitContent() {
        widthLocked = false
        val b = Geometry.contentBounds(scene.elements)
        val m = Matrix()
        if (b != null && surface.width > 0) {
            val bb = b.inflate(40.0)
            val s = minOf(surface.width / bb.width, surface.height / bb.height, 1.0).toFloat()
            m.setTranslate(-bb.minX.toFloat(), -bb.minY.toFloat()); m.postScale(s, s)
        }
        animateTo(m)
    }

    private fun addPage(goTo: Boolean) {
        pushUndo()
        Pages.addPage(scene, meta.orientation, meta.direction)
        if (!meta.paged) meta = meta.copy(paged = true)
        markDirty()
        if (goTo) goToPage(Pages.pages(scene).size - 1, keepScale = true) else redraw()
    }

    /** Inserts a page so it becomes page [position] (1-based) and shows it. Works the same in PDF notes. */
    private fun insertPage(position: Int, paper: String? = null) {
        pushUndo()
        val pages = Pages.pages(scene)
        val currentPaper = pages.getOrNull(currentPageIndex())?.let { p -> scene.elements.first { it.id == p.id }.backgroundKind }?.takeIf { it in Pages.PAPERS } ?: "blank"
        Pages.insertPage(scene, position, meta.direction, paper = paper ?: currentPaper)
        if (!meta.paged) meta = meta.copy(paged = true)
        markDirty()
        goToPage(position - 1, keepScale = true)
    }

    private fun showPageMenu(anchor: View) {
        val pages = Pages.pages(scene)
        val i = currentPageIndex()
        val page = pages.getOrNull(i) ?: return
        val frame = scene.elements.first { it.id == page.id }
        PopupMenu(this, anchor).apply {
            menu.add("Insert page before")
            menu.add("Insert page after")
            menu.add("Duplicate page")
            if (i > 0) menu.add("Move page earlier")
            if (i < pages.size - 1) menu.add("Move page later")
            val paper = menu.addSubMenu("Paper")
            for (k in Pages.PAPERS) paper.add(2, 0, 0, k.replaceFirstChar { it.uppercase() }).apply { isCheckable = true; isChecked = frame.backgroundKind == k }
            paper.setGroupCheckable(2, true, true)
            menu.add("Fit page")
            menu.add("Delete page")
            setOnMenuItemClickListener {
                when (val t = it.title.toString()) {
                    "Insert page before" -> insertPage(i + 1)
                    "Insert page after" -> insertPage(i + 2)
                    "Duplicate page" -> { pushUndo(); Pages.duplicatePage(scene, page.id, meta.direction); markDirty(); goToPage(i + 1, true) }
                    "Move page earlier" -> { pushUndo(); Pages.movePage(scene, page.id, -1, meta.direction); markDirty(); goToPage(i - 1, true) }
                    "Move page later" -> { pushUndo(); Pages.movePage(scene, page.id, +1, meta.direction); markDirty(); goToPage(i + 1, true) }
                    "Fit page" -> fitPage()
                    "Delete page" -> android.app.AlertDialog.Builder(this@EditorActivity).setMessage("Delete page ${i + 1} and everything on it?")
                        .setPositiveButton("Delete") { _, _ -> pushUndo(); Pages.deletePage(scene, page.id, meta.direction); markDirty(); goToPage((i - 1).coerceAtLeast(0), true) }
                        .setNegativeButton("Cancel", null).show()
                    else -> if (t.lowercase() in Pages.PAPERS) {
                        if (frame.pdfFile != null) toast("This page shows a PDF page; insert a new page for paper")
                        else { pushUndo(); Pages.setPaper(scene, page.id, t.lowercase()); markDirty() }
                    }
                }
                true
            }
        }.show()
    }

    private fun setDirection(d: PageDirection) {
        if (d == meta.direction) return
        pushUndo()
        meta = meta.copy(direction = d)
        Pages.relayout(scene, d)
        markDirty()
        main.post { fitPage() }
    }

    // =============================================================== images & PDF

    private fun pasteFromClipboard(at: FloatArray? = null) {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) { toast("Clipboard is empty"); return }
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val mime = contentResolver.getType(uri)
            if (mime == null || mime.startsWith("image/")) { insertImage(uri, at); return }
        }
        toast("No image in the clipboard")
    }

    private fun insertImage(uri: Uri, at: FloatArray?) {
        val center = at ?: toWorld(surface.width / 2f, surface.height / 2f)
        val visibleW = surface.width / scale()
        val visibleH = surface.height / scale()
        io.execute {
            try {
                val img = ImageImport.load(contentResolver, uri)
                runOnUiThread {
                    pushUndo()
                    scene.addFile(img.fileId, img.mimeType, img.dataUrl)
                    val pages = Pages.pages(scene)
                    val page = if (meta.paged) Pages.pageAt(pages, center[0].toDouble(), center[1].toDouble()) ?: pages.getOrNull(currentPageIndex()) else null
                    val maxW = page?.w?.times(0.8) ?: (visibleW * 0.6).toDouble()
                    val maxH = page?.h?.times(0.8) ?: (visibleH * 0.6).toDouble()
                    val k = minOf(1.0, maxW / img.width, maxH / img.height)
                    val w = img.width * k; val h = img.height * k
                    val el = ExcalidrawScene.newImage(img.fileId, center[0] - w / 2, center[1] - h / 2, w, h, FractionalIndex.after(scene.lastIndex()), frameId = page?.id)
                    scene.elements.add(el)
                    setSelection(setOf(el.id))
                    markDirty()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Couldn't insert image: ${e.message}") }
            }
        }
    }

    private fun insertPdf(uri: Uri) {
        val firstNew = Pages.pages(scene).size
        val name = queryName(uri) ?: "document.pdf"
        toast("Adding PDF…")
        io.execute {
            try {
                // Build the new pages on a copy so the UI thread's scene isn't touched concurrently.
                val tmp = ExcalidrawScene(kotlinx.serialization.json.JsonObject(emptyMap()), scene.snapshot().toMutableList())
                val before = tmp.elements.size
                val n = PdfSupport.import(this, store, folder, uri, name, tmp, meta.direction)
                val added = tmp.elements.drop(before)
                runOnUiThread {
                    pushUndo()
                    scene.elements.addAll(added)
                    meta = meta.copy(paged = true)
                    markDirty()
                    toast("Added $n page${if (n == 1) "" else "s"}")
                    main.post { goToPage(firstNew, keepScale = false) }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Couldn't import PDF: ${e.message}") }
            }
        }
    }

    /** For a file opened from elsewhere: its folder, if we can see it (file:// paths), so PDFs next to it resolve. */
    private fun externalFolder(u: Uri?): androidx.documentfile.provider.DocumentFile? {
        if (u == null || u.scheme != "file") return null
        val parent = java.io.File(u.path ?: return null).parentFile ?: return null
        return androidx.documentfile.provider.DocumentFile.fromFile(parent)
    }

    private fun queryName(u: Uri): String? = runCatching {
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun writePdf(uri: Uri) {
        val snapshot = scene.copyForSave()
        toast("Exporting PDF…")
        io.execute {
            try {
                contentResolver.openOutputStream(uri, "wt")!!.use { PdfSupport.export(snapshot, pdfs, it) }
                runOnUiThread { toast("PDF exported") }
            } catch (e: Exception) { runOnUiThread { toast("Export failed: ${e.message}") } }
        }
    }

    private fun setupDragAndDrop(target: View) {
        target.setOnDragListener { _, ev ->
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    ev.clipDescription?.let { d -> (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") || d.getMimeType(it) == "application/pdf" } } ?: false
                DragEvent.ACTION_DROP -> {
                    requestDragAndDropPermissions(ev)
                    val uri = ev.clipData?.let { c -> (0 until c.itemCount).firstNotNullOfOrNull { c.getItemAt(it).uri } }
                    if (uri != null) {
                        if (contentResolver.getType(uri) == "application/pdf") insertPdf(uri) else insertImage(uri, toWorld(ev.x, ev.y))
                    }
                    uri != null
                }
                else -> true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) when (keyCode) {
            KeyEvent.KEYCODE_V -> { pasteFromClipboard(); return true }
            KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) doRedo() else doUndo(); return true }
            KeyEvent.KEYCODE_Y -> { doRedo(); return true }
            KeyEvent.KEYCODE_D -> { duplicateSelection(); return true }
            KeyEvent.KEYCODE_A -> { setSelection(scene.elements.filter { !it.isDeleted && !it.locked && it.type != "frame" }.map { it.id }.toSet()); return true }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_PASTE -> { pasteFromClipboard(); return true }
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> if (selected.isNotEmpty()) { deleteSelection(); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> if (meta.paged) { goToPage(currentPageIndex() + 1, true); return true }
            KeyEvent.KEYCODE_PAGE_UP -> if (meta.paged) { goToPage(currentPageIndex() - 1, true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // =============================================================== undo / persistence

    private fun pushUndo() {
        undo.addLast(scene.elements.toList()); if (undo.size > 200) undo.removeFirst()
        redo.clear()
    }

    private fun doUndo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(scene.elements.toList())
        scene.elements.clear(); scene.elements.addAll(prev)
        setSelection(emptySet()); markDirty()
    }

    private fun doRedo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(scene.elements.toList())
        scene.elements.clear(); scene.elements.addAll(next)
        setSelection(emptySet()); markDirty()
    }

    /** Scene changed: schedule autosave and redraw. */
    private fun markDirty() {
        Live.sceneChanged()
        dirty = true
        main.removeCallbacks(autosave)
        main.postDelayed(autosave, 1500)
        redraw()
    }

    private fun metaChanged() { dirty = true; main.removeCallbacks(autosave); main.postDelayed(autosave, 1500) }

    private fun open(uri: Uri, name: String?) {
        titleView.text = name ?: NoteStore.displayName(uri.lastPathSegment?.substringAfterLast('/') ?: "Note")
        io.execute {
            val parsed = runCatching { ExcalidrawScene.parse(store.read(uri)) }
            runOnUiThread {
                parsed.onSuccess { loaded(uri, it) }.onFailure { toast("Couldn't open: ${it.message}"); finish() }
            }
        }
    }

    private fun loaded(uri: Uri, parsed: ExcalidrawScene) {
        // Parse points/bounds of every element in the background, so the first eraser or lasso
        // gesture doesn't pay for it.
        val warm = parsed.snapshot(); io.execute { warm.forEach { it.bounds } }
        try {
            scene = parsed
            meta = NoteMeta.readFrom(scene)
            tool = Tool.entries.firstOrNull { it.key == meta.tool } ?: Tool.PEN
            noteUri = uri
            undo.clear(); redo.clear(); setSelection(emptySet())
            updateToolButtons(); updateColorViews()
            surface.post { if (meta.paged) goToPage(0, keepScale = false) else fitContentNow() }
            Live.attach(this, this)
        } catch (e: Exception) {
            toast("Couldn't open: ${e.message}")
            finish()
        }
    }

    private fun fitContentNow() { fitContent(); animator?.end() }

    private fun saveNow() {
        if (!dirty) return
        Live.sceneChanged() // catches any change that didn't go through markDirty
        val uri = noteUri ?: return
        meta.copy(tool = tool.key).writeTo(scene)
        val copy = scene.copyForSave() // JSON encoding happens off the UI thread
        dirty = false
        io.execute {
            val json = copy.toJson()
            try { store.write(uri, json) } catch (e: Exception) {
                // The other app shared the file read-only: keep the work as a note instead of losing it.
                runCatching {
                    val copy = store.create(store.root(), titleView.text.toString(), json)
                    runOnUiThread { noteUri = copy.uri; toast("That file is read-only, so your changes were saved as a copy in Notes") }
                }
            }
        }
    }

    // =============================================================== toolbar & overlays

    private fun iconButton(icon: Icon, desc: String, small: Boolean = false, onClick: (ImageView) -> Unit) = ImageView(this).apply {
        setImageDrawable(IconDrawable(icon, ui.text))
        contentDescription = desc
        val pad = if (small) 8 else 9
        setPadding(ui.dpi(pad), ui.dpi(pad), ui.dpi(pad), ui.dpi(pad))
        background = null
        setOnClickListener { onClick(this) }
        val size = if (small) 40 else 44
        layoutParams = LinearLayout.LayoutParams(ui.dpi(size), ui.dpi(size)).apply { marginEnd = ui.dpi(2) }
    }

    private fun separator() = View(this).apply {
        setBackgroundColor(ui.divider)
        layoutParams = LinearLayout.LayoutParams(ui.dpi(1), ui.dpi(28)).apply { setMargins(ui.dpi(8), 0, ui.dpi(8), 0) }
    }

    private fun selectTool(t: Tool) {
        tool = t; lassoArmed = false
        if (t != Tool.SELECT && selected.isNotEmpty()) setSelection(emptySet())
        updateToolButtons(); updateColorViews(); metaChanged()
    }

    private fun updateToolButtons() {
        toolButtons.forEach { (k, b) -> b.background = if (k == tool || (k == Tool.SELECT && lassoArmed)) ui.rounded(ui.accentSoft, 10) else null }
    }

    private fun penTool() = if (tool == Tool.HIGHLIGHTER) Tool.HIGHLIGHTER else Tool.PEN

    private fun activeProfile(): PenProfile = if (tool == Tool.HIGHLIGHTER) PenProfiles.highlighter else PenProfiles.byId(meta.profile)
    private fun activePen(): PenConfig = meta.penFor(activeProfile().id)

    private fun setPen(update: (PenConfig) -> PenConfig) {
        if (tool != Tool.PEN && tool != Tool.HIGHLIGHTER) { tool = Tool.PEN; updateToolButtons() }
        val id = activeProfile().id
        meta = meta.withPen(id, update(meta.penFor(id)))
        updateColorViews(); metaChanged()
    }

    private fun setProfile(id: String) {
        meta = meta.copy(profile = id)
        selectTool(Tool.PEN)
        updateColorViews(); metaChanged()
    }

    private fun useColor(c: String) {
        colors.use(c)
        setPen { it.copy(color = c) }
    }

    private fun updateColorViews() {
        if (!::colorDot.isInitialized) return
        val pen = activePen()
        colorDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(ui.sceneColor(SceneRenderer.parseColor(pen.color, Color.BLACK)))
            setStroke(ui.dpi(2), ui.accent)
        }
        recentRow.removeAllViews()
        colors.list().filter { !it.equals(pen.color, true) }.take(3).forEach { c ->
            recentRow.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(ui.sceneColor(SceneRenderer.parseColor(c, Color.BLACK))); setStroke(ui.dpi(1), ui.divider)
                }
                contentDescription = c
                setOnClickListener { useColor(c) }
            }, LinearLayout.LayoutParams(ui.dpi(24), ui.dpi(24)).apply { setMargins(ui.dpi(4), 0, ui.dpi(4), 0) })
        }
    }

    private fun barRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dpi(4), 0, ui.dpi(8), 0); setBackgroundColor(ui.bar)
    }

    private fun scrollable(row: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; setBackgroundColor(ui.bar); isFillViewport = true
        addView(row, ViewGroup.LayoutParams(-2, -1))
    }

    /** Upper bar: the note itself (name, pages, view, export). Hidden in fullscreen. */
    private fun buildTopBar(): View {
        val bar = barRow()
        bar.addView(iconButton(Icon.BACK, "Back to notes") { finish() })
        titleView = TextView(this).apply {
            textSize = 17f; setTextColor(ui.text); maxLines = 1; setPadding(ui.dpi(2), 0, ui.dpi(12), 0)
            setOnClickListener { renameNote() }
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(iconButton(Icon.PAGES, "Add page") { if (!meta.paged) meta = meta.copy(paged = true); addPage(goTo = true) })
        bar.addView(iconButton(Icon.FIT, "Fit") { showFitMenu(it) })
        bar.addView(iconButton(Icon.PDF, "Insert PDF") { pickPdf.launch(arrayOf("application/pdf")) })
        bar.addView(iconButton(Icon.EXPORT, "Export as PDF") { exportPdf.launch(titleView.text.toString() + ".pdf") })
        liveButton = iconButton(Icon.LIVE, "Live") { showLivePanel() }.also { bar.addView(it) }
        Live.listen(liveListener); updateLiveButton()
        bar.addView(iconButton(Icon.FULLSCREEN, "Fullscreen") { setFullscreen(true) })
        bar.addView(iconButton(Icon.MORE, "More") { showMoreMenu(it) })
        return bar
    }

    /** Lower, compact bar: everything for drawing. Stays visible in fullscreen. */
    private fun buildDrawBar(): View {
        val bar = barRow()
        for ((t, icon, label) in listOf(Triple(Tool.PEN, Icon.PEN, "Pen"), Triple(Tool.HIGHLIGHTER, Icon.HIGHLIGHTER, "Highlighter"), Triple(Tool.ERASER, Icon.ERASER, "Eraser"), Triple(Tool.SELECT, Icon.LASSO, "Lasso select"))) {
            val b = iconButton(icon, label, small = true) {
                when {
                    t == Tool.PEN && tool == Tool.PEN -> showProfiles(it)
                    t == Tool.HIGHLIGHTER && tool == Tool.HIGHLIGHTER -> showWidths(it)
                    else -> selectTool(t)
                }
            }
            if (t == Tool.PEN) b.setOnLongClickListener { showProfiles(it); true }
            toolButtons[t] = b; bar.addView(b)
        }
        bar.addView(separator())
        colorDot = View(this).apply {
            contentDescription = "Color"
            setOnClickListener { v -> showColorPopup(v, ui, activePen().color, colors.list()) { useColor(it) } }
        }
        bar.addView(colorDot, LinearLayout.LayoutParams(ui.dpi(28), ui.dpi(28)).apply { setMargins(ui.dpi(6), 0, ui.dpi(4), 0) })
        recentRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(recentRow)
        bar.addView(iconButton(Icon.WIDTH, "Thickness", small = true) { showWidths(it) })
        bar.addView(separator())
        bar.addView(iconButton(Icon.PASTE, "Paste image", small = true) { pasteFromClipboard() })
        bar.addView(iconButton(Icon.UNDO, "Undo", small = true) { doUndo() })
        bar.addView(iconButton(Icon.REDO, "Redo", small = true) { doRedo() })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        exitFullscreen = iconButton(Icon.FULLSCREEN_EXIT, "Exit fullscreen", small = true) { setFullscreen(false) }
        bar.addView(exitFullscreen)
        updateToolButtons(); updateColorViews()
        return scrollable(bar)
    }

    private fun renameNote() {
        val uri = noteUri ?: return
        val input = android.widget.EditText(this).apply { setText(titleView.text); selectAll() }
        android.app.AlertDialog.Builder(this).setTitle("Rename note").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                saveNow()
                val newName = input.text.toString()
                io.execute {
                    val nu = runCatching { store.rename(uri, folder, newName) }.getOrNull()
                    runOnUiThread { if (nu != null) { noteUri = nu; titleView.text = newName.trim(); Live.titleChanged() } else toast("Rename failed") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showWidths(anchor: View) {
        val pen = activePen(); val prof = activeProfile()
        showThicknessPopup(anchor, ui, prof.widths, pen.strokeWidth, pen.color, pen.opacity, prof.grain) { w -> setPen { it.copy(strokeWidth = w) } }
    }

    private fun showProfiles(anchor: View) {
        showProfilePopup(anchor, ui, PenProfiles.all, meta.profile, { meta.penFor(it) }) { setProfile(it) }
    }

    private fun showFitMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            if (meta.paged) { menu.add("Fit page"); menu.add("Fit width") } else menu.add("Fit drawing")
            menu.add("Zoom 100%")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Fit page" -> fitPage(); "Fit width" -> fitWidth(); "Fit drawing" -> fitContent()
                    "Zoom 100%" -> { widthLocked = false; val c = toWorld(surface.width / 2f, surface.height / 2f); animateTo(Matrix().apply { setTranslate(surface.width / 2f - c[0], surface.height / 2f - c[1]) }) }
                }
                true
            }
        }.show()
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Draw with pen only").apply { isCheckable = true; isChecked = penOnly }
            val theme = menu.addSubMenu("Dark mode")
            val cur = Ui.themeSetting(this@EditorActivity)
            for ((k, l) in listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark")) theme.add(1, 0, 0, l).apply { isCheckable = true; isChecked = cur == k }
            theme.setGroupCheckable(1, true, true)
            if (meta.paged) {
                menu.add("Add page")
                menu.add(if (meta.direction == PageDirection.VERTICAL) "Scroll pages horizontally" else "Scroll pages vertically")
                menu.add(if (meta.orientation == PageOrientation.PORTRAIT) "New pages: landscape" else "New pages: portrait")
            } else menu.add("Add A4 pages to this note")
            menu.add(if (fullscreen) "Exit fullscreen" else "Fullscreen")
            menu.add("Insert PDF…")
            menu.add("Export as PDF…")
            setOnMenuItemClickListener {
                val t = it.title.toString()
                when {
                    it.itemId == 1 -> { penOnly = !penOnly; getSharedPreferences("ui", 0).edit().putBoolean("penOnly", penOnly).apply(); toast(if (penOnly) "Fingers navigate, only the pen draws" else "Fingers draw too; use two fingers to navigate") }
                    t == "Follow system" -> { Ui.setThemeSetting(this@EditorActivity, "system"); saveNow(); recreate() }
                    t == "Light" -> { Ui.setThemeSetting(this@EditorActivity, "light"); saveNow(); recreate() }
                    t == "Dark" -> { Ui.setThemeSetting(this@EditorActivity, "dark"); saveNow(); recreate() }
                    t == "Add page" -> addPage(goTo = true)
                    t == "Add A4 pages to this note" -> { meta = meta.copy(paged = true); addPage(goTo = true) }
                    t == "Scroll pages horizontally" -> setDirection(PageDirection.HORIZONTAL)
                    t == "Scroll pages vertically" -> setDirection(PageDirection.VERTICAL)
                    t == "New pages: landscape" -> { meta = meta.copy(orientation = PageOrientation.LANDSCAPE); metaChanged() }
                    t == "New pages: portrait" -> { meta = meta.copy(orientation = PageOrientation.PORTRAIT); metaChanged() }
                    t == "Fullscreen" -> setFullscreen(true)
                    t == "Exit fullscreen" -> setFullscreen(false)
                    t == "Insert PDF…" -> pickPdf.launch(arrayOf("application/pdf"))
                    t == "Export as PDF…" -> exportPdf.launch(titleView.text.toString() + ".pdf")
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
        }.show()
    }

    private fun buildSelectionBar(): View {
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(ui.surface, 24, ui.divider); elevation = ui.dp(6)
            setPadding(ui.dpi(14), ui.dpi(2), ui.dpi(6), ui.dpi(2))
            visibility = View.GONE
        }
        selectionCount = TextView(this).apply { textSize = 14f; setTextColor(ui.subtext); setPadding(0, 0, ui.dpi(6), 0) }
        selectionBar.addView(selectionCount)
        selectionBar.addView(TextView(this).apply {
            text = "Recolor"; textSize = 14f; setTextColor(ui.accent); setPadding(ui.dpi(10), ui.dpi(10), ui.dpi(10), ui.dpi(10))
            setOnClickListener { recolorSelection() }
        })
        selectionBar.addView(iconButton(Icon.COPY, "Duplicate") { duplicateSelection() })
        selectionBar.addView(iconButton(Icon.TRASH, "Delete") { deleteSelection() })
        selectionBar.addView(iconButton(Icon.CHECK, "Done") { setSelection(emptySet()) })
        return selectionBar
    }

    private fun buildPageNav(): View {
        pageNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(ui.surface, 24, ui.divider); elevation = ui.dp(6)
            setPadding(ui.dpi(4), 0, ui.dpi(4), 0)
            visibility = View.GONE
        }
        pageNav.addView(iconButton(Icon.PREV, "Previous page") { goToPage(currentPageIndex() - 1, keepScale = true) })
        pageLabel = TextView(this).apply {
            textSize = 14f; setTextColor(ui.text); gravity = Gravity.CENTER; minWidth = ui.dpi(64)
            setOnClickListener { showPageMenu(it) }
        }
        pageNav.addView(pageLabel)
        pageNav.addView(iconButton(Icon.NEXT, "Next page") { goToPage(currentPageIndex() + 1, keepScale = true) })
        pageNav.addView(iconButton(Icon.PLUS, "Add page") { insertPage(currentPageIndex() + 2) })
        pageNav.addView(iconButton(Icon.MORE, "Page options") { showPageMenu(it) })
        return pageNav
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { const val EXTRA_NAME = "name"; const val EXTRA_FOLDER = "folder"; const val EXTRA_FOLDER_PATH = "folderPath" }
}
