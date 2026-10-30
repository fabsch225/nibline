package dev.inkcalidraw

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile

/**
 * Handles links like  inkcalidraw://open?vault=Uni&path=Lectures/Week%203.excalidraw&abs=/storage/emulated/0/Documents/Uni
 * (sent by the Obsidian plugin). Finds the file inside a folder the user has granted (once per vault) and opens it
 * in the editor with write access, so edits go straight back into the vault.
 */
class OpenLinkActivity : ComponentActivity() {
    private var link: VaultLink? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val l = link
        if (tree == null || l == null) { finish(); return@registerForActivityResult }
        runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val hit = VaultResolver.resolve(this, l, listOf(tree))
        if (hit == null) {
            Toast.makeText(this, "“${l.path}” isn't in that folder. Pick your vault folder “${l.vault}”.", Toast.LENGTH_LONG).show()
            finish()
        } else openEditor(hit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val l = VaultLink.parse(intent?.data)
        if (l == null) { Toast.makeText(this, "Nibline: unsupported link", Toast.LENGTH_SHORT).show(); finish(); return }
        link = l
        if (savedInstanceState != null) return // waiting for the folder picker
        val hit = VaultResolver.resolve(this, l, VaultResolver.grantedTrees(this))
        if (hit != null) { openEditor(hit); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Allow access to “${l.vault}”")
            .setMessage("To edit drawings from Obsidian in place, Nibline needs access to your vault folder. Pick the folder “${l.vault}” on the next screen. This is only asked once.")
            .setPositiveButton("Choose folder") { _, _ -> pickFolder.launch(VaultResolver.initialHint(l)) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openEditor(hit: VaultResolver.Hit) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            data = hit.file
            hit.folder?.let { putExtra(EditorActivity.EXTRA_FOLDER, it.toString()) }
            putExtra(EditorActivity.EXTRA_NAME, NoteStore.displayName(link!!.path.substringAfterLast('/')))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        })
        setResult(Activity.RESULT_OK)
        finish()
    }
}

data class VaultLink(val vault: String, val path: String, val abs: String?) {
    companion object {
        fun parse(u: Uri?): VaultLink? {
            if (u == null || (u.scheme != "nibline" && u.scheme != "inkcalidraw")) return null
            val path = u.getQueryParameter("path")?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
            if (path.split('/').any { it == ".." }) return null
            val vault = u.getQueryParameter("vault") ?: ""
            val abs = u.getQueryParameter("abs")?.removePrefix("file://")?.trimEnd('/')?.takeIf { it.startsWith("/") }
            return VaultLink(vault, path, abs)
        }
    }
}

object VaultResolver {
    data class Hit(val file: Uri, val folder: Uri?)

    fun grantedTrees(ctx: Context): List<Uri> =
        ctx.contentResolver.persistedUriPermissions.filter { it.isWritePermission && DocumentsContract.isTreeUri(it.uri) }.map { it.uri }

    /** "primary:Documents/Uni" -> "/storage/emulated/0/Documents/Uni" (ExternalStorageProvider ids). */
    fun treePath(tree: Uri): String? {
        if (tree.authority != "com.android.externalstorage.documents") return null
        val id = DocumentsContract.getTreeDocumentId(tree)
        val vol = id.substringBefore(':'); val rel = id.substringAfter(':', "")
        val base = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /** Candidate paths of the file relative to a granted folder. */
    fun candidates(tp: String?, l: VaultLink, treeName: String?): List<String> {
        val out = mutableListOf<String>()
        if (l.abs != null && tp != null) {
            val full = "${l.abs}/${l.path}"
            if (full.startsWith("$tp/")) out += full.removePrefix("$tp/")
            // /sdcard and /storage/emulated/0 are the same place
            val alt = full.replaceFirst("/sdcard/", "/storage/emulated/0/")
            if (alt != full && alt.startsWith("$tp/")) out += alt.removePrefix("$tp/")
        }
        if (treeName != null && treeName == l.vault) out += l.path   // the vault folder itself
        if (l.vault.isNotEmpty()) out += "${l.vault}/${l.path}"      // a parent of the vault
        return out.distinct()
    }

    fun resolve(ctx: Context, l: VaultLink, trees: List<Uri>): Hit? {
        for (tree in trees) {
            val root = DocumentFile.fromTreeUri(ctx, tree) ?: continue
            for (rel in candidates(treePath(tree), l, root.name)) {
                find(ctx, tree, root, rel)?.let { return it }
            }
        }
        return null
    }

    private fun find(ctx: Context, tree: Uri, root: DocumentFile, rel: String): Hit? {
        // Path-based document ids (the usual storage provider): build the uri directly, no folder walking.
        if (tree.authority == "com.android.externalstorage.documents") {
            val base = DocumentsContract.getTreeDocumentId(tree)
            val sep = if (base.endsWith(":")) "" else "/"
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, base + sep + rel)
            if (exists(ctx, doc)) {
                val parentRel = rel.substringBeforeLast('/', "")
                val folder = if (parentRel.isEmpty()) DocumentsContract.buildDocumentUriUsingTree(tree, base)
                    else DocumentsContract.buildDocumentUriUsingTree(tree, base + sep + parentRel)
                return Hit(doc, folder)
            }
        }
        var dir: DocumentFile = root
        val parts = rel.split('/')
        for (p in parts.dropLast(1)) dir = dir.findFile(p)?.takeIf { it.isDirectory } ?: return null
        val f = dir.findFile(parts.last())?.takeIf { it.isFile } ?: return null
        return Hit(f.uri, dir.uri)
    }

    private fun exists(ctx: Context, doc: Uri) = runCatching {
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /** Start the folder picker near the vault when we know where it is. */
    fun initialHint(l: VaultLink): Uri? {
        val abs = l.abs?.replaceFirst("/sdcard", "/storage/emulated/0") ?: return null
        val rel = abs.removePrefix("/storage/emulated/0/").takeIf { it != abs } ?: return null
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    }
}
