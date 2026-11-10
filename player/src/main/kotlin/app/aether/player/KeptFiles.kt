package app.aether.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Kept songs as files of their own, in the listener's Music folder (`Music/AETHER`), as BitChord and
 * LastWave keep theirs: the phone's file manager lists them, any player opens them, and they can be
 * copied, backed up or deleted like any music. Each is the very audio that streamed, never encoded
 * again: YouTube's Opus moved into an Ogg Opus file ([OggOpus]), its AAC kept as the M4A it is. An Opus
 * file carries the song's title, artist, album, cover and words in its tags.
 *
 * Written through Android's media store, which needs no permission for the files AETHER makes;
 * hidden from everything else until it's whole. Which file each kept song is lives in a small list of
 * AETHER's own ([entries]), with the whole track, so a kept song plays as itself with no network.
 */
@OptIn(UnstableApi::class)
class KeptFiles internal constructor(private val context: Context) {
    /** A kept song's file: where it is, what it's called, and the song. */
    data class Entry(val track: Track, val uri: Uri, val name: String)

    private val store = File(context.filesDir, "kept-files.json")
    private val _entries = MutableStateFlow(read())
    val entries: StateFlow<Map<MediaId, Entry>> = _entries.asStateFlow()

    fun uriOf(id: MediaId): Uri? = _entries.value[id]?.uri

    /** Whether [uri] is one of AETHER's kept files (so the phone's own list doesn't show it twice). */
    fun isKept(uri: String): Boolean = _entries.value.values.any { it.uri.toString() == uri }

    /**
     * [track]'s audio, kept in the app's store, written out as its own file in the Music folder, with
     * [lyrics] (LRC) and [cover] in its tags. Null when it couldn't be (the store refused it, there's no
     * room): the song stays kept in the app's store, and still plays.
     */
    suspend fun export(track: Track, lyrics: String?, cover: ByteArray?): Entry? = withContext(Dispatchers.IO) {
        _entries.value[track.id]?.let { return@withContext it }
        val work = File(context.cacheDir, "keeping").apply { mkdirs() }
        val raw = File(work, "${track.id.value.hashCode()}.part")
        try {
            if (!copyOut(track.id, raw)) return@withContext null
            val head = raw.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
            val webm = head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
            val mp4 = String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
            val (extension, mime) = when {
                webm -> "opus" to "audio/ogg"
                mp4 -> "m4a" to "audio/mp4"
                else -> return@withContext null
            }
            val name = fileName(track, extension)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Audio.Media.TITLE, track.title)
                track.artists.firstOrNull()?.let { put(MediaStore.Audio.Media.ARTIST, track.artistLine) }
                track.album?.let { put(MediaStore.Audio.Media.ALBUM, it.title) }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            val written = runCatching {
                resolver.openOutputStream(uri)?.buffered(256 * 1024)?.use { out ->
                    if (webm) {
                        val tags = OggOpus.Tags(
                            title = track.title,
                            artist = track.artistLine.takeIf { it.isNotBlank() },
                            album = track.album?.title,
                            year = track.year,
                            number = track.trackNumber,
                            lyrics = lyrics,
                            cover = cover,
                            aether = String(track.toBytes(), Charsets.UTF_8),
                        )
                        OggOpus.remux(raw, out, tags)
                    } else {
                        raw.inputStream().use { it.copyTo(out, 256 * 1024) }
                        true
                    }
                } == true
            }.getOrDefault(false)
            if (!written) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val entry = Entry(track, uri, name)
            _entries.value = _entries.value + (track.id to entry)
            write()
            entry
        } catch (e: Exception) {
            null
        } finally {
            raw.delete()
        }
    }

    /** A kept song let go: its file in the Music folder deleted (it's AETHER's own), and forgotten. */
    suspend fun forget(id: MediaId) = withContext(Dispatchers.IO) {
        val entry = _entries.value[id] ?: return@withContext
        runCatching { context.contentResolver.delete(entry.uri, null, null) }
        _entries.value = _entries.value - id
        write()
    }

    /** Files the listener deleted themselves (in the file manager) are no longer kept. */
    suspend fun prune() = withContext(Dispatchers.IO) {
        val gone = _entries.value.filterValues { e ->
            runCatching { context.contentResolver.openFileDescriptor(e.uri, "r")?.use { false } ?: true }.getOrDefault(true)
        }.keys
        if (gone.isEmpty()) return@withContext
        _entries.value = _entries.value - gone
        write()
    }

    /** The audio kept for [id] in the app's store, copied out whole to [to]; false if it isn't all there. */
    private fun copyOut(id: MediaId, to: File): Boolean {
        // The store alone: nothing is fetched to fill a gap, so a song not wholly kept isn't written out.
        val source = CacheDataSource.Factory().setCache(AudioStore.cache(context)).setCacheKeyFactory(AudioStore::keyOf).setCacheWriteDataSinkFactory(null).createDataSource()
        return try {
            val spec = DataSpec.Builder().setUri(trackUri(id)).setKey("track:${id.value}").build()
            source.open(spec)
            to.outputStream().buffered(256 * 1024).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, n)
                }
            }
            to.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { source.close() }
        }
    }

    private fun fileName(track: Track, extension: String): String {
        fun clean(s: String) = s.replace(ILLEGAL, " ").replace(SPACES, " ").trim().trim('.')
        val artist = clean(track.artists.firstOrNull()?.name.orEmpty())
        val title = clean(track.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { track.id.key }
        return "${stem.take(120).trimEnd()}.$extension"
    }

    private fun read(): Map<MediaId, Entry> = runCatching {
        val array = JSONArray(store.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val track = trackFromBytes(o.getString("track").toByteArray(Charsets.UTF_8)) ?: return@mapNotNull null
            track.id to Entry(track, Uri.parse(o.getString("uri")), o.optString("name"))
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun write() {
        val array = JSONArray()
        _entries.value.values.forEach { e ->
            array.put(JSONObject().put("track", String(e.track.toBytes(), Charsets.UTF_8)).put("uri", e.uri.toString()).put("name", e.name))
        }
        runCatching {
            val part = File(store.parentFile, store.name + ".part")
            part.writeText(array.toString())
            part.renameTo(store)
        }
    }

    companion object {
        /** Where kept songs go: the Music folder's own, for AETHER. */
        const val FOLDER = "Music/AETHER"
        private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
        private val SPACES = Regex("""\s+""")
    }
}
