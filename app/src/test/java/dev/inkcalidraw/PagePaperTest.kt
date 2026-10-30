package dev.inkcalidraw

import org.junit.Assert.assertEquals
import org.junit.Test

class PagePaperTest {
    private fun kind(s: ExcalidrawScene, n: Int) = s.elements.first { it.id == Pages.pages(s)[n].id }.backgroundKind

    @Test fun newPagesKeepThePreviousPattern() {
        val s = Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL)
        Pages.setPaper(s, Pages.pages(s)[0].id, "lined")
        Pages.addPage(s, PageOrientation.PORTRAIT, PageDirection.VERTICAL)
        assertEquals("added page copies lined", "lined", kind(s, 1))
        Pages.setPaper(s, Pages.pages(s)[1].id, "grid")
        Pages.insertPage(s, 2, PageDirection.VERTICAL) // between page 1 (lined) and 2 (grid): follows the page before
        assertEquals("inserted page copies the page before it", "lined", kind(s, 1))
        Pages.addPage(s, PageOrientation.PORTRAIT, PageDirection.VERTICAL, paper = "dotted")
        assertEquals("explicit paper wins", "dotted", kind(s, 3))
    }

    @Test fun afterAPdfPageNewPagesAreBlank() {
        val s = ExcalidrawScene.empty()
        Pages.addPage(s, PageOrientation.PORTRAIT, PageDirection.VERTICAL, pdfFile = "a.pdf", pdfPage = 0)
        Pages.addPage(s, PageOrientation.PORTRAIT, PageDirection.VERTICAL)
        assertEquals("blank", kind(s, 1))
    }
}
