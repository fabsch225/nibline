package dev.inkcalidraw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDiffTest {
    private fun stroke(i: Int, n: Int = 20) = ExcalidrawScene.newFreedraw(
        (0 until n).map { floatArrayOf(it * 3f, i * 10f, 0.5f) }, "#1e1e1e", 2.0, "a$i", 100, null, null)

    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun ids(m: JsonObject, k: String = "upsert") = m[k]!!.jsonArray.map { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: it.jsonPrimitive.content }

    @Test fun fullThenOnlyChanges() {
        val scene = ExcalidrawScene.empty()
        repeat(3) { scene.elements.add(stroke(it)) }
        val d = LiveDiff()
        val full = d.full("Lecture", scene).map(::parse)
        assertEquals("full", full[0]["t"]!!.jsonPrimitive.content)
        assertEquals("Lecture", full[0]["note"]!!.jsonPrimitive.content)
        assertEquals(3, full.drop(1).flatMap { ids(it) }.size)

        assertEquals(emptyList<String>(), d.changes(scene)) // nothing changed

        val moved = scene.elements[1].withChanges("x" to JsonPrimitive(50))
        scene.elements[1] = moved
        scene.elements.removeAt(0)
        val added = stroke(9); scene.elements.add(added)
        val ch = d.changes(scene).map(::parse)
        assertEquals(1, ch.size)
        assertEquals(listOf(moved.id, added.id), ids(ch[0]))
        assertEquals(1, ch[0]["remove"]!!.jsonArray.size)
        assertEquals(emptyList<String>(), d.changes(scene))

        // erasing marks isDeleted: viewers get a removal
        scene.elements[0] = scene.elements[0].withChanges("isDeleted" to JsonPrimitive(true))
        val er = d.changes(scene).map(::parse).single()
        assertEquals(emptyList<String>(), ids(er))
        assertEquals(listOf(moved.id), ids(er, "remove"))
    }

    @Test fun bigScenesAreChunked() {
        val scene = ExcalidrawScene.empty()
        repeat(2000) { scene.elements.add(stroke(it, 60)) }
        val msgs = LiveDiff().full("Big", scene, chunk = 200_000)
        assertTrue("several deltas: ${msgs.size}", msgs.size > 3)
        assertTrue(msgs.all { it.length < 260_000 })
        assertEquals(2000, msgs.drop(1).map(::parse).flatMap { ids(it) }.size)
    }

    @Test fun codesAndKeys() {
        val codes = (1..500).map { LiveDiff.newCode() }
        assertTrue(codes.all { Regex("^[A-HJ-NP-Z]{5}$").matches(it) })
        assertTrue(codes.toSet().size > 490)
        assertEquals(48, LiveDiff.newHostKey().length)
    }

    @Test fun pdfFileIdMatchesViewer() {
        // viewer: `pdf-${file.replace(/[^A-Za-z0-9]/g, "").slice(0, 40)}-${page}`
        assertEquals("pdf-Lecture3Folienpdf-4", LiveDiff.pdfFileId("Lecture 3 (Folien).pdf", 4))
        assertEquals("pdf-bungsblatt2pdf-0", LiveDiff.pdfFileId("Übungsblatt-2.pdf", 0))
    }

    @Test fun smallMessages() {
        assertEquals("""{"t":"wet","el":null}""", LiveDiff.wet(null))
        val v = parse(LiveDiff.view(1f, 2f, 300f, 400f))
        assertEquals(300.0, v["w"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertTrue(LiveDiff.deltas(emptyList(), listOf("x")).single().contains("\"remove\":[\"x\"]"))
        assertEquals(JsonArray(emptyList()), parse(LiveDiff.deltas(emptyList(), listOf("x")).single())["upsert"])
    }
}
