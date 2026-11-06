package app.aether.player

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.Artwork
import app.aether.core.model.ArtworkSize
import app.aether.core.model.MediaId
import app.aether.core.model.MediaKind
import app.aether.core.model.PlayContext
import app.aether.core.model.Track
import org.json.JSONArray
import org.json.JSONObject

/*
 * A track as Media3 carries it between the screens and the service: what the system's controls
 * show in its metadata, and the whole track in its extras, so the queue comes back intact on the
 * other side and after a restart.
 */

internal const val SCHEME = "aether"

internal fun trackUri(id: MediaId): Uri = Uri.Builder().scheme(SCHEME).authority("track").appendPath(id.value).build()

internal fun Uri.trackId(): MediaId? = if (scheme == SCHEME && authority == "track") lastPathSegment?.let(::MediaId) else null

fun Track.toMediaItem(): MediaItem {
    val extras = toBundle()
    val uri = trackUri(id)
    return MediaItem.Builder()
        .setMediaId(id.value)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistLine)
                .setAlbumTitle(album?.title)
                .setArtworkUri(artwork.url(720)?.toUri())
                .setDurationMs(durationMs)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)
                .build(),
        )
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(extras).build())
        .build()
}

/** The whole track back out of an item, whichever side of the session it crossed. */
fun MediaItem.toTrack(): Track? =
    (mediaMetadata.extras ?: requestMetadata.extras)?.toTrack()
        ?: mediaId.takeIf { it.contains(':') }?.let { id ->
            Track(MediaId(id), mediaMetadata.title?.toString() ?: "", listOfNotNull(mediaMetadata.artist?.toString()?.let { ArtistRef(it) }))
        }

/** The item with the address the player resolves (controllers never send one across). */
internal fun MediaItem.playable(): MediaItem =
    if (localConfiguration != null) this else buildUpon().setUri(requestMetadata.mediaUri ?: trackUri(MediaId(mediaId))).build()

private fun Track.toBundle() = Bundle().apply {
    putString("id", id.value)
    putString("title", title)
    putStringArray("artists", artists.map { it.name }.toTypedArray())
    putStringArray("artistIds", artists.map { it.id?.value ?: "" }.toTypedArray())
    album?.let { putString("album", it.title); putString("albumId", it.id?.value) }
    durationMs?.let { putLong("duration", it) }
    putStringArray("art", artwork.sizes.map { it.url }.toTypedArray())
    putIntArray("artW", artwork.sizes.map { it.width }.toIntArray())
    putIntArray("artH", artwork.sizes.map { it.height }.toIntArray())
    putString("kind", kind.name)
    putBoolean("explicit", explicit)
    trackNumber?.let { putInt("number", it) }
    year?.let { putInt("year", it) }
    putBoolean("available", available)
    context?.let { putString("ctxKey", it.key); putString("ctxParams", it.params) }
}

private fun Bundle.toTrack(): Track? {
    val id = getString("id") ?: return null
    val names = getStringArray("artists").orEmpty()
    val ids = getStringArray("artistIds").orEmpty()
    val art = getStringArray("art").orEmpty()
    val w = getIntArray("artW") ?: IntArray(0)
    val h = getIntArray("artH") ?: IntArray(0)
    return Track(
        id = MediaId(id),
        title = getString("title").orEmpty(),
        artists = names.mapIndexed { i, n -> ArtistRef(n, ids.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(::MediaId)) },
        album = getString("album")?.let { AlbumRef(it, getString("albumId")?.let(::MediaId)) },
        durationMs = if (containsKey("duration")) getLong("duration") else null,
        artwork = Artwork(art.mapIndexed { i, u -> ArtworkSize(u, w.getOrElse(i) { 0 }, h.getOrElse(i) { 0 }) }),
        kind = runCatching { MediaKind.valueOf(getString("kind") ?: "SONG") }.getOrDefault(MediaKind.SONG),
        explicit = getBoolean("explicit"),
        trackNumber = if (containsKey("number")) getInt("number") else null,
        year = if (containsKey("year")) getInt("year") else null,
        available = getBoolean("available", true),
        context = if (containsKey("ctxKey") || containsKey("ctxParams")) PlayContext(getString("ctxKey"), getString("ctxParams")) else null,
    )
}

private fun Array<String>?.orEmpty(): Array<String> = this ?: emptyArray()

/** The whole track as bytes, kept with its download: plain JSON, so it reads back after any update. */
internal fun Track.toBytes(): ByteArray = JSONObject().apply {
    put("id", id.value)
    put("title", title)
    put("artists", JSONArray(artists.map { JSONObject().put("name", it.name).put("id", it.id?.value ?: "") }))
    album?.let { put("album", it.title); put("albumId", it.id?.value ?: "") }
    durationMs?.let { put("duration", it) }
    put("art", JSONArray(artwork.sizes.map { JSONObject().put("url", it.url).put("w", it.width).put("h", it.height) }))
    put("kind", kind.name)
    put("explicit", explicit)
    trackNumber?.let { put("number", it) }
    year?.let { put("year", it) }
}.toString().toByteArray(Charsets.UTF_8)

/** A track back from its download's bytes; null if they aren't one. */
internal fun trackFromBytes(bytes: ByteArray): Track? = runCatching {
    val o = JSONObject(String(bytes, Charsets.UTF_8))
    val artists = o.optJSONArray("artists") ?: JSONArray()
    val art = o.optJSONArray("art") ?: JSONArray()
    Track(
        id = MediaId(o.getString("id")),
        title = o.optString("title"),
        artists = (0 until artists.length()).map { i -> artists.getJSONObject(i).let { a -> ArtistRef(a.optString("name"), a.optString("id").takeIf { it.isNotEmpty() }?.let(::MediaId)) } },
        album = o.optString("album").takeIf { o.has("album") }?.let { AlbumRef(it, o.optString("albumId").takeIf { id -> id.isNotEmpty() }?.let(::MediaId)) },
        durationMs = if (o.has("duration")) o.getLong("duration") else null,
        artwork = Artwork((0 until art.length()).map { i -> art.getJSONObject(i).let { a -> ArtworkSize(a.optString("url"), a.optInt("w"), a.optInt("h")) } }),
        kind = runCatching { MediaKind.valueOf(o.optString("kind", "SONG")) }.getOrDefault(MediaKind.SONG),
        explicit = o.optBoolean("explicit"),
        trackNumber = if (o.has("number")) o.getInt("number") else null,
        year = if (o.has("year")) o.getInt("year") else null,
    )
}.getOrNull()
