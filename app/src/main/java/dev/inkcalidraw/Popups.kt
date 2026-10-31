package dev.inkcalidraw

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/** Excalidraw's colour palette (open-color shades 0, 2, 4, 6, 8 per hue family). */
object Palette {
    val quick = listOf("#1e1e1e", "#e03131", "#2f9e44", "#1971c2", "#f08c00", "#ffffff")
    val families: List<List<String>> = listOf(
        listOf("#f8f9fa", "#e9ecef", "#ced4da", "#868e96", "#343a40"),
        listOf("#f8f1ee", "#eaddd7", "#d2bab0", "#a18072", "#846358"),
        listOf("#fff5f5", "#ffc9c9", "#ff8787", "#fa5252", "#e03131"),
        listOf("#fff0f6", "#fcc2d7", "#f783ac", "#e64980", "#c2255c"),
        listOf("#f8f0fc", "#eebefa", "#da77f2", "#be4bdb", "#9c36b5"),
        listOf("#f3f0ff", "#d0bfff", "#9775fa", "#7950f2", "#6741d9"),
        listOf("#e7f5ff", "#a5d8ff", "#4dabf7", "#228be6", "#1971c2"),
        listOf("#e3fafc", "#99e9f2", "#3bc9db", "#15aabf", "#0c8599"),
        listOf("#e6fcf5", "#96f2d7", "#38d9a9", "#12b886", "#099268"),
        listOf("#ebfbee", "#b2f2bb", "#69db7c", "#40c057", "#2f9e44"),
        listOf("#fff9db", "#ffec99", "#ffd43b", "#fab005", "#f08c00"),
        listOf("#fff4e6", "#ffd8a8", "#ffa94d", "#fd7e14", "#e8590c"),
    )
}

/** Recently used colours (app-wide, newest first). */
class ColorHistory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("colors", 0)
    fun list(): List<String> = prefs.getString("recent", null)?.split(',')?.filter { it.startsWith("#") } ?: listOf("#1e1e1e", "#e03131", "#1971c2")
    /** Remembers a colour. Colours already in the history keep their place, so the quick colours don't jump around. */
    fun use(c: String) {
        val cur = list()
        if (cur.any { it.equals(c, ignoreCase = true) }) return
        val l = (listOf(c.lowercase()) + cur).take(16)
        prefs.edit().putString("recent", l.joinToString(",")).apply()
    }
}

private fun swatch(ctx: Context, ui: Ui, color: String, sizeDp: Int, selected: Boolean, onClick: () -> Unit) = View(ctx).apply {
    val c = SceneRenderer.parseColor(color, Color.BLACK)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(ui.sceneColor(c))
        setStroke(ui.dpi(if (selected) 3 else 1), if (selected) ui.accent else ui.divider)
    }
    layoutParams = ViewGroup.MarginLayoutParams(ui.dpi(sizeDp), ui.dpi(sizeDp)).apply { setMargins(ui.dpi(3), ui.dpi(3), ui.dpi(3), ui.dpi(3)) }
    contentDescription = color
    setOnClickListener { onClick() }
}

private fun popupShell(ctx: Context, ui: Ui, title: String): Pair<PopupWindow, LinearLayout> {
    val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dpi(16), ui.dpi(12), ui.dpi(16), ui.dpi(14))
        background = ui.rounded(ui.surface, 14, ui.divider)
        elevation = ui.dp(8)
        addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(ui.text); setPadding(0, 0, 0, ui.dpi(8)) })
    }
    val scroll = ScrollView(ctx).apply { addView(col) }
    val pw = PopupWindow(scroll, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); elevation = ui.dp(8); isOutsideTouchable = true
    }
    return pw to col
}

private fun sectionLabel(ctx: Context, ui: Ui, s: String) = TextView(ctx).apply {
    text = s; textSize = 12f; setTextColor(ui.subtext); setPadding(0, ui.dpi(10), 0, ui.dpi(4))
}

