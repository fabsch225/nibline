package dev.inkcalidraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live mirror: streams the open note to the relay so it can be watched at <viewer page>#CODE.
 * Process-wide (survives switching notes); all public methods are called on the main thread.
 */
object Live {
    /** The open editor. */
    interface Source {
        fun liveTitle(): String
        fun liveScene(): ExcalidrawScene
        /** Renders a PDF page for viewers (called on a background thread). */
        fun livePdfPage(file: String, page: Int, w: Int, h: Int): Bitmap?
    }

    enum class State { OFF, CONNECTING, LIVE, FAILED }

    var state = State.OFF; private set
    var viewers = 0; private set
    var problem: String? = null; private set
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() { listeners.toList().forEach { it() } }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val http by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build() }
    private lateinit var app: Context
    private var ws: WebSocket? = null
    private var source: Source? = null
    private var retry = 0
    private val diff = LiveDiff()
    private val sentFiles = HashSet<String>()
    private var lastView: FloatArray? = null
    private var viewDue = false
    private var lastWet = 0L
    private var wetShown = false

    // ------------------------------------------------------------------ settings

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("live", 0)

    fun relayUrl(ctx: Context): String = prefs(ctx).getString("relay", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.LIVE_RELAY
    fun setRelayUrl(ctx: Context, url: String) = prefs(ctx).edit().putString("relay", url.trim().trimEnd('/')).apply()
    fun configured(ctx: Context) = relayUrl(ctx).isNotBlank()

    fun code(ctx: Context): String = prefs(ctx).getString("code", null) ?: LiveDiff.newCode().also { prefs(ctx).edit().putString("code", it).apply() }
    private fun hostKey(ctx: Context): String = prefs(ctx).getString("key", null) ?: LiveDiff.newHostKey().also { prefs(ctx).edit().putString("key", it).apply() }
    fun viewerLink(ctx: Context) = BuildConfig.LIVE_PAGE.trimEnd('/') + "/#" + code(ctx)

    /** Whether the user switched Live on (remembered until switched off). */
    fun wanted(ctx: Context) = prefs(ctx).getBoolean("on", false)

    // ------------------------------------------------------------------ control

    fun start(ctx: Context) {
        app = ctx.applicationContext
        prefs(app).edit().putBoolean("on", true).apply()
        problem = null
        connect()
    }

    fun stop(ctx: Context) {
        prefs(ctx).edit().putBoolean("on", false).apply()
        disconnect()
        state = State.OFF; problem = null; viewers = 0; changed()
    }

    /** A different code (e.g. to stop people who know the old one from watching). */
    fun newCode(ctx: Context) {
        prefs(ctx).edit().putString("code", LiveDiff.newCode()).putString("key", LiveDiff.newHostKey()).apply()
        if (wanted(ctx)) { disconnect(); start(ctx) } else changed()
    }

    fun attach(s: Source, ctx: Context) {
        app = ctx.applicationContext
        source = s
        if (wanted(app) && ws == null) connect() else sendFull()
    }

    fun detach(s: Source) { if (source === s) source = null }

    // ------------------------------------------------------------------ editor events

    fun sceneChanged() {
        val s = source ?: return
        if (state != State.LIVE) return
        val scene = s.liveScene()
        diff.changes(scene).forEach { send(it) }
        sendNewFiles(scene)
    }

    fun titleChanged() = sendFull()

    /** The stroke being drawn; [build] is only called when an update is due (~15 per second). */
    fun wet(build: () -> JsonObject) {
        if (state != State.LIVE) return
        val now = SystemClock.uptimeMillis()
        if (now - lastWet < 66) return
        lastWet = now; wetShown = true
        send(LiveDiff.wet(build()))
    }

    fun wetEnd() {
        if (!wetShown) return
        wetShown = false; lastWet = 0
        if (state == State.LIVE) send(LiveDiff.wet(null))
    }

    /** Visible part of the note (world coordinates). Throttled; the latest value always goes out. */
    fun viewChanged(x: Float, y: Float, w: Float, h: Float) {
        val v = floatArrayOf(x, y, w, h)
        val last = lastView
        if (last != null && (0..3).all { kotlin.math.abs(last[it] - v[it]) < 0.5f }) return
        lastView = v
        if (state != State.LIVE || viewDue) return
        viewDue = true
        main.postDelayed({ viewDue = false; flushView() }, 120)
    }

    private fun flushView() {
        val v = lastView ?: return
        if (state != State.LIVE) return
        send(LiveDiff.view(v[0], v[1], v[2], v[3]))
        sendVisiblePdfPages(v)
    }

    // ------------------------------------------------------------------ connection

    private fun connect() {
        if (!::app.isInitialized || !configured(app)) { state = State.FAILED; problem = "No relay configured"; changed(); return }
        disconnect()
        state = State.CONNECTING; changed()
        val url = relayUrl(app).replaceFirst(Regex("^http"), "ws") + "/live/" + code(app) + "?host=" + hostKey(app)
        val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = main.post {
                if (ws !== webSocket) return@post
                retry = 0; state = State.LIVE; problem = null; changed()
                sendFull()
            }.let { }

            override fun onMessage(webSocket: WebSocket, text: String) = main.post {
                if (ws !== webSocket) return@post
                if (text.contains("\"t\":\"viewers\"")) {
                    viewers = Regex("\"n\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: viewers
                    changed()
                }
            }.let { }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = main.post {
                if (ws !== webSocket) return@post
                ws = null; viewers = 0
                if (response?.code == 403) { state = State.FAILED; problem = "This code is in use by another device. Choose a new code."; changed(); return@post }
                state = State.CONNECTING; problem = t.message; changed()
                scheduleRetry()
            }.let { }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = main.post {
                if (ws !== webSocket) return@post
                ws = null; viewers = 0; state = State.CONNECTING; changed(); scheduleRetry()
            }.let { }
        })
        ws = socket
    }

    private fun scheduleRetry() {
        if (!wanted(app)) { state = State.OFF; changed(); return }
        val delay = minOf(1000L shl minOf(retry++, 4), 15_000L)
        main.postDelayed({ if (wanted(app) && ws == null) connect() }, delay)
    }

    private fun disconnect() {
        val s = ws ?: return
        ws = null
        s.close(1000, "stopped")
    }

    private fun send(text: String) { ws?.send(text) }

    // ------------------------------------------------------------------ content

    private fun sendFull() {
        val s = source ?: return
        if (state != State.LIVE) return
        sentFiles.clear()
        diff.full(s.liveTitle(), s.liveScene()).forEach { send(it) }
        sendNewFiles(s.liveScene())
        lastView?.let { send(LiveDiff.view(it[0], it[1], it[2], it[3])); sendVisiblePdfPages(it) }
    }

    /** Images in the scene (big ones re-encoded so a single message stays small). */
    private fun sendNewFiles(scene: ExcalidrawScene) {
        for ((id, f) in scene.files) {
            if (!sentFiles.add(id)) continue
            val mime = (f["mimeType"] as? JsonPrimitive)?.content ?: "image/png"
            val data = (f["dataURL"] as? JsonPrimitive)?.content ?: continue
            if (data.length < 1_200_000) { send(LiveDiff.file(id, mime, data)); continue }
            io.execute {
                val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                val url = jpeg(bmp, 1600)
                main.post { send(LiveDiff.file(id, "image/jpeg", url)) }
            }
        }
    }

    /** PDF pages are rendered for viewers only once they're (nearly) on the tablet's screen. */
    private fun sendVisiblePdfPages(v: FloatArray) {
        val s = source ?: return
        val mx = v[2] * 0.5f; val my = v[3] * 0.5f
        for (e in s.liveScene().elements) {
            val file = e.pdfFile ?: continue
            if (e.type != "frame" || e.isDeleted) continue
            if (e.x > v[0] + v[2] + mx || e.x + e.width < v[0] - mx || e.y > v[1] + v[3] + my || e.y + e.height < v[1] - my) continue
            val page = e.pdfPage ?: 0
            val id = LiveDiff.pdfFileId(file, page)
            if (!sentFiles.add(id)) continue
            val w = 1100; val h = (w * e.height / e.width).toInt().coerceAtLeast(1)
            io.execute {
                val bmp = runCatching { s.livePdfPage(file, page, w, h) }.getOrNull() ?: return@execute
                val url = jpeg(bmp, w, recycle = false)
                main.post { send(LiveDiff.file(id, "image/jpeg", url)) }
            }
        }
    }

    private fun jpeg(src: Bitmap, maxSide: Int, recycle: Boolean = true): String {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        // JPEG has no transparency: put it on white
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also { android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) } }
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, 78, out)
        flat.recycle(); if (bmp !== src) bmp.recycle(); if (recycle) src.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
