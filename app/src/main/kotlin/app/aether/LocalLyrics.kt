package app.aether

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.aether.core.data.Settings
import app.aether.core.lyrics.EmbeddedLyrics
import app.aether.core.lyrics.Lyrics
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import app.aether.sources.local.LocalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lyrics already on the phone, for the songs that are on it, as other players keep them:
 *
 * - an `.lrc` file beside the song, of the same name (`Song.flac` and `Song.lrc`), in a folder the
 *   listener has let AETHER read (Android shows no app another app's text files without that);
 * - the words the file carries in its own tags (FLAC, Ogg, MP3, M4A), which needs nothing more;
 * - for a song kept in the listener's Music folder, the words AETHER wrote into it when it was kept.
 *
 * Read once a song and remembered for the session; nothing is sent anywhere.
 */
class LocalLyrics(private val context: Context, private val local: LocalSource, private val settings: Settings, private val kept: (MediaId) -> Uri?) {
    private val lock = Mutex()
    private val found = HashMap<String, Lyrics?>()

    /** The folders' `.lrc` files, by their path below the storage's root, lower case: built when first needed. */
    private var index: Map<String, Uri>? = null
    private var indexedFolders: List<String> = emptyList()
    private var indexedAt = 0L

    /** What's credited for words found on the phone. */
    data class Found(val lyrics: Lyrics, val credit: String)

    suspend fun of(track: Track): Found? = withContext(Dispatchers.IO) {
        val key = track.id.value
        lock.withLock { if (found.containsKey(key)) return@withContext found[key]?.let { Found(it, credit(track)) } }
        val lyrics = runCatching { look(track) }.getOrNull()
        lock.withLock { found[key] = lyrics }
        lyrics?.let { Found(it, credit(track)) }
    }

    private fun credit(track: Track) = if (track.id.source == local.id) "This phone" else "Kept with the song"

    private suspend fun look(track: Track): Lyrics? {
        if (track.id.source == local.id) {
            val file = local.file(track.id) ?: return null
            sidecar(file)?.let { return it }
            return embedded(file.uri)
        }
        return kept(track.id)?.let(::embedded)
    }

    private fun embedded(uri: Uri): Lyrics? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { EmbeddedLyrics.read(it.buffered(64 * 1024)) }
    }.getOrNull()

    private suspend fun sidecar(file: LocalSource.PhoneFile): Lyrics? {
        val folders = settings.lyricsFolders.value
        if (folders.isEmpty()) return null
        val stem = file.name.substringBeforeLast('.', file.name)
        val path = (file.folder.trimEnd('/') + "/" + stem + ".lrc").trimStart('/').lowercase()
        val uri = index(folders)[path] ?: return null
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_LRC).toByteArray().decodeToString() } }.getOrNull() ?: return null
        return EmbeddedLyrics.lyrics(text)
    }

    private suspend fun index(folders: List<String>): Map<String, Uri> = lock.withLock {
        val fresh = index?.takeIf { folders == indexedFolders && System.currentTimeMillis() - indexedAt < INDEX_FOR_MS }
        if (fresh != null) return@withLock fresh
        val out = HashMap<String, Uri>()
        folders.forEach { tree -> runCatching { walk(Uri.parse(tree), out) } }
        index = out
        indexedFolders = folders
        indexedAt = System.currentTimeMillis()
        out
    }

    /** Every `.lrc` under [tree], by its path from the storage's root ("Music/Album/Song.lrc"), a few levels deep at most. */
    private fun walk(tree: Uri, out: MutableMap<String, Uri>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        // A tree's document id is "volume:path": the path is where it sits below the storage's root.
        val rootPath = rootId.substringAfter(':', "").trim('/')
        val pending = ArrayDeque(listOf(rootId to rootPath))
        var seen = 0
        while (pending.isNotEmpty() && seen < MAX_ENTRIES) {
            val (doc, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && seen < MAX_ENTRIES) {
                    seen++
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    val childPath = if (path.isEmpty()) name else "$path/$name"
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (childPath.count { it == '/' } < MAX_DEPTH) pending += id to childPath
                        name.endsWith(".lrc", ignoreCase = true) -> out[childPath.lowercase()] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
    }

    /** Forget what was looked up (a folder was added, or files changed). */
    suspend fun forget() = lock.withLock {
        found.clear()
        index = null
    }

    private companion object {
        const val MAX_LRC = 512 * 1024
        const val MAX_ENTRIES = 40_000
        const val MAX_DEPTH = 8
        const val INDEX_FOR_MS = 10 * 60_000L
    }
}
