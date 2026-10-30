package dev.inkcalidraw

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * Page model for A4/PDF notes. A page is an Excalidraw frame element; whatever is drawn on it gets
 * frameId = that frame, so Excalidraw clips and moves it with the page.
 *
 * Two independent pointers per page (both in the frame's customData):
 *  - inkcalidrawPage: its position in the note (1..n, contiguous), which drives layout and numbering;
 *  - background: what's underneath (a page of a PDF, or blank/lined/grid/dotted paper).
 * Inserting, deleting or reordering pages only renumbers and re-flows; it never re-points backgrounds.
 *
 * Pure Kotlin so the layout logic is unit-testable.
 */
object Pages {
    /** A4 in CSS pixels (96 dpi), which is Excalidraw's scene unit. */
    const val A4_SHORT = 794.0
    const val A4_LONG = 1123.0
    const val GAP = 48.0
    val PAPERS = listOf("blank", "lined", "grid", "dotted")

    data class Page(val id: String, val number: Int, val x: Double, val y: Double, val w: Double, val h: Double) {
        val box get() = Geometry.Box(x, y, x + w, y + h)
        fun contains(px: Double, py: Double) = px >= x && px <= x + w && py >= y && py <= y + h
    }

    fun size(o: PageOrientation) = if (o == PageOrientation.PORTRAIT) A4_SHORT to A4_LONG else A4_LONG to A4_SHORT

    fun pages(scene: ExcalidrawScene): List<Page> = pages(scene.elements)

    fun pages(elements: List<Element>): List<Page> = elements
        .filter { it.type == "frame" && !it.isDeleted && it.pageNumber != null }
        .map { Page(it.id, it.pageNumber!!, it.x, it.y, it.width, it.height) }
        .sortedBy { it.number }

    fun pageAt(pages: List<Page>, x: Double, y: Double): Page? = pages.firstOrNull { it.contains(x, y) }

    /** Page whose centre is closest to (x, y): "the current page" for a viewport centred there. */
    fun nearest(pages: List<Page>, x: Double, y: Double): Int =
        pages.indices.minByOrNull { val p = pages[it]; abs(p.x + p.w / 2 - x) + abs(p.y + p.h / 2 - y) } ?: -1

    /** Where the next page goes, keeping pages centred on a common axis like Samsung Notes. */
    fun nextSlot(pages: List<Page>, w: Double, h: Double, dir: PageDirection, origin: Pair<Double, Double> = 0.0 to 0.0): Pair<Double, Double> {
        val last = pages.lastOrNull() ?: return origin
        val first = pages.first()
        return if (dir == PageDirection.VERTICAL) {
            val cx = first.x + first.w / 2
            (cx - w / 2) to (last.y + last.h + GAP)
        } else {
            val cy = first.y + first.h / 2
            (last.x + last.w + GAP) to (cy - h / 2)
        }
    }

    /** Paper for a new page next to [p]: its pattern (blank/lined/grid/dotted); PDF pages give a blank page. */
    fun paperLike(scene: ExcalidrawScene, p: Page?): String {
        val kind = p?.let { pg -> scene.elements.firstOrNull { it.id == pg.id }?.backgroundKind } ?: return "blank"
        return if (kind == "pdf") "blank" else kind
    }

    /** Appends a page; returns the new frame element. */
    fun addPage(
        scene: ExcalidrawScene, orientation: PageOrientation, dir: PageDirection, w: Double? = null, h: Double? = null,
        pdfFile: String? = null, pdfPage: Int? = null, paper: String? = null,
    ): Element {
        val existing = pages(scene)
        val paper = paper ?: paperLike(scene, existing.lastOrNull()) // a new page keeps the previous page's pattern
        val (dw, dh) = size(orientation)
        val pw = w ?: dw; val ph = h ?: dh
        // First page of a note that already has whiteboard content: put the pages next to it, not on top.
        val origin = if (existing.isEmpty()) Geometry.contentBounds(scene.elements)?.let { (it.maxX + 200) to it.minY } ?: (0.0 to 0.0) else 0.0 to 0.0
        val (x, y) = nextSlot(existing, pw, ph, dir, origin)
        val n = (existing.maxOfOrNull { it.number } ?: 0) + 1
        val frame = ExcalidrawScene.newFrame("Page $n", x, y, pw, ph, FractionalIndex.after(scene.lastIndex()), n, pdfFile, pdfPage, paper)
        scene.elements.add(frame)
        return frame
    }

    /**
     * Inserts a blank page so that it becomes page [position] (1-based; size+1 = at the end).
     * Size/orientation default to the neighbouring page, so inserting into a PDF matches its pages.
     */
    fun insertPage(scene: ExcalidrawScene, position: Int, dir: PageDirection, orientation: PageOrientation? = null, paper: String? = null): Element {
        val ps = pages(scene)
        val pos = position.coerceIn(1, ps.size + 1)
        val neighbour = ps.getOrNull(pos - 1) ?: ps.getOrNull(pos - 2)
        val paper = paper ?: paperLike(scene, ps.getOrNull(pos - 2) ?: neighbour)
        val (w, h) = when {
            orientation != null -> size(orientation)
            neighbour != null -> neighbour.w to neighbour.h
            else -> size(PageOrientation.PORTRAIT)
        }
        renumber(scene, ps.drop(pos - 1).associate { it.id to it.number + 1 })
        val frame = ExcalidrawScene.newFrame("Page $pos", neighbour?.x ?: 0.0, neighbour?.y ?: 0.0, w, h, FractionalIndex.after(scene.lastIndex()), pos, kind = paper)
        scene.elements.add(frame)
        relayout(scene, dir)
        return scene.elements.first { it.id == frame.id }
    }

    /** Deletes a page and everything on it, then closes the gap. */
    fun deletePage(scene: ExcalidrawScene, pageId: String, dir: PageDirection) {
        val ps = pages(scene)
        val victim = ps.firstOrNull { it.id == pageId } ?: return
        for (i in scene.elements.indices) {
            val el = scene.elements[i]
            if (el.id == pageId || el.frameId == pageId) scene.elements[i] = el.withChanges("isDeleted" to JsonPrimitive(true))
        }
        renumber(scene, ps.filter { it.number > victim.number }.associate { it.id to it.number - 1 })
        relayout(scene, dir)
    }

    /** Copies a page (background pointer and ink) right after itself. */
    fun duplicatePage(scene: ExcalidrawScene, pageId: String, dir: PageDirection): Element? {
        val ps = pages(scene)
        val src = ps.firstOrNull { it.id == pageId } ?: return null
        val srcFrame = scene.elements.first { it.id == pageId }
        renumber(scene, ps.filter { it.number > src.number }.associate { it.id to it.number + 1 })
        val n = src.number + 1
        val copy = srcFrame.copyAsNew(FractionalIndex.after(scene.lastIndex()))
            .withChanges("name" to JsonPrimitive("Page $n"), "customData" to customWithNumber(srcFrame, n))
        scene.elements.add(copy)
        val children = scene.elements.filter { it.frameId == pageId && !it.isDeleted }
        for (c in children) scene.elements.add(c.copyAsNew(FractionalIndex.after(scene.lastIndex())).withChanges("frameId" to JsonPrimitive(copy.id)))
        relayout(scene, dir)
        return scene.elements.first { it.id == copy.id }
    }

    /** Moves a page one step earlier (-1) or later (+1). */
    fun movePage(scene: ExcalidrawScene, pageId: String, step: Int, dir: PageDirection) {
        val ps = pages(scene)
        val i = ps.indexOfFirst { it.id == pageId }
        val j = i + step
        if (i < 0 || j !in ps.indices) return
        renumber(scene, mapOf(ps[i].id to ps[j].number, ps[j].id to ps[i].number))
        relayout(scene, dir)
    }

    /** Changes a page's paper (no effect on PDF pages' pointer unless [paper] replaces it). */
    fun setPaper(scene: ExcalidrawScene, pageId: String, paper: String) {
        val i = scene.elements.indexOfFirst { it.id == pageId }
        if (i < 0) return
        val el = scene.elements[i]
        val cd = (el.customData ?: JsonObject(emptyMap())).toMutableMap()
        cd["background"] = JsonObject(mapOf("kind" to JsonPrimitive(paper)))
        cd.remove("pdfFile"); cd.remove("pdfPage")
        scene.elements[i] = el.withChanges("customData" to JsonObject(cd))
    }

    private fun customWithNumber(el: Element, n: Int): JsonObject {
        val cd = (el.customData ?: JsonObject(emptyMap())).toMutableMap()
        cd["inkcalidrawPage"] = JsonPrimitive(n)
        return JsonObject(cd)
    }

    private fun renumber(scene: ExcalidrawScene, newNumbers: Map<String, Int>) {
        if (newNumbers.isEmpty()) return
        for (i in scene.elements.indices) {
            val el = scene.elements[i]
            val n = newNumbers[el.id] ?: continue
            scene.elements[i] = el.withChanges("customData" to customWithNumber(el, n), "name" to JsonPrimitive("Page $n"))
        }
    }

    /** Re-flows all pages along [dir], moving each page's content with it. */
    fun relayout(scene: ExcalidrawScene, dir: PageDirection) {
        val ps = pages(scene)
        if (ps.isEmpty()) return
        // Anchor on the page that is first in order *and* keep the axis of the existing layout.
        val anchor = ps.minByOrNull { if (dir == PageDirection.VERTICAL) it.y else it.x }!!
        val axis = if (dir == PageDirection.VERTICAL) ps.first().x + ps.first().w / 2 else ps.first().y + ps.first().h / 2
        var cursor = if (dir == PageDirection.VERTICAL) anchor.y else anchor.x
        val delta = HashMap<String, Pair<Double, Double>>()
        for (p in ps) {
            val nx: Double; val ny: Double
            if (dir == PageDirection.VERTICAL) { nx = axis - p.w / 2; ny = cursor; cursor += p.h + GAP }
            else { nx = cursor; ny = axis - p.h / 2; cursor += p.w + GAP }
            delta[p.id] = (nx - p.x) to (ny - p.y)
        }
        for (i in scene.elements.indices) {
            val el = scene.elements[i]
            if (el.isDeleted) continue
            val d = delta[el.id] ?: el.frameId?.let { delta[it] } ?: continue
            if (d.first == 0.0 && d.second == 0.0) continue
            scene.elements[i] = el.withChanges("x" to JsonPrimitive(el.x + d.first), "y" to JsonPrimitive(el.y + d.second))
        }
    }

    /** Assigns frameId by the page that contains the element's centre (or clears it). */
    fun frameFor(pages: List<Page>, el: Element): String? {
        val b = Geometry.bounds(el)
        return pageAt(pages, b.centerX, b.centerY)?.id
    }

    fun newPagedScene(orientation: PageOrientation, dir: PageDirection, paper: String = "blank"): ExcalidrawScene {
        val s = ExcalidrawScene.empty()
        addPage(s, orientation, dir, paper = paper)
        NoteMeta(paged = true, orientation = orientation, direction = dir).writeTo(s)
        return s
    }

    @Suppress("unused") private val keepImport = JsonNull
}
