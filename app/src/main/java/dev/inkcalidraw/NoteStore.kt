package dev.inkcalidraw

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notes are plain .excalidraw files in a folder tree: either the app's private storage (default) or a
 * folder the user picked (e.g. an Obsidian vault), accessed via the Storage Access Framework.
 * DocumentFile gives both the same API. PDFs being annotated live next to their note.
 */
class NoteStore(private val context: Context) {
    data class Note(val uri: Uri, val name: String, val lastModified: Long)
    data class Folder(val uri: Uri, val name: String)
    data class Listing(val folders: List<Folder>, val notes: List<Note>)

    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)

    val treeUri: Uri? get() = prefs.getString("tree", null)?.let(Uri::parse)
    val isExternal: Boolean get() = treeUri != null

    private fun internalRoot() = File(context.filesDir, "notes").apply { mkdirs() }

    fun root(): DocumentFile {
        treeUri?.let { uri -> DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() }?.let { return it } }
        return DocumentFile.fromFile(internalRoot())
    }

    /** Re-opens a folder from its uri (as passed between screens). null = root. */
    fun folder(uri: Uri?): DocumentFile {
        if (uri == null) return root()
        return if (uri.scheme == "file") DocumentFile.fromFile(File(uri.path!!))
        else DocumentFile.fromTreeUri(context, uri) ?: root()
    }

    fun rootLabel(): String = if (isExternal) (root().name ?: "Chosen folder") else "On this tablet"

    fun setTree(uri: Uri?) {
        if (uri != null) context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().apply { if (uri == null) remove("tree") else putString("tree", uri.toString()) }.apply()
    }

    fun list(folder: DocumentFile): Listing {
        val all = folder.listFiles()
        val folders = all.filter { it.isDirectory && !(it.name ?: "").startsWith(".") }
            .map { Folder(it.uri, it.name ?: "Folder") }.sortedBy { it.name.lowercase() }
        val notes = all.filter { it.isFile && (it.name ?: "").endsWith(EXT, ignoreCase = true) }
            .map { Note(it.uri, displayName(it.name ?: ""), it.lastModified()) }
            .sortedByDescending { it.lastModified }
        return Listing(folders, notes)
    }

    /** All folders below [from] (breadth-first, limited), for "Move to…". */
    fun allFolders(from: DocumentFile = root(), maxDepth: Int = 4): List<Pair<String, DocumentFile>> {
        val out = mutableListOf("/" to from)
        var level = listOf("" to from)
        repeat(maxDepth) {
            level = level.flatMap { (path, f) ->
                f.listFiles().filter { it.isDirectory && !(it.name ?: "").startsWith(".") }.map { "$path/${it.name}" to it }
            }
            out += level
            if (level.isEmpty()) return out
        }
        return out
    }

    fun create(folder: DocumentFile, baseName: String? = null, content: String = ExcalidrawScene.empty().toJson()): Note {
        val name = uniqueName(folder, baseName ?: ("Note " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())), EXT)
        val file = folder.createFile(MIME, name + EXT) ?: error("Couldn't create a file in ${folder.name}")
        if (file.name != name + EXT) file.renameTo(name + EXT)
        write(file.uri, content)
        return Note(file.uri, name, System.currentTimeMillis())
    }

    fun createFolder(parent: DocumentFile, name: String): DocumentFile =
        parent.createDirectory(uniqueName(parent, name.trim().ifEmpty { "Folder" }, "")) ?: error("Couldn't create folder")

    fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    fun write(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
    }

    /**
     * Renames a note; returns its new uri (content uris change when renamed), or null if that failed.
     * DocumentFile.renameTo can't be used for documents in a chosen folder: those are SingleDocumentFiles, which
     * throw on rename, so this goes through DocumentsContract directly.
     */
    fun rename(uri: Uri, folder: DocumentFile, newName: String, ext: String = EXT): Uri? {
        val current = queryName(uri) ?: doc(uri)?.name
        val name = uniqueName(folder, newName.trim().ifEmpty { "Untitled" }, ext, except = current) + ext
        if (name == current) return uri
        return runCatching {
            if (uri.scheme == "file") {
                val src = File(uri.path!!); val dst = File(src.parentFile, name)
                if (src.renameTo(dst)) Uri.fromFile(dst) else null
            } else DocumentsContract.renameDocument(context.contentResolver, uri, name)
        }.getOrNull()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") File(uri.path!!).name
        else context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun delete(uri: Uri): Boolean = doc(uri)?.delete() ?: false

    fun duplicate(folder: DocumentFile, note: Note): Note = create(folder, "${note.name} copy", read(note.uri))

    /** Moves a note (and the PDFs it annotates) to another folder. Returns the note's new uri. */
    fun move(note: Note, from: DocumentFile, to: DocumentFile): Uri {
        if (from.uri == to.uri) return note.uri
        val pdfs = runCatching { ExcalidrawScene.parse(read(note.uri)).elements.mapNotNull { it.pdfFile }.toSet() }.getOrDefault(emptySet())
        for (p in pdfs) from.findFile(p)?.let { moveFile(it, from, to) }
        return moveFile(doc(note.uri)!!, from, to)
    }

    private fun moveFile(f: DocumentFile, from: DocumentFile, to: DocumentFile): Uri {
        if (f.uri.scheme == "file" && to.uri.scheme == "file") {
            val src = File(f.uri.path!!); val dst = File(File(to.uri.path!!), f.name!!)
            if (src.renameTo(dst)) return Uri.fromFile(dst)
        } else if (f.uri.scheme == "content" && to.uri.scheme == "content") {
            runCatching { DocumentsContract.moveDocument(context.contentResolver, f.uri, from.uri, to.uri) }.getOrNull()?.let { return it }
        }
        // Fallback: copy + delete
        val name = uniqueName(to, (f.name ?: "file").substringBeforeLast('.'), "." + (f.name ?: "").substringAfterLast('.', "bin"))
        val dst = to.createFile(MIME, name + "." + (f.name ?: "").substringAfterLast('.', "bin")) ?: error("Couldn't move ${f.name}")
        context.contentResolver.openInputStream(f.uri)!!.use { i -> context.contentResolver.openOutputStream(dst.uri, "wt")!!.use { o -> i.copyTo(o) } }
        f.delete()
        return dst.uri
    }

    /** Copies an outside file (e.g. a picked PDF) into [folder]; returns the stored file name. */
    fun importFile(folder: DocumentFile, src: Uri, baseName: String, ext: String): String {
        val name = uniqueName(folder, baseName, ext) + ext
        val dst = folder.createFile(MIME, name) ?: error("Couldn't create $name")
        if (dst.name != name) dst.renameTo(name)
        context.contentResolver.openInputStream(src)!!.use { i -> context.contentResolver.openOutputStream(dst.uri, "wt")!!.use { o -> i.copyTo(o) } }
        return dst.name ?: name
    }

    fun findFile(folder: DocumentFile, name: String): Uri? = folder.findFile(name)?.uri

    private fun doc(uri: Uri): DocumentFile? =
        if (uri.scheme == "file") DocumentFile.fromFile(File(uri.path!!)) else DocumentFile.fromSingleUri(context, uri)

    private fun uniqueName(folder: DocumentFile, base: String, ext: String, except: String? = null): String {
        val existing = folder.listFiles().mapNotNull { it.name?.lowercase() }.toSet() - (except?.lowercase() ?: "")
        val stem = base.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        var candidate = stem
        var i = 2
        while ((candidate + ext).lowercase() in existing) candidate = "$stem (${i++})"
        return candidate
    }

    companion object {
        const val EXT = ".excalidraw"
        const val MIME = "application/octet-stream"
        fun displayName(fileName: String) = fileName.removeSuffix(EXT).removeSuffix(".json")
    }
}