/** Colour submenu: Excalidraw palette, HSV picker with hex entry, and history. */
fun showColorPopup(anchor: View, ui: Ui, current: String, history: List<String>, onPick: (String) -> Unit) {
    val ctx = anchor.context
    val (pw, col) = popupShell(ctx, ui, "Color")
    fun pick(c: String) { onPick(c); pw.dismiss() }

    col.addView(LinearLayout(ctx).apply { Palette.quick.forEach { addView(swatch(ctx, ui, it, 34, it.equals(current, true)) { pick(it) }) } })

    col.addView(sectionLabel(ctx, ui, "Palette"))
    col.addView(GridLayout(ctx).apply {
        columnCount = Palette.families.size
        for (shade in 0 until 5) for (fam in Palette.families) {
            val c = fam[shade]
            addView(swatch(ctx, ui, c, 24, c.equals(current, true)) { pick(c) })
        }
    })

    if (history.isNotEmpty()) {
        col.addView(sectionLabel(ctx, ui, "Recent"))
        col.addView(LinearLayout(ctx).apply { history.take(12).forEach { addView(swatch(ctx, ui, it, 28, it.equals(current, true)) { pick(it) }) } })
    }

    col.addView(sectionLabel(ctx, ui, "Custom"))
    val picker = ColorPickerView(ctx, ui, current)
    col.addView(picker, LinearLayout.LayoutParams(ui.dpi(300), ui.dpi(210)))
    val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dpi(8), 0, 0) }
    val preview = View(ctx)
    val hex = EditText(ctx).apply {
        setText(current); textSize = 15f; setTextColor(ui.text); isSingleLine = true
        setEms(6); isFocusableInTouchMode = true
    }
    val use = TextView(ctx).apply {
        text = "Use"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = ui.rounded(ui.accent, 16); setPadding(ui.dpi(16), ui.dpi(7), ui.dpi(16), ui.dpi(7))
        setOnClickListener { pick(picker.hex()) }
    }
    fun refreshPreview() { preview.background = ui.rounded(SceneRenderer.parseColor(picker.hex(), Color.BLACK), 6, ui.divider) }
    var syncing = false
    picker.onChange = { if (!syncing) { syncing = true; hex.setText(picker.hex()); syncing = false }; refreshPreview() }
    hex.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val t = s.toString().trim()
            if (!syncing && Regex("^#?[0-9a-fA-F]{6}$").matches(t)) { syncing = true; picker.set(if (t.startsWith("#")) t else "#$t"); syncing = false; refreshPreview() }
        }
    })
    refreshPreview()
    row.addView(preview, LinearLayout.LayoutParams(ui.dpi(32), ui.dpi(32)))
    row.addView(hex, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dpi(8) })
    row.addView(use)
    col.addView(row)

    pw.showAsDropDown(anchor, 0, ui.dpi(4))
}

/** Thickness submenu with real perfect-freehand previews of each width. */
fun showThicknessPopup(anchor: View, ui: Ui, widths: List<Double>, current: Double, color: String, opacity: Int, grain: String?, onPick: (Double) -> Unit) {
    val ctx = anchor.context
    val (pw, col) = popupShell(ctx, ui, "Thickness")
    for (w in widths) {
        col.addView(StrokePreview(ctx, ui, w, color, opacity, w == current, grain, null).apply {
            setOnClickListener { onPick(w); pw.dismiss() }
        }, LinearLayout.LayoutParams(ui.dpi(240), ui.dpi(40)))
    }
    pw.showAsDropDown(anchor, 0, ui.dpi(4))
}

/** Pen type submenu: one live preview per profile in its own colour and width. */
fun showProfilePopup(anchor: View, ui: Ui, profiles: List<PenProfile>, current: String, cfg: (String) -> PenConfig, onPick: (String) -> Unit) {
    val ctx = anchor.context
    val (pw, col) = popupShell(ctx, ui, "Pen type")
    for (p in profiles) {
        val c = cfg(p.id)
        col.addView(StrokePreview(ctx, ui, maxOf(c.strokeWidth, 1.0), c.color, c.opacity, p.id == current, p.grain, p.label, constant = !p.pressure).apply {
            setOnClickListener { onPick(p.id); pw.dismiss() }
        }, LinearLayout.LayoutParams(ui.dpi(280), ui.dpi(48)))
    }
    pw.showAsDropDown(anchor, 0, ui.dpi(4))
}

