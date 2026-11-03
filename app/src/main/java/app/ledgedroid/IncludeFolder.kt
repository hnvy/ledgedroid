package app.ledgedroid

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.ledgedroid.core.IncludeException
import app.ledgedroid.core.IncludeResolver
import app.ledgedroid.core.IncludedFile

/**
 * Reads the files a journal's include directives name, from a folder the person chose (Settings ›
 * Included files): Android only lets the app read files it has been given, so the folder is granted once.
 * Paths are followed from the including file's folder within it, and from the main journal's own folder
 * in it when Android says where that is (otherwise from the chosen folder itself). The last part of a
 * path can have * and ? wildcards, like hledger's include globs.
 */
class IncludeFolder(private val context: Context, private val tree: Uri, private val mainFile: Uri?) : IncludeResolver {
    private val resolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    /** The main journal's folder, as a path under the chosen one ("" for the folder itself). */
    private val mainDir: String = mainFile?.let { relativeDir(it) } ?: ""

    private data class Entry(val id: String, val name: String, val isDir: Boolean)

    private val listings = HashMap<String, List<Entry>>()

    override fun find(pattern: String, from: String?): List<IncludedFile> {
        if (pattern.startsWith("/") || pattern.startsWith("~") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(pattern)) {
            throw IncludeException("a full path, which can't be followed on the phone; use a path from the journal's folder")
        }
        // Names are paths from the main journal's folder; the including file's folder is where to start.
        val fromDir = from?.substringBeforeLast('/', "") ?: ""
        val parts = normalise(join(join(mainDir, fromDir), pattern.replace('\\', '/')))
            ?: throw IncludeException("it goes outside the chosen folder")
        var dirId = rootId
        for (p in parts.dropLast(1)) {
            dirId = list(dirId).firstOrNull { it.isDir && it.name == p }?.id ?: return emptyList()
        }
        val last = parts.last()
        val re = if (last.contains('*') || last.contains('?')) globRegex(last) else null
        val matches = list(dirId).filter { !it.isDir && (if (re != null) re.matches(it.name) else it.name == last) }.sortedBy { it.name }
        val dir = parts.dropLast(1)
        return matches.map { e ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.id)
            val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IncludeException("${e.name} couldn't be opened")
            IncludedFile(relativeToMain(dir + e.name), text)
        }
    }

    private fun join(a: String, b: String) = if (a.isEmpty()) b else if (b.isEmpty()) a else "$a/$b"

    /** A path (parts under the chosen folder) as seen from the main journal's folder, maybe with "..". */
    private fun relativeToMain(parts: List<String>): String {
        val base = if (mainDir.isEmpty()) emptyList() else mainDir.split('/')
        var common = 0
        while (common < base.size && common < parts.size - 1 && base[common] == parts[common]) common++
        return (List(base.size - common) { ".." } + parts.drop(common)).joinToString("/")
    }

    private fun globRegex(g: String) = Regex(buildString {
        for (c in g) append(
            when (c) {
                '*' -> "[^/]*"
                '?' -> "[^/]"
                else -> Regex.escape(c.toString())
            }
        )
    })

    private fun list(dirId: String): List<Entry> = listings.getOrPut(dirId) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<Entry>()
        try {
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out.add(Entry(c.getString(0), c.getString(1), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        } catch (e: SecurityException) {
            throw IncludeException("the folder can no longer be read; choose it again in Settings › Included files")
        }
        out
    }

    /** The main file's folder under the chosen one, from document ids like "primary:Books/2026/main.journal". */
    private fun relativeDir(file: Uri): String? = try {
        val id = DocumentsContract.getDocumentId(file)
        if (id.startsWith("$rootId/")) id.removePrefix("$rootId/").substringBeforeLast('/', "") else null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Path parts with . and .. worked out; null if .. goes above the chosen folder. */
    private fun normalise(path: String): List<String>? {
        val out = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> Unit
            ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
            else -> out.add(p)
        }
        return out.ifEmpty { null }
    }

    companion object {
        private const val KEY = "includeFolder"

        fun chosen(context: Context): Uri? =
            context.getSharedPreferences("ledgedroid", Context.MODE_PRIVATE).getString(KEY, null)?.let(Uri::parse)

        /** Remember [tree] (keeping access to it across restarts), or forget the folder with null. */
        fun choose(context: Context, tree: Uri?) {
            val prefs = context.getSharedPreferences("ledgedroid", Context.MODE_PRIVATE)
            if (tree != null) {
                runCatching { context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            prefs.edit().apply { if (tree == null) remove(KEY) else putString(KEY, tree.toString()) }.apply()
        }

        /** The resolver for the chosen folder, or null if none is chosen. */
        fun resolver(context: Context, mainFile: Uri?): IncludeResolver? = chosen(context)?.let { IncludeFolder(context, it, mainFile) }

        /** The chosen folder's name, for Settings. */
        fun name(tree: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "(storage root)" } }.getOrDefault(tree.toString())
    }
}
