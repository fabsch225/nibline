package dev.inkcalidraw

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * The tablet side of the live protocol (see live/relay/src/index.ts), free of Android/network code so it can be unit tested.
 * Elements are immutable, so "changed" simply means "a different Element object than the one last sent".
 */
class LiveDiff {
    private val sent = HashMap<String, Element>()

    fun reset() = sent.clear()

    /** Messages for a whole scene: one "full" plus deltas, each below [chunk] characters. */
    fun full(title: String, scene: ExcalidrawScene, chunk: Int = CHUNK): List<String> {
        sent.clear()
        val els = scene.elements.filter { !it.isDeleted } // viewers don't need erased elements
        els.forEach { sent[it.id] = it }
        val out = ArrayList<String>()
        val app = buildJsonObject { put("viewBackgroundColor", scene.backgroundColor) }
        out += buildJsonObject { put("t", "full"); put("note", title); put("appState", app); put("elements", JsonArray(emptyList())) }.toString()
        out += deltas(els.map { it.json.toString() }, emptyList(), chunk)
        return out
    }

    /** Changes since the last call (or [full]); empty when nothing changed. */
    fun changes(scene: ExcalidrawScene, chunk: Int = CHUNK): List<String> {
        val upsert = ArrayList<String>()
        val seen = HashSet<String>(scene.elements.size * 2)
        for (e in scene.elements) {
            if (e.isDeleted) continue // erased: goes out as a removal below
            seen += e.id
            if (sent[e.id] !== e) { upsert += e.json.toString(); sent[e.id] = e }
        }
        val remove = sent.keys.filter { it !in seen }
        remove.forEach { sent.remove(it) }
        return deltas(upsert, remove, chunk)
    }

    companion object {
        const val CHUNK = 400_000
        private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ" // no I/O (they look like 1/0)
        private val random = SecureRandom()

        fun newCode(): String = String(CharArray(5) { LETTERS[random.nextInt(LETTERS.length)] })
        fun newHostKey(): String = (1..24).joinToString("") { "%02x".format(random.nextInt(256)) }

        /** Same id the viewer computes for a PDF page under a page frame. */
        fun pdfFileId(file: String, page: Int) = "pdf-" + file.filter { it.isLetterOrDigit() && it.code < 128 }.take(40) + "-" + page

        fun deltas(upsert: List<String>, remove: List<String>, chunk: Int = CHUNK): List<String> {
            if (upsert.isEmpty() && remove.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            val removeJson = JsonArray(remove.map { JsonPrimitive(it) }).toString()
            var batch = StringBuilder(); var first = true
            fun flush(rem: String) {
                out += """{"t":"delta","upsert":[$batch],"remove":$rem}"""
                batch = StringBuilder(); first = true
            }
            for (e in upsert) {
                if (!first && batch.length + e.length > chunk) flush("[]")
                if (!first) batch.append(',')
                batch.append(e); first = false
            }
            flush(removeJson)
            return out
        }

        fun message(t: String, vararg kv: Pair<String, JsonElement?>): String = buildJsonObject {
            put("t", t)
            for ((k, v) in kv) put(k, v ?: kotlinx.serialization.json.JsonNull)
        }.toString()

        fun view(x: Float, y: Float, w: Float, h: Float) =
            message("view", "x" to JsonPrimitive(x), "y" to JsonPrimitive(y), "w" to JsonPrimitive(w), "h" to JsonPrimitive(h))

        fun file(id: String, mime: String, dataUrl: String) =
            message("file", "id" to JsonPrimitive(id), "mimeType" to JsonPrimitive(mime), "dataURL" to JsonPrimitive(dataUrl))

        fun wet(el: JsonObject?) = message("wet", "el" to el)
    }
}
