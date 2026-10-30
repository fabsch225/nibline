package dev.inkcalidraw

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Line icons drawn in a 24×24 grid (stroke 1.8), so they scale crisply and follow the theme colour. */
enum class Icon { BACK, PEN, HIGHLIGHTER, ERASER, LASSO, IMAGE, PDF, UNDO, REDO, FIT, MORE, PLUS, PREV, NEXT, PASTE, PAGES, TRASH, COPY, CHECK, FOLDER, IMPORT, EXPORT, MOON, WIDTH, FULLSCREEN, FULLSCREEN_EXIT, FOLDER_PLUS, MOVE, LIVE, SHAPE, LASER, GUIDE }

class IconDrawable(private val icon: Icon, color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        val s = minOf(b.width(), b.height()) / 24f
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        c.scale(s, s)
        path.reset()
        val P = path
        when (icon) {
            Icon.BACK -> { P.moveTo(15f, 5f); P.lineTo(8f, 12f); P.lineTo(15f, 19f) }
            Icon.PREV -> { P.moveTo(14f, 6f); P.lineTo(8f, 12f); P.lineTo(14f, 18f) }
            Icon.NEXT -> { P.moveTo(10f, 6f); P.lineTo(16f, 12f); P.lineTo(10f, 18f) }
            Icon.PEN -> {
                P.moveTo(4f, 20f); P.lineTo(5f, 15.5f); P.lineTo(15.5f, 5f); P.lineTo(19f, 8.5f); P.lineTo(8.5f, 19f); P.close()
                P.moveTo(13.5f, 7f); P.lineTo(17f, 10.5f)
            }
            Icon.HIGHLIGHTER -> {
                P.moveTo(9f, 15f); P.lineTo(16.5f, 4.5f); P.lineTo(20f, 7.5f); P.lineTo(12f, 17.5f); P.close()
                P.moveTo(9f, 15f); P.lineTo(7f, 19f); P.lineTo(10.5f, 19.5f); P.lineTo(12f, 17.5f)
                P.moveTo(4f, 21f); P.lineTo(20f, 21f)
            }
            Icon.ERASER -> {
                P.moveTo(8f, 20f); P.lineTo(3.8f, 15.8f); P.lineTo(13.5f, 6f); P.lineTo(19.5f, 12f); P.lineTo(11.5f, 20f); P.close()
                P.moveTo(8.5f, 11f); P.lineTo(14.5f, 17f); P.moveTo(12f, 20f); P.lineTo(20f, 20f)
            }
            Icon.SHAPE -> {
                c.drawRoundRect(RectF(3.5f, 9.5f, 13.5f, 19.5f), 1.5f, 1.5f, p)
                c.drawCircle(15.5f, 8.5f, 5f, p)
            }
            Icon.LASER -> {
                P.moveTo(3.5f, 20.5f); P.lineTo(11f, 13f)
                c.drawCircle(15f, 9f, 2.6f, p)
                P.moveTo(15f, 3f); P.lineTo(15f, 4.3f); P.moveTo(21f, 9f); P.lineTo(19.7f, 9f); P.moveTo(19.3f, 4.7f); P.lineTo(18.4f, 5.6f)
                P.moveTo(19.3f, 13.3f); P.lineTo(18.4f, 12.4f); P.moveTo(10.7f, 4.7f); P.lineTo(11.6f, 5.6f)
            }
            Icon.GUIDE -> {
                P.moveTo(4f, 13f); P.cubicTo(5.5f, 7f, 7.5f, 7f, 8f, 13f); P.cubicTo(9f, 9f, 10.5f, 9f, 11f, 13f)
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2.4f, 2f), 0f)
                c.drawLine(3f, 16.5f, 21f, 16.5f, p)
                p.pathEffect = null
            }
            Icon.LASSO -> {
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2.6f, 2.2f), 0f)
                c.drawOval(RectF(3.5f, 4f, 20.5f, 15.5f), p)
                p.pathEffect = null
                P.moveTo(8f, 14.5f); P.cubicTo(6f, 17f, 7.5f, 20.5f, 10.5f, 19.5f)
            }
            Icon.IMAGE -> {
                c.drawRoundRect(RectF(3.5f, 5f, 20.5f, 19f), 2f, 2f, p)
                P.moveTo(4f, 17f); P.lineTo(9f, 12f); P.lineTo(13f, 16f); P.lineTo(15.5f, 13.5f); P.lineTo(20f, 18f)
                c.drawCircle(15.5f, 9f, 1.4f, p)
            }
            Icon.PDF -> {
                P.moveTo(6f, 3.5f); P.lineTo(14f, 3.5f); P.lineTo(18.5f, 8f); P.lineTo(18.5f, 20.5f); P.lineTo(6f, 20.5f); P.close()
                P.moveTo(14f, 3.5f); P.lineTo(14f, 8f); P.lineTo(18.5f, 8f)
                P.moveTo(9f, 12.5f); P.lineTo(15.5f, 12.5f); P.moveTo(9f, 16f); P.lineTo(15.5f, 16f)
            }
            Icon.UNDO -> { P.moveTo(9f, 14f); P.lineTo(4.5f, 9.5f); P.lineTo(9f, 5f); P.moveTo(4.5f, 9.5f); P.lineTo(14f, 9.5f); P.cubicTo(17.5f, 9.5f, 20f, 12f, 20f, 15f); P.cubicTo(20f, 18f, 17.5f, 19.5f, 15f, 19.5f); P.lineTo(11f, 19.5f) }
            Icon.REDO -> { P.moveTo(15f, 14f); P.lineTo(19.5f, 9.5f); P.lineTo(15f, 5f); P.moveTo(19.5f, 9.5f); P.lineTo(10f, 9.5f); P.cubicTo(6.5f, 9.5f, 4f, 12f, 4f, 15f); P.cubicTo(4f, 18f, 6.5f, 19.5f, 9f, 19.5f); P.lineTo(13f, 19.5f) }
            Icon.FIT -> {
                P.moveTo(4f, 9f); P.lineTo(4f, 4f); P.lineTo(9f, 4f); P.moveTo(15f, 4f); P.lineTo(20f, 4f); P.lineTo(20f, 9f)
                P.moveTo(20f, 15f); P.lineTo(20f, 20f); P.lineTo(15f, 20f); P.moveTo(9f, 20f); P.lineTo(4f, 20f); P.lineTo(4f, 15f)
                c.drawRect(RectF(8.5f, 8.5f, 15.5f, 15.5f), p)
            }
            Icon.MORE -> { for (y in listOf(6f, 12f, 18f)) { p.style = Paint.Style.FILL; c.drawCircle(12f, y, 1.6f, p); p.style = Paint.Style.STROKE } }
            Icon.PLUS -> { P.moveTo(12f, 5f); P.lineTo(12f, 19f); P.moveTo(5f, 12f); P.lineTo(19f, 12f) }
            Icon.PASTE -> {
                c.drawRoundRect(RectF(5f, 5f, 19f, 21f), 2f, 2f, p)
                c.drawRoundRect(RectF(9f, 3f, 15f, 7f), 1.2f, 1.2f, p)
                P.moveTo(8.5f, 12f); P.lineTo(15.5f, 12f); P.moveTo(8.5f, 16f); P.lineTo(13.5f, 16f)
            }
            Icon.PAGES -> {
                c.drawRoundRect(RectF(7f, 3.5f, 19f, 17.5f), 1.5f, 1.5f, p)
                P.moveTo(7f, 6.5f); P.lineTo(5f, 6.5f); P.lineTo(5f, 20.5f); P.lineTo(16f, 20.5f); P.lineTo(16f, 17.5f)
            }
            Icon.TRASH -> {
                P.moveTo(4.5f, 7f); P.lineTo(19.5f, 7f); P.moveTo(9.5f, 7f); P.lineTo(10f, 4f); P.lineTo(14f, 4f); P.lineTo(14.5f, 7f)
                P.moveTo(6.5f, 7f); P.lineTo(7.5f, 20f); P.lineTo(16.5f, 20f); P.lineTo(17.5f, 7f)
                P.moveTo(10f, 11f); P.lineTo(10f, 16.5f); P.moveTo(14f, 11f); P.lineTo(14f, 16.5f)
            }
            Icon.COPY -> {
                c.drawRoundRect(RectF(8.5f, 8.5f, 20f, 20f), 2f, 2f, p)
                P.moveTo(15.5f, 8.5f); P.lineTo(15.5f, 5.5f); P.cubicTo(15.5f, 4.5f, 15f, 4f, 14f, 4f); P.lineTo(5.5f, 4f)
                P.cubicTo(4.5f, 4f, 4f, 4.5f, 4f, 5.5f); P.lineTo(4f, 14f); P.cubicTo(4f, 15f, 4.5f, 15.5f, 5.5f, 15.5f); P.lineTo(8.5f, 15.5f)
            }
            Icon.CHECK -> { P.moveTo(5f, 12.5f); P.lineTo(10f, 17.5f); P.lineTo(19f, 7f) }
            Icon.FOLDER -> { P.moveTo(3.5f, 18.5f); P.lineTo(3.5f, 5.5f); P.lineTo(9.5f, 5.5f); P.lineTo(11.5f, 8f); P.lineTo(20.5f, 8f); P.lineTo(20.5f, 18.5f); P.close() }
            Icon.IMPORT -> { P.moveTo(12f, 4f); P.lineTo(12f, 15f); P.moveTo(7.5f, 10.5f); P.lineTo(12f, 15f); P.lineTo(16.5f, 10.5f); P.moveTo(4.5f, 15f); P.lineTo(4.5f, 20f); P.lineTo(19.5f, 20f); P.lineTo(19.5f, 15f) }
            Icon.EXPORT -> { P.moveTo(12f, 15f); P.lineTo(12f, 4f); P.moveTo(7.5f, 8.5f); P.lineTo(12f, 4f); P.lineTo(16.5f, 8.5f); P.moveTo(4.5f, 15f); P.lineTo(4.5f, 20f); P.lineTo(19.5f, 20f); P.lineTo(19.5f, 15f) }
            Icon.MOON -> { P.moveTo(19.5f, 14.5f); P.cubicTo(15f, 17f, 8f, 14f, 9.5f, 4.5f); P.cubicTo(5.5f, 6f, 3.8f, 10.5f, 5.5f, 14.5f); P.cubicTo(7.5f, 19.5f, 15.5f, 21f, 19.5f, 14.5f); P.close() }
            Icon.FULLSCREEN -> {
                P.moveTo(4f, 9f); P.lineTo(4f, 4f); P.lineTo(9f, 4f); P.moveTo(15f, 4f); P.lineTo(20f, 4f); P.lineTo(20f, 9f)
                P.moveTo(20f, 15f); P.lineTo(20f, 20f); P.lineTo(15f, 20f); P.moveTo(9f, 20f); P.lineTo(4f, 20f); P.lineTo(4f, 15f)
            }
            Icon.FULLSCREEN_EXIT -> {
                P.moveTo(9f, 4f); P.lineTo(9f, 9f); P.lineTo(4f, 9f); P.moveTo(15f, 4f); P.lineTo(15f, 9f); P.lineTo(20f, 9f)
                P.moveTo(20f, 15f); P.lineTo(15f, 15f); P.lineTo(15f, 20f); P.moveTo(9f, 20f); P.lineTo(9f, 15f); P.lineTo(4f, 15f)
            }
            Icon.FOLDER_PLUS -> {
                P.moveTo(3.5f, 18.5f); P.lineTo(3.5f, 5.5f); P.lineTo(9.5f, 5.5f); P.lineTo(11.5f, 8f); P.lineTo(20.5f, 8f); P.lineTo(20.5f, 18.5f); P.close()
                P.moveTo(12f, 10.5f); P.lineTo(12f, 16f); P.moveTo(9.25f, 13.25f); P.lineTo(14.75f, 13.25f)
            }
            Icon.LIVE -> {
                // a dot with broadcast arcs on both sides
                c.drawCircle(12f, 12f, 2f, p)
                P.moveTo(8.5f, 8.5f); P.cubicTo(6.5f, 10.5f, 6.5f, 13.5f, 8.5f, 15.5f)
                P.moveTo(15.5f, 8.5f); P.cubicTo(17.5f, 10.5f, 17.5f, 13.5f, 15.5f, 15.5f)
                P.moveTo(5.5f, 5.5f); P.cubicTo(1.8f, 9.2f, 1.8f, 14.8f, 5.5f, 18.5f)
                P.moveTo(18.5f, 5.5f); P.cubicTo(22.2f, 9.2f, 22.2f, 14.8f, 18.5f, 18.5f)
            }
            Icon.MOVE -> { P.moveTo(4f, 12f); P.lineTo(18f, 12f); P.moveTo(13.5f, 7.5f); P.lineTo(18f, 12f); P.lineTo(13.5f, 16.5f) }
            Icon.WIDTH -> {
                p.strokeWidth = 1f; c.drawLine(4f, 6f, 20f, 6f, p)
                p.strokeWidth = 2.2f; c.drawLine(4f, 12f, 20f, 12f, p)
                p.strokeWidth = 3.8f; c.drawLine(4f, 18.5f, 20f, 18.5f, p)
                p.strokeWidth = 1.8f
            }
        }
        c.drawPath(path, p)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 72
    override fun getIntrinsicHeight() = 72
}
