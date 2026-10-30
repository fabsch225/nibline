package dev.inkcalidraw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ShapesTest {
    private val rnd = Random(5)
    private fun wobble(v: Double, amp: Double) = v + (rnd.nextDouble() - 0.5) * 2 * amp

    /** Hand-drawn polygon: points along the edges with jitter, ending near the start. */
    private fun drawn(vararg corners: DoubleArray, amp: Double = 1.5): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val ring = corners.toList() + listOf(corners[0])
        for (i in 0 until ring.size - 1) for (k in 0 until 20) {
            val t = k / 20.0; val a = ring[i]; val b = ring[i + 1]
            out += doubleArrayOf(wobble(a[0] + (b[0] - a[0]) * t, amp), wobble(a[1] + (b[1] - a[1]) * t, amp))
        }
        out += doubleArrayOf(corners[0][0] + 3, corners[0][1] + 2)
        return out
    }

    private fun p(x: Double, y: Double) = doubleArrayOf(x, y)

    @Test fun straightStrokeBecomesALineSnappedToHorizontal() {
        val pts = (0..40).map { p(10.0 + it * 5, wobble(100.0 + it * 0.08, 1.0)) }
        val r = ShapeRecognizer.recognize(pts)
        assertTrue(r is ShapeRecognizer.Result.Line)
        r as ShapeRecognizer.Result.Line
        assertEquals("snapped flat", r.ay, r.by, 1e-6)
    }

    @Test fun recognisesRectangleTriangleDiamondEllipse() {
        assertEquals(ShapeKind.RECTANGLE, (ShapeRecognizer.recognize(drawn(p(0.0, 0.0), p(200.0, 0.0), p(200.0, 120.0), p(0.0, 120.0))) as ShapeRecognizer.Result.Box).kind)
        val tri = ShapeRecognizer.recognize(drawn(p(100.0, 0.0), p(200.0, 170.0), p(0.0, 170.0)))
        assertTrue("triangle: $tri", tri is ShapeRecognizer.Result.Polygon && tri.corners.size == 3)
        assertEquals(ShapeKind.DIAMOND, (ShapeRecognizer.recognize(drawn(p(100.0, 0.0), p(200.0, 80.0), p(100.0, 160.0), p(0.0, 80.0))) as ShapeRecognizer.Result.Box).kind)
        val circle = (0..80).map { val t = it / 80.0 * 2 * PI * 1.03; p(wobble(150 + 90 * cos(t), 2.0), wobble(150 + 60 * sin(t), 2.0)) }
        val e = ShapeRecognizer.recognize(circle)
        assertTrue("ellipse: $e", e is ShapeRecognizer.Result.Box && e.kind == ShapeKind.ELLIPSE)
    }

    @Test fun scribblesStayStrokes() {
        val zig = (0..60).map { p(it * 4.0, if (it % 2 == 0) 0.0 else 30.0) }
        assertNull(ShapeRecognizer.recognize(zig))
        val spiral = (0..120).map { val t = it / 20.0; p(100 + t * 12 * cos(t), 100 + t * 12 * sin(t)) }
        assertNull(ShapeRecognizer.recognize(spiral))
    }

    @Test fun shapesAreExcalidrawElements() {
        val r = Shapes.build(ShapeKind.RECTANGLE, 50.0, 80.0, 10.0, 20.0, "#1e1e1e", 2.0, 100, "a1", null)
        assertEquals("rectangle", r.type); assertEquals(10.0, r.x, 0.0); assertEquals(20.0, r.y, 0.0); assertEquals(40.0, r.width, 0.0)
        val a = Shapes.build(ShapeKind.ARROW, 0.0, 0.0, 100.0, 50.0, "#1e1e1e", 2.0, 100, "a2", null)
        assertEquals("arrow", a.type); assertEquals(2, a.points.size); assertEquals("\"arrow\"", a.json["endArrowhead"].toString())
        val t = Shapes.build(ShapeKind.TRIANGLE, 0.0, 0.0, 100.0, 80.0, "#1e1e1e", 2.0, 100, "a3", null)
        assertEquals("line", t.type); assertEquals(4, t.points.size)
    }

    @Test fun eraserHitsShapeOutlinesNotTheirInside() {
        val r = Shapes.build(ShapeKind.RECTANGLE, 0.0, 0.0, 200.0, 200.0, "#1e1e1e", 2.0, 100, "a1", null)
        assertTrue(Geometry.eraserHits(r, 0.0, 100.0, 0.0, 100.0, 5.0))
        assertFalse("middle of an empty rectangle", Geometry.eraserHits(r, 100.0, 100.0, 100.0, 100.0, 5.0))
        val e = Shapes.build(ShapeKind.ELLIPSE, 0.0, 0.0, 200.0, 100.0, "#1e1e1e", 2.0, 100, "a1", null)
        assertTrue(Geometry.eraserHits(e, 100.0, 0.0, 100.0, 0.0, 4.0))
        assertFalse(Geometry.eraserHits(e, 2.0, 2.0, 2.0, 2.0, 4.0))
    }

    @Test fun areaEraserCutsAStrokeInTwo() {
        val pts = (0..10).map { floatArrayOf(it * 20f, 0f, 0.3f + it * 0.05f) }
        val el = ExcalidrawScene.newFreedraw(pts, "#000000", 1.0, "a1")
        val pieces = AreaEraser.cut(el, 100.0, -20.0, 100.0, 20.0, 6.0)
        assertNotNull(pieces); pieces!!
        assertEquals(2, pieces.size)
        assertEquals("first piece keeps the id", el.id, pieces[0].id)
        assertTrue(pieces[1].id != el.id)
        val left = pieces[0]; val right = pieces[1]
        assertTrue(left.x + left.points.maxOf { it[0] } < 100 - 6)
        assertTrue(right.x > 100 + 6)
        assertEquals(left.points.size, left.pressures.size)
        assertTrue("pressure kept from the original", right.pressures.first() > 0.5)
        assertNull("eraser elsewhere", AreaEraser.cut(el, 100.0, 60.0, 100.0, 80.0, 6.0))
        assertEquals("erasing over all of it", 0, AreaEraser.cut(el, -10.0, 0.0, 210.0, 0.0, 6.0)!!.size)
    }

    @Test fun writingGuideFollowsTheBaseline() {
        // "letters": 10 small strokes on a line at y = 300 (bottoms), one descender
        val strokes = (0 until 10).map { i ->
            val bottom = 300.0 + (if (i == 4) 12.0 else 0.0) + i * 0.5
            WritingGuide.Stroke(100.0 + i * 25, bottom - 20, 100.0 + i * 25 + 18, bottom)
        }
        val g = WritingGuide.baseline(strokes, maxLetter = 80.0)
        assertNotNull(g); g!!
        assertEquals(302.0, g.yAt(200.0), 2.5)
        assertNull("too few strokes", WritingGuide.baseline(strokes.take(3), maxLetter = 80.0))
        val drawing = strokes + WritingGuide.Stroke(100.0, 100.0, 400.0, 260.0)
        assertNull("a big drawing isn't text", WritingGuide.baseline(drawing, maxLetter = 80.0))
        val newLine = strokes + (0 until 2).map { WritingGuide.Stroke(100.0 + it * 25, 340.0, 118.0 + it * 25, 360.0) }
        assertNull("a new line has just started", WritingGuide.baseline(newLine, maxLetter = 80.0))
    }
}