private class StrokePreview(
    ctx: Context, private val ui: Ui, private val w: Double, color: String, opacity: Int, private val selected: Boolean,
    private val grain: String?, private val title: String?, private val constant: Boolean = false,
) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = ui.sceneColor(SceneRenderer.parseColor(color, Color.BLACK)); alpha = opacity * 255 / 100
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ui.subtext; textSize = ui.dp(12) }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ui.accentSoft }

    override fun onDraw(c: Canvas) {
        if (selected) c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), ui.dp(8), ui.dp(8), sel)
        val left = if (title != null) ui.dp(100) else ui.dp(12)
        val pts = (0..40).map { i ->
            val t = i / 40.0
            doubleArrayOf(left + t * (width - left - ui.dp(58)), height / 2.0 + sin(t * 2 * PI) * height * 0.18, if (constant) 0.5 else 0.3 + 0.5 * sin(t * PI))
        }
        // preview at 100% zoom, but never taller than the row
        val shown = minOf(w * ui.dp(1), height * 0.42 / 4.25 / 1.35)
        val o = PerfectFreehand.excalidrawOptions(shown, false, false)
        val sp = PerfectFreehand.getStrokePoints(pts, o)
        val path = SceneRenderer.outlinePath(PerfectFreehand.getStrokeOutlinePoints(sp, o))
        if (GrainStroke.isGrainBrush(grain)) GrainStroke.draw(c, GrainStroke.grains(sp, o, grain!!, shown, 0.0, 0.0), fill) // same as on the page
        else c.drawPath(path, fill)
        if (title != null) { label.color = ui.text; label.textSize = ui.dp(14); c.drawText(title, ui.dp(12), height / 2f + ui.dp(5), label) }
        else c.drawText(if (w % 1.0 == 0.0) w.toInt().toString() else w.toString(), width - ui.dp(46), height / 2f + ui.dp(4), label)
    }
}

/** Saturation/value square + hue bar. */
@SuppressLint("ViewConstructor")
class ColorPickerView(ctx: Context, private val ui: Ui, initial: String) : View(ctx) {
    private val hsv = FloatArray(3)
    var onChange: (() -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(2.5f); color = Color.WHITE }
    private val ringOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(1); color = Color.argb(120, 0, 0, 0) }
    private var dragging = 0

    init { set(initial) }

    fun set(hex: String) { Color.colorToHSV(SceneRenderer.parseColor(hex, Color.BLACK), hsv); invalidate() }
    fun hex(): String = String.format("#%06x", Color.HSVToColor(hsv) and 0xFFFFFF)

    private fun sv() = RectF(0f, 0f, width.toFloat(), height - ui.dp(34))
    private fun hue() = RectF(0f, height - ui.dp(22), width.toFloat(), height.toFloat())

    override fun onDraw(c: Canvas) {
        val r = sv()
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        p.shader = ComposeShader(
            LinearGradient(r.left, 0f, r.right, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP),
            LinearGradient(0f, r.top, 0f, r.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP),
            PorterDuff.Mode.SRC_OVER,
        )
        c.drawRoundRect(r, ui.dp(6), ui.dp(6), p)
        val h = hue()
        p.shader = LinearGradient(h.left, 0f, h.right, 0f, IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(h, ui.dp(11), ui.dp(11), p)
        p.shader = null
        val x = r.left + hsv[1] * r.width(); val y = r.top + (1 - hsv[2]) * r.height()
        c.drawCircle(x, y, ui.dp(8), ringOuter); c.drawCircle(x, y, ui.dp(7), ring)
        val hx = h.left + hsv[0] / 360f * h.width()
        c.drawCircle(hx, h.centerY(), ui.dp(9), ringOuter); c.drawCircle(hx, h.centerY(), ui.dp(8), ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            dragging = if (e.y >= hue().top - ui.dp(6)) 2 else 1
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val r = sv(); val h = hue()
        if (dragging == 1) {
            hsv[1] = ((e.x - r.left) / r.width()).coerceIn(0f, 1f)
            hsv[2] = 1 - ((e.y - r.top) / r.height()).coerceIn(0f, 1f)
        } else if (dragging == 2) {
            hsv[0] = ((e.x - h.left) / h.width()).coerceIn(0f, 1f) * 359.9f
        }
        invalidate(); onChange?.invoke()
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) dragging = 0
        return true
    }
}
