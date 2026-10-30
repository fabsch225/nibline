package dev.inkcalidraw

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Drives the editor on an emulator with synthetic S Pen input (tool type, pressure, side button) and
 * finger input, checks the resulting .excalidraw data, and saves screenshots for review.
 */
@RunWith(AndroidJUnit4::class)
class EditorDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val auto: UiAutomation get() = inst.uiAutomation
    private val device = UiDevice.getInstance(inst)
    private val shots = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
    private val report = StringBuilder()

    @Before fun setup() {
        Ui.setThemeSetting(ctx, "light")
        ctx.getSharedPreferences("ui", 0).edit().putBoolean("penOnly", true).commit()
    }

    // ------------------------------------------------------------------ helpers

    private val store get() = NoteStore(ctx)

    private fun note(json: String, name: String): Uri = store.create(store.root(), name, json).uri

    private fun launch(uri: Uri): ActivityScenario<EditorActivity> {
        val s = ActivityScenario.launch<EditorActivity>(Intent(ctx, EditorActivity::class.java).setData(uri).putExtra(EditorActivity.EXTRA_FOLDER, store.root().uri.toString()))
        // wait for the note to load and the first real frame to be on screen
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < 15_000) {
            var drawn = 0
            s.onActivity { a -> drawn = field<CanvasSurface>(a, "surface").framesDrawn }
            if (drawn > 1) break
            SystemClock.sleep(100)
        }
        SystemClock.sleep(700)
        return s
    }

    private fun <T> field(a: EditorActivity, name: String): T {
        val f = EditorActivity::class.java.getDeclaredField(name); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") return f.get(a) as T
    }

    private fun scene(s: ActivityScenario<EditorActivity>): ExcalidrawScene {
        var out: ExcalidrawScene? = null
        s.onActivity { out = field(it, "scene") }
        return out!!
    }

    private fun live(s: ActivityScenario<EditorActivity>, type: String) = scene(s).elements.count { it.type == type && !it.isDeleted }

    /** Screen rect of the drawing surface. */
    private fun surfaceRect(s: ActivityScenario<EditorActivity>): Rect {
        var r: Rect? = null
        s.onActivity { a ->
            val v: CanvasSurface = field(a, "surface")
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        return r!!
    }

    private fun event(action: Int, down: Long, x: Float, y: Float, pressure: Float, tool: Int, buttons: Int = 0): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 0.01f })
        val source = if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, buttons, 1f, 1f, 0, 0, source, 0)
    }

    private fun inject(e: MotionEvent) { auto.injectInputEvent(e, true); e.recycle() }

    /** Draws a polyline with the given tool; returns without lifting if [lift] is false. */
    private fun gesture(points: List<FloatArray>, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, buttons: Int = 0, lift: Boolean = true, stepMs: Long = 4): Long {
        val down = SystemClock.uptimeMillis()
        inject(event(MotionEvent.ACTION_DOWN, down, points[0][0], points[0][1], points[0][2], tool, buttons))
        for (p in points.drop(1)) { SystemClock.sleep(stepMs); inject(event(MotionEvent.ACTION_MOVE, down, p[0], p[1], p[2], tool, buttons)) }
        if (lift) inject(event(MotionEvent.ACTION_UP, down, points.last()[0], points.last()[1], points.last()[2], tool, buttons))
        return down
    }

    private fun wave(r: Rect, y: Float, n: Int = 90): List<FloatArray> = (0 until n).map { i ->
        val t = i / (n - 1f)
        floatArrayOf(r.left + r.width() * (0.38f + 0.24f * t), y + 40f * sin(t * 4 * PI).toFloat(), (0.2f + 0.7f * sin(t * PI).toFloat()))
    }

    private fun shot(name: String): Bitmap {
        SystemClock.sleep(400)
        var b: Bitmap? = null
        for (i in 0 until 5) { b = auto.takeScreenshot(); if (b != null) break; SystemClock.sleep(300) }
        b!!
        File(shots, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    private fun log(s: String) { Log.i("InkcalidrawTest", s); report.appendLine(s); File(shots, "report.txt").appendText(s + "\n") }

    // ------------------------------------------------------------------ tests

    /** The stroke must look the same while drawing (front buffer) and after lifting (committed). */
    @Test fun wetInkMatchesCommittedStroke() {
        val s = launch(note(Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson(), "wet-vs-dry"))
        val r = surfaceRect(s)
        val pts = wave(r, r.top + r.height() * 0.45f)
        val box = Rect(pts.minOf { it[0] }.toInt() - 30, pts.minOf { it[1] }.toInt() - 30, pts.maxOf { it[0] }.toInt() + 30, pts.maxOf { it[1] }.toInt() + 30)
        // On a freshly booted emulator the first screenshots can show a stale frame: retry until the ink is in it.
        fun inkIn(b: Bitmap): Int { var n = 0; for (y in box.top until box.bottom step 3) for (x in box.left until box.right step 3) if (Color.red(b.getPixel(x, y)) < 200) n++; return n }
        // ...and until two screenshots in a row agree, so a half-updated frame isn't compared.
        fun shotWithInk(name: String, nudge: () -> Unit = {}): Bitmap {
            var b = shot(name); var n = inkIn(b)
            for (i in 0 until 12) {
                nudge(); SystemClock.sleep(250)
                val b2 = shot(name); val n2 = inkIn(b2)
                if (n > 60 && n2 > 60 && kotlin.math.abs(n2 - n) <= maxOf(3, n / 50)) return b2
                b = b2; n = n2
            }
            return b
        }
        val down = gesture(pts, lift = false)
        val settle = { repeat(6) { SystemClock.sleep(8); inject(event(MotionEvent.ACTION_MOVE, down, pts.last()[0], pts.last()[1], pts.last()[2], MotionEvent.TOOL_TYPE_STYLUS)) } }
        // let the predictor settle on the last point, then capture the wet stroke
        settle()
        val wet = shotWithInk("01-wet-stroke", settle)
        inject(event(MotionEvent.ACTION_UP, down, pts.last()[0], pts.last()[1], pts.last()[2], MotionEvent.TOOL_TYPE_STYLUS))
        val dry = shotWithInk("02-committed-stroke")
        assertEquals(1, live(s, "freedraw"))

        // Compare the stroke area pixel by pixel (ignore the pen tip, where prediction may still differ).
        var inked = 0; var differ = 0
        for (y in box.top until box.bottom step 1) for (x in box.left until box.right step 1) {
            // Skip the pen tip: emulator screenshots can lag the front buffer by a frame there.
            if (hypot(x - pts.last()[0], y - pts.last()[1]) < 50 || x > pts.last()[0] - 150) continue
            val a = wet.getPixel(x, y); val b = dry.getPixel(x, y)
            val dark = Color.red(b) < 200 || Color.red(a) < 200
            if (dark) inked++
            val d = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
            if (d > 48) differ++
        }
        val frac = differ.toDouble() / maxOf(1, inked)
        log("wet-vs-dry: inked=$inked differing=$differ (${"%.2f".format(frac * 100)}%)")
        assertTrue("stroke drawn", inked > 500)
        assertTrue("wet and committed stroke differ in ${frac * 100}% of stroke pixels", frac < 0.03)
        s.close()
    }

    private fun abs(i: Int) = kotlin.math.abs(i)

    /** Side button = eraser, and a fast swipe whose samples skip over the stroke still erases it. */
    @Test fun sideButtonErasesWithFastSwipe() {
        val s = launch(note(ExcalidrawScene.empty().toJson(), "erase"))
        val r = surfaceRect(s)
        val y = r.top + r.height() * 0.5f
        gesture(wave(r, y))
        SystemClock.sleep(300)
        assertEquals(1, live(s, "freedraw"))
        // three samples, 300 px apart, crossing the stroke vertically
        val x = r.left + r.width() * 0.5f
        gesture(listOf(floatArrayOf(x, y - 300, .5f), floatArrayOf(x, y + 10, .5f), floatArrayOf(x, y + 320, .5f)), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY, stepMs = 16)
        SystemClock.sleep(300)
        shot("03-after-button-erase")
        log("button erase: freedraw left = ${live(s, "freedraw")}")
        assertEquals(0, live(s, "freedraw"))
        s.close()
    }

    /** Holding the side button still opens the select/paste menu instead of erasing. */
    @Test fun buttonHoldOpensMenu() {
        val s = launch(note(ExcalidrawScene.empty().toJson(), "menu"))
        val r = surfaceRect(s)
        val x = r.exactCenterX(); val y = r.exactCenterY()
        val down = SystemClock.uptimeMillis()
        inject(event(MotionEvent.ACTION_DOWN, down, x, y, .5f, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        SystemClock.sleep(800)
        val menu = device.wait(Until.findObject(By.text("Lasso select")), 2000)
        shot("04-pen-button-menu")
        inject(event(MotionEvent.ACTION_UP, down, x, y, .5f, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertNotNull("menu with 'Lasso select' shown", menu)
        assertNotNull(device.findObject(By.text("Paste here")))
        menu.click()
        SystemClock.sleep(300)
        // the next pen stroke is a lasso: draw one, then a circle around nothing selects nothing but must not ink
        gesture((0..40).map { i -> val a = i / 40.0 * 2 * PI; floatArrayOf((x + 120 * kotlin.math.cos(a)).toFloat(), (y + 120 * sin(a)).toFloat(), .5f) })
        SystemClock.sleep(300)
        assertEquals("lasso stroke must not be inked", 0, live(s, "freedraw"))
        s.close()
    }

    /** Pen-only mode: a finger drag scrolls, it never draws. */
    @Test fun fingerNavigatesInPenOnlyMode() {
        val s = launch(note(ExcalidrawScene.empty().toJson(), "finger"))
        val r = surfaceRect(s)
        var before = FloatArray(9)
        s.onActivity { before = FloatArray(9).also { v -> field<android.graphics.Matrix>(it, "view").getValues(v) } }
        gesture(wave(r, r.exactCenterY(), 30), tool = MotionEvent.TOOL_TYPE_FINGER, stepMs = 10)
        SystemClock.sleep(300)
        var after = FloatArray(9)
        s.onActivity { after = FloatArray(9).also { v -> field<android.graphics.Matrix>(it, "view").getValues(v) } }
        assertEquals(0, live(s, "freedraw"))
        assertTrue("finger moved the canvas", hypot(after[2] - before[2], after[5] - before[5]) > 50)
        s.close()
    }

    /** Paste an image from the clipboard via the toolbar button. */
    @Test fun pasteImageFromClipboard() {
        val png = File(ctx.cacheDir, "clip.png")
        Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.parseColor("#a5d8ff"))
            android.graphics.Canvas(this).drawText("clipboard image", 40f, 120f, Paint().apply { textSize = 36f })
        }.compress(Bitmap.CompressFormat.PNG, 100, png.outputStream())
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", png)
        val s = launch(note(Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson(), "paste"))
        s.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(it.contentResolver, "img", uri)) }
        device.findObject(By.desc("Paste image")).click()
        SystemClock.sleep(1500)
        shot("05-pasted-image")
        val sc = scene(s)
        log("paste: images=${live(s, "image")} files=${sc.files.size}")
        assertEquals(1, live(s, "image"))
        assertEquals(1, sc.files.size)
        assertNotNull("pasted onto the page", sc.elements.first { it.type == "image" }.frameId)
        s.close()
    }

    /** PDF import -> annotate -> export round trip, plus dark mode rendering of the same note. */
    @Test fun pdfAnnotateAndExport() {
        val pdf = File(ctx.cacheDir, "lecture.pdf")
        PdfDocument().apply {
            for (i in 1..3) {
                val p = startPage(PdfDocument.PageInfo.Builder(595, 842, i).create())
                p.canvas.drawText("Lecture slide $i", 60f, 100f, Paint().apply { textSize = 32f })
                p.canvas.drawRect(60f, 140f, 535f, 400f, Paint().apply { color = Color.parseColor("#1971c2") })
                finishPage(p)
            }
            writeTo(pdf.outputStream()); close()
        }
        val scene = ExcalidrawScene.empty()
        val n = PdfSupport.import(ctx, store, store.root(), Uri.fromFile(pdf), "lecture.pdf", scene, PageDirection.VERTICAL)
        NoteMeta(paged = true).writeTo(scene)
        assertEquals(3, n)
        val s = launch(note(scene.toJson(), "lecture"))
        val r = surfaceRect(s)
        gesture(wave(r, r.top + r.height() * 0.6f))
        SystemClock.sleep(400)
        shot("06-pdf-annotated")
        val sc = scene(s)
        assertEquals(3, Pages.pages(sc).size)
        assertNotNull("annotation bound to a page", sc.elements.last { it.type == "freedraw" }.frameId)

        val out = File(ctx.cacheDir, "export.pdf")
        val pdfs = PdfPages(ctx) { name -> store.findFile(store.root(), name) }
        out.outputStream().use { PdfSupport.export(sc, pdfs, it) }
        pdfs.close()
        PdfRenderer(ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(3, it.pageCount) }
        log("pdf: imported=$n exportedBytes=${out.length()}")
        s.close()

        Ui.setThemeSetting(ctx, "dark")
        val d = launch(note(sc.toJson(), "lecture-dark"))
        shot("07-dark-mode")
        d.close()
        Ui.setThemeSetting(ctx, "light")
    }

    /** A 300-page PDF must import and open quickly (pages are rendered on demand, not copied into the note). */
    @Test fun largePdfImportsFast() {
        val pdf = File(ctx.cacheDir, "big.pdf")
        PdfDocument().apply {
            val paint = Paint().apply { textSize = 28f }
            for (i in 1..300) { val p = startPage(PdfDocument.PageInfo.Builder(595, 842, i).create()); p.canvas.drawText("Page $i", 60f, 100f, paint); finishPage(p) }
            writeTo(pdf.outputStream()); close()
        }
        val t0 = SystemClock.uptimeMillis()
        val scene = ExcalidrawScene.empty()
        val n = PdfSupport.import(ctx, store, store.root(), Uri.fromFile(pdf), "big.pdf", scene, PageDirection.VERTICAL)
        NoteMeta(paged = true).writeTo(scene)
        val json = scene.toJson()
        val importMs = SystemClock.uptimeMillis() - t0
        val t1 = SystemClock.uptimeMillis()
        val s = launch(note(json, "big"))
        val openMs = SystemClock.uptimeMillis() - t1 - 700 // launch() waits for the first frame, then 700 ms
        shot("12-big-pdf")
        log("big pdf: pages=$n import=${importMs}ms noteSize=${json.length / 1024}KB open≈${openMs}ms")
        assertEquals(300, n)
        assertTrue("note stays small (${json.length} chars)", json.length < 400_000)
        assertTrue("import took $importMs ms", importMs < 30_000) // emulator; reads 300 page sizes
        s.close()
    }

    /** Eraser on a busy page: time spent handling a fast erase gesture. */
    @Test fun eraserStaysFastOnBusyNotes() {
        val scene = ExcalidrawScene.empty()
        for (i in 0 until 1500) {
            val ox = (i % 30) * 70f; val oy = (i / 30) * 60f
            scene.elements.add(ExcalidrawScene.newFreedraw((0 until 120).map { k -> floatArrayOf(ox + k * 0.5f, oy + 15 * sin(k / 8.0).toFloat(), .5f) }, "#1e1e1e", 1.5, FractionalIndex.after(scene.lastIndex())))
        }
        val s = launch(note(scene.toJson(), "busy"))
        SystemClock.sleep(1500)
        val r = surfaceRect(s)
        val pts = (0 until 120).map { i -> floatArrayOf(r.left + r.width() * (0.1f + 0.8f * i / 119f), r.top + r.height() * (0.3f + 0.4f * sin(i / 10.0).toFloat()), .5f) }
        s.onActivity { it.touchNanos = 0; it.touchCpuNanos = 0; it.touchEvents = 0 }
        val t0 = SystemClock.uptimeMillis()
        gesture(pts, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY, stepMs = 0)
        val ms = SystemClock.uptimeMillis() - t0
        SystemClock.sleep(500)
        shot("13-busy-erase")
        val left = live(s, "freedraw")
        var mainMs = 0.0; var cpuMs = 0.0; var n = 0
        s.onActivity { mainMs = it.touchNanos / 1e6; cpuMs = it.touchCpuNanos / 1e6; n = it.touchEvents }
        log("busy erase: $n events, main thread ${"%.2f".format(cpuMs / n)} ms CPU / ${"%.2f".format(mainMs / n)} ms wall per event (whole gesture ${"%.1f".format(ms / 120.0)} ms/event incl. emulator software GPU), erased ${1500 - left} strokes")
        assertTrue("erased something", left < 1500)
        // Budget: a 120 Hz frame is 8.3 ms; the app's own CPU work per event must stay well below that.
        assertTrue("eraser handling too slow: ${cpuMs / n} ms CPU/event", cpuMs / n < 4.0)
        s.close()
    }

    /** "Open with" from another app: the single file is edited in place. */
    @Test fun openWithEditsTheSharedFile() {
        val f = File(ctx.cacheDir, "Shared lecture.excalidraw")
        f.writeText(ExcalidrawScene.empty().toJson())
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        for (type in listOf("application/octet-stream", "application/json", "application/vnd.excalidraw+json")) {
            val handlers = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type), 0)
            assertTrue("we handle $type", handlers.any { it.activityInfo.name == EditorActivity::class.java.name })
        }
        val intent = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "application/octet-stream")
            .setClass(ctx, EditorActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val s = ActivityScenario.launch<EditorActivity>(intent)
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < 15_000) {
            var drawn = 0; s.onActivity { a -> drawn = field<CanvasSurface>(a, "surface").framesDrawn }
            if (drawn > 1) break; SystemClock.sleep(100)
        }
        SystemClock.sleep(500)
        val r = surfaceRect(s)
        gesture(wave(r, r.exactCenterY()))
        SystemClock.sleep(400)
        shot("16-open-with")
        s.close() // onPause saves
        SystemClock.sleep(1500)
        val saved = ExcalidrawScene.parse(f.readText())
        log("open with: file now has ${saved.elements.count { it.type == "freedraw" }} stroke(s)")
        assertEquals(1, saved.elements.count { it.type == "freedraw" && !it.isDeleted })
    }

    /** Runs [cmd] in a real shell (executeShellCommand alone splits on spaces and ignores quotes). */
    private fun sh(cmd: String): String {
        val (out, input) = auto.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write("$cmd\nexit\n".toByteArray()) }
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun clickText(vararg labels: String, timeout: Long = 5000): Boolean {
        val re = java.util.regex.Pattern.compile("(?i)(" + labels.joinToString("|") { java.util.regex.Pattern.quote(it) } + ")")
        val o = device.wait(Until.findObject(By.text(re)), timeout) ?: return false
        o.click(); return true
    }

    /**
     * The Obsidian plugin's "Edit in Inkcalidraw app": inkcalidraw://open?vault=…&path=… opens the vault file in
     * place. First time: the user grants the vault folder in Android's folder picker; afterwards it opens directly.
     */
    @Test fun obsidianLinkEditsVaultFileInPlace() {
        val tmp = File(ctx.getExternalFilesDir(null), "Week 3.excalidraw").apply { writeText(ExcalidrawScene.empty().toJson()) }
        val vaultFile = "/sdcard/Documents/Uni/Lectures/Week 3.excalidraw"
        sh("mkdir -p /sdcard/Documents/Uni/Lectures && cat '${tmp.absolutePath}' > '$vaultFile'")
        assertTrue("vault file prepared", sh("ls '/sdcard/Documents/Uni/Lectures/'").contains("Week 3.excalidraw"))
        val link = Uri.parse("inkcalidraw://open?vault=Uni&path=" + Uri.encode("Lectures/Week 3.excalidraw") + "&abs=" + Uri.encode("/storage/emulated/0/Documents/Uni"))
        val view = Intent(Intent.ACTION_VIEW, link).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertTrue("link handled by the app", ctx.packageManager.queryIntentActivities(view, 0).any { it.activityInfo.name == OpenLinkActivity::class.java.name })

        ctx.startActivity(view)
        assertNotNull("asks for the vault folder", device.wait(Until.findObject(By.textContains("Allow access")), 8000))
        shot("17-link-asks-for-vault")
        assertTrue(clickText("Choose folder"))
        SystemClock.sleep(2500); shot("18-folder-picker")
        // the picker should start in Documents/Uni; navigate there if it didn't
        if (device.findObject(By.text(java.util.regex.Pattern.compile("(?i)use this folder"))) == null || device.findObject(By.text("Lectures")) == null) {
            clickText("Documents", timeout = 3000); SystemClock.sleep(1200); clickText("Uni", timeout = 3000); SystemClock.sleep(1200)
            shot("18b-folder-picker-navigated")
        }
        assertTrue("use this folder", clickText("Use this folder", "Select", timeout = 5000))
        SystemClock.sleep(800); shot("19-allow")
        assertTrue("allow", clickText("Allow", timeout = 5000))
        assertNotNull("editor opened", device.wait(Until.findObject(By.desc("Pen")), 15000))
        SystemClock.sleep(1500)
        val w = device.displayWidth; val h = device.displayHeight
        gesture(wave(Rect(0, 0, w, h), h * 0.6f))
        SystemClock.sleep(500); shot("20-link-editing")
        device.pressBack()
        var strokes = 0
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < 8000) {
            strokes = runCatching { ExcalidrawScene.parse(sh("cat '$vaultFile'")).elements.count { it.type == "freedraw" && !it.isDeleted } }.getOrDefault(0)
            if (strokes > 0) break; SystemClock.sleep(300)
        }
        log("obsidian link: vault file now has $strokes stroke(s)")
        assertEquals("stroke saved into the vault file", 1, strokes)

        // second time: permission kept, opens straight away
        ctx.startActivity(view)
        assertNotNull("opens directly", device.wait(Until.findObject(By.desc("Pen")), 15000))
        assertEquals(null, device.findObject(By.textContains("Allow access")))
        shot("21-link-second-open")
        device.pressBack()
    }

    /**
     * Live mirror: with Live on, a viewer connected to the relay (run on the CI machine, reached at 10.0.2.2) receives
     * the note, the in-progress stroke, the finished stroke, erasing and the visible area. The received messages are saved
     * so CI can replay them into the real viewer page.
     */
    @Test fun liveMirrorsTheOpenNote() {
        val relay = InstrumentationRegistry.getArguments().getString("liveRelay")
        org.junit.Assume.assumeTrue("no relay given (-e liveRelay URL)", relay != null)
        inst.runOnMainSync { Live.stop(ctx); Live.setRelayUrl(ctx, relay!!); Live.newCode(ctx) }
        val code = Live.code(ctx)
        val msgs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val viewer = okhttp3.OkHttpClient().newWebSocket(okhttp3.Request.Builder().url(relay!!.replaceFirst("http", "ws") + "/live/$code").build(),
            object : okhttp3.WebSocketListener() { override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { msgs += text } })
        fun waitFor(what: String, ms: Long = 10_000, ok: () -> Boolean) {
            val t0 = SystemClock.uptimeMillis()
            while (!ok()) { if (SystemClock.uptimeMillis() - t0 > ms) throw AssertionError("timed out: $what (got ${msgs.map { it.take(60) }})"); SystemClock.sleep(50) }
        }
        val s = launch(note(Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson(), "Live lecture"))
        s.onActivity { a -> Live.start(a); Live.attach(a, a) }
        waitFor("tablet connected") { Live.state == Live.State.LIVE }
        waitFor("note arrives") { msgs.any { it.contains("\"t\":\"full\"") && it.contains("Live lecture") } }
        waitFor("tablet sees the viewer") { Live.viewers == 1 }
        val r = surfaceRect(s)
        // draw slowly so in-progress updates go out
        gesture(wave(r, r.exactCenterY()), stepMs = 14)
        waitFor("finished stroke") { msgs.any { it.startsWith("{\"t\":\"delta\"") && it.contains("\"type\":\"freedraw\"") } }
        val wet = msgs.count { it.startsWith("{\"t\":\"wet\"") && !it.contains("\"el\":null") }
        assertTrue("in-progress stroke updates sent: $wet", wet >= 3)
        waitFor("wet stroke ended") { msgs.any { it == "{\"t\":\"wet\",\"el\":null}" } }
        waitFor("visible area") { msgs.any { it.startsWith("{\"t\":\"view\"") } }
        shot("22-live-drawing")
        // a second stroke, then erase the first
        gesture(wave(r, r.exactCenterY() + 160))
        waitFor("second stroke") { msgs.filter { it.startsWith("{\"t\":\"delta\"") && it.contains("freedraw") }.size >= 2 }
        device.findObject(By.desc("Eraser")).click(); SystemClock.sleep(300)
        gesture((0 until 40).map { i -> floatArrayOf(r.left + r.width() * (0.36f + 0.28f * i / 39f), r.exactCenterY(), 0.5f) })
        waitFor("erase reaches the viewer") { msgs.any { it.startsWith("{\"t\":\"delta\"") && !it.contains("\"remove\":[]") } }
        device.findObject(By.desc("Pen")).click()
        log("live: ${msgs.size} messages to the viewer (${wet} in-progress updates), code $code")
        File(shots, "live-messages.jsonl").writeText(msgs.joinToString("\n"))
        inst.runOnMainSync { Live.stop(ctx) }
        viewer.close(1000, null)
        s.close()
    }

    /**
     * Bug report: after erasing or "Fit width" new strokes didn't show on screen (they were saved and reached Live
     * viewers). Draws, erases (eraser tool and S Pen button), fits the width, and checks each new stroke is visible.
     * Runs with Live off and, when a relay is given, with Live on.
     */
    @Test fun strokesStayVisibleAfterEraseAndFitWidth() {
        val relay = InstrumentationRegistry.getArguments().getString("liveRelay")
        for (live in listOfNotNull(false, if (relay != null) true else null)) {
            inst.runOnMainSync { Live.stop(ctx); if (live) { Live.setRelayUrl(ctx, relay!!); Live.newCode(ctx) } }
            val viewer = if (live) okhttp3.OkHttpClient().newWebSocket(okhttp3.Request.Builder().url(relay!!.replaceFirst("http", "ws") + "/live/" + Live.code(ctx)).build(), object : okhttp3.WebSocketListener() {}) else null
            val s = launch(note(Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson(), "visible-$live"))
            if (live) { s.onActivity { a -> Live.start(a); Live.attach(a, a) }; val t0 = SystemClock.uptimeMillis(); while (Live.state != Live.State.LIVE && SystemClock.uptimeMillis() - t0 < 10_000) SystemClock.sleep(50) }
            val tag = if (live) "live" else "plain"
            fun inkAround(r: Rect, y: Float): Int {
                val b = shot("23-$tag-check"); var n = 0
                for (yy in (y - 60).toInt() until (y + 60).toInt() step 2) for (xx in (r.left + r.width() * 0.36f).toInt() until (r.left + r.width() * 0.64f).toInt() step 2) if (Color.red(b.getPixel(xx, yy)) < 120) n++
                return n
            }
            fun drawAndCheck(step: String) {
                val r = surfaceRect(s)
                val y = r.top + r.height() * (0.25f + 0.5f * Math.random().toFloat())
                val before = inkAround(r, y)
                // select the pen directly: tapping "Pen" while it's already selected opens the pen-type menu
                s.onActivity { a -> EditorActivity::class.java.getDeclaredMethod("selectTool", EditorActivity.Tool::class.java).apply { isAccessible = true }.invoke(a, EditorActivity.Tool.PEN) }
                SystemClock.sleep(200)
                gesture(wave(r, y))
                var after = 0
                for (i in 0 until 8) { SystemClock.sleep(250); after = inkAround(r, y); if (after - before > 150) break }
                log("visible [$tag] $step: ink before=$before after=$after")
                shot("23-$tag-$step")
                assertTrue("[$tag] stroke visible after $step (ink $before -> $after)", after - before > 150)
            }
            drawAndCheck("first-stroke")
            // erase with the eraser tool across the middle
            val r = surfaceRect(s)
            device.findObject(By.desc("Eraser")).click(); SystemClock.sleep(300)
            gesture((0 until 60).map { i -> floatArrayOf(r.left + r.width() * 0.3f, r.top + r.height() * (0.2f + 0.6f * i / 59f), 0.5f) })
            SystemClock.sleep(400)
            drawAndCheck("after-eraser-tool")
            // erase with the S Pen button
            gesture((0 until 60).map { i -> floatArrayOf(r.left + r.width() * 0.5f, r.top + r.height() * (0.2f + 0.6f * i / 59f), 0.5f) }, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
            SystemClock.sleep(400)
            drawAndCheck("after-button-erase")
            // fit width
            device.findObject(By.desc("Fit")).click()
            device.wait(Until.findObject(By.text("Fit width")), 3000)!!.click()
            SystemClock.sleep(900)
            drawAndCheck("after-fit-width")
            inst.runOnMainSync { Live.stop(ctx) }
            viewer?.close(1000, null)
            s.close()
        }
    }

    /**
     * Regression test for the canvas freeze (render thread starved of buffers by back-to-back frames): after each of
     * these actions the canvas must keep rendering and a new stroke must reach the screen.
     */
    @Test fun canvasKeepsRenderingAfterEachAction() {
        val failures = ArrayList<String>()
        fun pick(a: EditorActivity, t: EditorActivity.Tool) = EditorActivity::class.java.getDeclaredMethod("selectTool", EditorActivity.Tool::class.java).apply { isAccessible = true }.invoke(a, t)
        val variants = linkedMapOf<String, (ActivityScenario<EditorActivity>, Rect) -> Unit>(
            "nothing" to { _, _ -> },
            "tap-eraser-button" to { _, _ -> device.findObject(By.desc("Eraser")).click(); SystemClock.sleep(400) },
            "erase-gesture-no-toolbar" to { s, r -> s.onActivity { pick(it, EditorActivity.Tool.ERASER) }; gesture((0 until 40).map { i -> floatArrayOf(r.left + r.width() * 0.3f, r.top + r.height() * (0.2f + 0.6f * i / 39f), 0.5f) }); SystemClock.sleep(400) },
            "button-erase-gesture" to { _, r -> gesture((0 until 40).map { i -> floatArrayOf(r.left + r.width() * 0.3f, r.top + r.height() * (0.2f + 0.6f * i / 39f), 0.5f) }, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY); SystemClock.sleep(400) },
            "tap-thickness-then-back" to { _, _ -> device.findObject(By.desc("Thickness")).click(); SystemClock.sleep(500); device.pressBack(); SystemClock.sleep(400) },
            "fit-width" to { _, _ -> device.findObject(By.desc("Fit")).click(); device.wait(Until.findObject(By.text("Fit width")), 3000)!!.click(); SystemClock.sleep(900) },
            "fit-width-no-menu" to { s, _ -> s.onActivity { EditorActivity::class.java.getDeclaredMethod("fitWidth").apply { isAccessible = true }.invoke(it) }; SystemClock.sleep(900) },
            "long-fast-erase" to { s, r -> s.onActivity { pick(it, EditorActivity.Tool.ERASER) }; gesture((0 until 400).map { i -> floatArrayOf(r.left + r.width() * (0.2f + 0.6f * (i % 50) / 49f), r.top + r.height() * (0.15f + 0.7f * i / 399f), 0.5f) }, stepMs = 2); SystemClock.sleep(400) },
            "fast-finger-pan" to { _, r -> gesture((0 until 200).map { i -> floatArrayOf(r.exactCenterX() + 300f * kotlin.math.sin(i / 10f), r.exactCenterY() + 200f * kotlin.math.cos(i / 13f), 0.5f) }, tool = MotionEvent.TOOL_TYPE_FINGER, stepMs = 2); SystemClock.sleep(400) },
        )
        for ((name, step) in variants) {
            val s = launch(note(Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson(), "diag-$name"))
            val r = surfaceRect(s)
            step(s, r)
            s.onActivity { pick(it, EditorActivity.Tool.PEN) }
            SystemClock.sleep(200)
            var f0 = 0; s.onActivity { f0 = field<CanvasSurface>(it, "surface").framesDrawn }
            val y = r.top + r.height() * 0.5f
            val before = shot("24-render-$name-before")
            gesture(wave(r, y))
            SystemClock.sleep(1200)
            val after = shot("24-render-$name-after")
            var f1 = 0; s.onActivity { f1 = field<CanvasSurface>(it, "surface").framesDrawn }
            var changed = 0
            for (yy in (y - 60).toInt() until (y + 60).toInt() step 2) for (xx in (r.left + r.width() * 0.36f).toInt() until (r.left + r.width() * 0.64f).toInt() step 2)
                if (abs(Color.red(after.getPixel(xx, yy)) - Color.red(before.getPixel(xx, yy))) > 60) changed++
            log("render check $name: strokes=${live(s, "freedraw")} frames ${f0}->${f1} changedPixels=$changed")
            if (f1 <= f0 || changed < 100) failures += "$name (frames $f0->$f1, changed pixels $changed)"
            if (f1 == f0) {
                // stuck: where are the app's threads? (the test runs in the app's process)
                for ((t, st) in Thread.getAllStackTraces()) {
                    if (st.none { it.className.startsWith("dev.inkcalidraw") || it.className.startsWith("androidx.graphics") }) continue
                    log("  thread '${t.name}' ${t.state}:\n" + st.take(28).joinToString("\n") { "      at $it" })
                }
            }
            s.close()
        }
        assertTrue("canvas stopped updating after: $failures", failures.isEmpty())
    }

    /**
     * Pencil and chalk: the stroke must look the same while drawing and after lifting the pen (the grain used to jump),
     * and pressure must show as darkness: a firm stroke is clearly darker than a light one.
     */
    @Test fun grainBrushesMatchWhileDrawingAndShowPressure() {
        for (brush in listOf("pencil", "chalk")) {
            val s = launch(note(Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL).toJson(), "grain-$brush"))
            s.onActivity { a ->
                val f = EditorActivity::class.java.getDeclaredField("meta").apply { isAccessible = true }
                f.set(a, (f.get(a) as NoteMeta).copy(profile = brush))
            }
            val r = surfaceRect(s)
            fun stable(name: String, box: Rect, nudge: () -> Unit = {}): Bitmap {
                fun ink(b: Bitmap): Int { var n = 0; for (y in box.top until box.bottom step 3) for (x in box.left until box.right step 3) if (Color.red(b.getPixel(x, y)) < 235) n++; return n }
                var b = shot(name); var n = ink(b)
                for (i in 0 until 12) { nudge(); SystemClock.sleep(250); val b2 = shot(name); val n2 = ink(b2); if (n > 30 && abs(n2 - n) <= maxOf(3, n / 50)) return b2; b = b2; n = n2 }
                return b
            }
            // 1) wet == dry
            val pts = wave(r, r.top + r.height() * 0.3f)
            val box = Rect(pts.minOf { it[0] }.toInt() - 30, pts.minOf { it[1] }.toInt() - 40, pts.maxOf { it[0] }.toInt() + 30, pts.maxOf { it[1] }.toInt() + 40)
            val down = gesture(pts, lift = false)
            val settle = { repeat(6) { SystemClock.sleep(8); inject(event(MotionEvent.ACTION_MOVE, down, pts.last()[0], pts.last()[1], pts.last()[2], MotionEvent.TOOL_TYPE_STYLUS)) } }
            settle()
            val wet = stable("25-$brush-wet", box, settle)
            inject(event(MotionEvent.ACTION_UP, down, pts.last()[0], pts.last()[1], pts.last()[2], MotionEvent.TOOL_TYPE_STYLUS))
            val dry = stable("26-$brush-committed", box)
            var inked = 0; var differ = 0
            for (y in box.top until box.bottom) for (x in box.left until box.right) {
                if (hypot(x - pts.last()[0], y - pts.last()[1]) < 50 || x > pts.last()[0] - 150) continue // pen tip
                val a = wet.getPixel(x, y); val b = dry.getPixel(x, y)
                if (Color.red(a) < 235 || Color.red(b) < 235) inked++
                if (abs(Color.red(a) - Color.red(b)) > 48) differ++
            }
            val frac = differ.toDouble() / maxOf(1, inked)
            // 2) pressure shows: light and firm straight strokes
            fun line(y: Float, p: Float) = (0 until 70).map { i -> floatArrayOf(r.left + r.width() * (0.38f + 0.24f * i / 69f), y, p) }
            val yl = r.top + r.height() * 0.55f; val yh = r.top + r.height() * 0.7f
            gesture(line(yl, 0.12f)); gesture(line(yh, 0.95f))
            SystemClock.sleep(600)
            val shot2 = shot("27-$brush-pressure")
            fun darkness(y: Float): Double {
                var sum = 0.0; var n = 0
                for (yy in (y - 30).toInt() until (y + 30).toInt()) for (x in (r.left + r.width() * 0.42f).toInt() until (r.left + r.width() * 0.58f).toInt()) {
                    val v = Color.red(shot2.getPixel(x, yy)); if (v < 250) { sum += 255 - v; n++ }
                }
                return if (n == 0) 0.0 else sum / n
            }
            val light = darkness(yl); val firm = darkness(yh)
            log("grain $brush: wet-vs-dry differ ${"%.2f".format(frac * 100)}% of $inked inked px; mean darkness light=${"%.0f".format(light)} firm=${"%.0f".format(firm)}")
            assertTrue("$brush: stroke changed when lifting the pen (${frac * 100}% of pixels)", frac < 0.03)
            assertTrue("$brush: pressure doesn't show (light $light vs firm $firm)", firm > light * 1.35 && light > 5)
            s.close()
        }
    }

    /** Fit width locks horizontal dragging; vertical drags accelerate when fast and keep gliding after a flick. */
    @Test fun fitWidthLocksHorizontalAndFastSwipesGoFurther() {
        val sc = Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL)
        repeat(7) { Pages.addPage(sc, PageOrientation.PORTRAIT, PageDirection.VERTICAL) }
        val s = launch(note(sc.toJson(), "navigation"))
        fun call(name: String) = s.onActivity { EditorActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(it) }
        // read the view matrix directly: onActivity waits for the main thread to go idle, which only happens once a glide ends
        var act: EditorActivity? = null; s.onActivity { act = it }
        val viewMatrix = field<android.graphics.Matrix>(act!!, "view")
        fun m(): FloatArray { val v = FloatArray(9); viewMatrix.getValues(v); return v }
        val r = surfaceRect(s)
        fun drag(dx: Float, dy: Float, steps: Int, stepMs: Long) =
            gesture((0..steps).map { i -> floatArrayOf(r.exactCenterX() - dx / 2 + dx * i / steps, r.exactCenterY() - dy / 2 + dy * i / steps, 0.5f) }, tool = MotionEvent.TOOL_TYPE_FINGER, stepMs = stepMs)

        call("fitWidth"); SystemClock.sleep(700)
        val a = m()
        drag(300f, -200f, 60, 14) // slow diagonal drag
        SystemClock.sleep(500)
        val b = m()
        val slowDy = b[Matrix.MTRANS_Y] - a[Matrix.MTRANS_Y]
        log("navigation: fit width, slow diagonal drag (300, -200): moved x=${b[Matrix.MTRANS_X] - a[Matrix.MTRANS_X]} y=$slowDy")
        assertEquals("no horizontal movement under fit width", a[Matrix.MTRANS_X], b[Matrix.MTRANS_X], 1f)
        assertEquals("scale unchanged", a[Matrix.MSCALE_X], b[Matrix.MSCALE_X], 0.001f)
        assertTrue("slow drags stay close to 1:1 ($slowDy)", slowDy < -170f && slowDy > -280f)

        val c = m()
        drag(0f, -600f, 6, 3) // fast flick up
        val atLift = m()
        SystemClock.sleep(1200)
        val d = m()
        val dragged = atLift[Matrix.MTRANS_Y] - c[Matrix.MTRANS_Y]
        val glided = d[Matrix.MTRANS_Y] - atLift[Matrix.MTRANS_Y]
        val rv = act!!.lastReleaseVelocity
        log("navigation: fast flick of 600px moved ${-dragged} px while dragging, then glided ${-glided} px (release velocity $rv px/s, density ${ctx.resources.displayMetrics.density})")
        assertTrue("fast drags go further than the finger (${-dragged})", -dragged > 600 * 1.3f)
        assertTrue("keeps gliding after a fast flick (${-glided})", -glided > 150f)
        assertEquals("still no horizontal movement", a[Matrix.MTRANS_X], d[Matrix.MTRANS_X], 1f)

        // gliding stops at the last page instead of running off (and doesn't add pages)
        repeat(6) { drag(0f, -500f, 8, 4); SystemClock.sleep(150) }
        SystemClock.sleep(2500)
        var pages = 0; s.onActivity { pages = Pages.pages(field<ExcalidrawScene>(it, "scene")).size }
        assertEquals("no page added by flicking", 8, pages)

        // Fit page releases the lock
        call("fitPage"); SystemClock.sleep(700)
        val e = m()
        drag(250f, 0f, 40, 14)
        SystemClock.sleep(400)
        val f2 = m()
        assertTrue("horizontal dragging works again after Fit page", kotlin.math.abs(f2[Matrix.MTRANS_X] - e[Matrix.MTRANS_X]) > 150f)
        shot("28-navigation")
        s.close()
    }

    /** Overview screenshots of the toolbar and its submenus. */
    @Test fun toolbarAndSubmenus() {
        val s = launch(note(Pages.newPagedScene(PageOrientation.LANDSCAPE, PageDirection.HORIZONTAL).toJson(), "toolbar"))
        val r = surfaceRect(s)
        gesture(wave(r, r.exactCenterY()))
        shot("08-toolbar")
        device.findObject(By.desc("Color")).click(); device.wait(Until.hasObject(By.text("Palette")), 3000); shot("09-color-popup"); device.pressBack()
        device.findObject(By.desc("Thickness")).click(); SystemClock.sleep(500); shot("10-thickness-popup"); device.pressBack()
        device.findObject(By.desc("Add page")).click(); SystemClock.sleep(800)
        assertEquals(2, Pages.pages(scene(s)).size)
        shot("11-second-page")
        device.findObject(By.desc("Pen")).click(); SystemClock.sleep(500); shot("14-pen-types"); device.pressBack()
        device.findObject(By.desc("Fullscreen")).click(); SystemClock.sleep(800); shot("15-fullscreen")
        device.findObject(By.desc("Exit fullscreen")).click(); SystemClock.sleep(500)
        s.close()
    }
}
