package app.aether.sources.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.Artwork
import app.aether.core.model.Capability
import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.SearchFilter
import app.aether.core.model.SearchResults
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import app.aether.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The music on the phone itself (PODIUM's offline library, read from Android's media store): every
 * song the store lists as music, played straight from its file at whatever quality the file is.
 * Nothing leaves the phone. Needs the listener's permission to read their audio; without it, it
 * simply has nothing.
 */
class LocalSource(private val context: Context) : MusicSource {
    override val id = "local"
    override val name = "On this phone"
    override val capabilities = setOf(Capability.SEARCH, Capability.STREAM)

    /** Every song on the phone, by title. */
    suspend fun songs(): List<Track> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
        )
        val tracks = ArrayList<Track>()
        runCatching {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                while (c.moveToNext()) {
                    val key = c.getLong(iId)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key)
                    val artist = c.getString(iArtist)?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                    tracks += Track(
                        id = MediaId("$id:$key"),
                        title = c.getString(iTitle) ?: "",
                        artists = listOfNotNull(artist?.let { ArtistRef(it) }),
                        album = c.getString(iAlbum)?.takeIf { it.isNotBlank() }?.let { AlbumRef(it) },
                        durationMs = c.getLong(iDuration).takeIf { it > 0 },
                        // The store's own thumbnail of the file's embedded picture.
                        artwork = Artwork.single(uri.toString()),
                        trackNumber = (c.getInt(iTrack) % 1000).takeIf { it > 0 },
                        year = c.getInt(iYear).takeIf { it > 0 },
                    )
                }
            }
        }
        tracks
    }

    override suspend fun search(query: String, filter: SearchFilter?): Outcome<SearchResults> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Outcome.Ok(SearchResults(emptyList(), emptyList(), null))
        val found = songs().filter { t -> t.title.lowercase().contains(q) || t.artistLine.lowercase().contains(q) || t.album?.title?.lowercase()?.contains(q) == true }
        return Outcome.Ok(SearchResults(found, emptyList(), null))
    }

    override suspend fun stream(id: MediaId, efficient: Boolean): Outcome<Stream> {
        val key = id.value.substringAfter(':').toLongOrNull() ?: return Outcome.Err(SourceError.NotFound("song"))
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key)
        return Outcome.Ok(Stream(url = uri.toString(), cacheKey = id.value))
    }
}
