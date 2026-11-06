package app.aether.core.data

import app.aether.core.model.Album
import app.aether.core.model.AlbumRef
import app.aether.core.model.Artist
import app.aether.core.model.ArtistRef
import app.aether.core.model.Artwork
import app.aether.core.model.ArtworkSize
import app.aether.core.model.MediaId
import app.aether.core.model.MediaKind
import app.aether.core.model.PlayContext
import app.aether.core.model.Playlist
import app.aether.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The shared model as stored JSON. Mirrors, so the model itself stays free of any serialisation
 * library; unknown fields are ignored on read, so a newer app reads an older row.
 */

internal val storeJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

@Serializable
internal data class ArtworkDto(val s: List<SizeDto> = emptyList()) {
    @Serializable data class SizeDto(val u: String, val w: Int = 0, val h: Int = 0)
    fun toModel() = Artwork(s.map { ArtworkSize(it.u, it.w, it.h) })
}

internal fun Artwork.toDto() = ArtworkDto(sizes.map { ArtworkDto.SizeDto(it.url, it.width, it.height) })

@Serializable
internal data class RefDto(val n: String, val id: String? = null)

@Serializable
internal data class TrackDto(
    val id: String,
    val title: String,
    val artists: List<RefDto> = emptyList(),
    val album: RefDto? = null,
    val durationMs: Long? = null,
    val art: ArtworkDto = ArtworkDto(),
    val kind: String = "SONG",
    val explicit: Boolean = false,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val available: Boolean = true,
    val ctxKey: String? = null,
    val ctxParams: String? = null,
)

internal fun Track.toJson(): String = storeJson.encodeToString(
    TrackDto.serializer(),
    TrackDto(
        id = id.value,
        title = title,
        artists = artists.map { RefDto(it.name, it.id?.value) },
        album = album?.let { RefDto(it.title, it.id?.value) },
        durationMs = durationMs,
        art = artwork.toDto(),
        kind = kind.name,
        explicit = explicit,
        trackNumber = trackNumber,
        year = year,
        available = available,
        ctxKey = context?.key,
        ctxParams = context?.params,
    ),
)

internal fun trackFromJson(json: String): Track? = runCatching {
    val d = storeJson.decodeFromString(TrackDto.serializer(), json)
    Track(
        id = MediaId(d.id),
        title = d.title,
        artists = d.artists.map { ArtistRef(it.n, it.id?.let(::MediaId)) },
        album = d.album?.let { AlbumRef(it.n, it.id?.let(::MediaId)) },
        durationMs = d.durationMs,
        artwork = d.art.toModel(),
        kind = runCatching { MediaKind.valueOf(d.kind) }.getOrDefault(MediaKind.SONG),
        explicit = d.explicit,
        trackNumber = d.trackNumber,
        year = d.year,
        available = d.available,
        context = if (d.ctxKey != null || d.ctxParams != null) PlayContext(d.ctxKey, d.ctxParams) else null,
    )
}.getOrNull()

@Serializable
internal data class SavedDto(
    val id: String,
    val title: String,
    val line: String? = null,
    val artists: List<RefDto> = emptyList(),
    val year: Int? = null,
    val art: ArtworkDto = ArtworkDto(),
    val count: Int? = null,
)

internal fun Album.toSavedJson() = storeJson.encodeToString(
    SavedDto.serializer(),
    SavedDto(id.value, title, subtitle, artists.map { RefDto(it.name, it.id?.value) }, year, artwork.toDto()),
)

internal fun Artist.toSavedJson() = storeJson.encodeToString(SavedDto.serializer(), SavedDto(id.value, name, subtitle, art = artwork.toDto()))

internal fun Playlist.toSavedJson() = storeJson.encodeToString(
    SavedDto.serializer(),
    SavedDto(id.value, title, author, art = artwork.toDto(), count = trackCount),
)

internal fun albumFromJson(json: String): Album? = runCatching {
    val d = storeJson.decodeFromString(SavedDto.serializer(), json)
    Album(MediaId(d.id), d.title, d.artists.map { ArtistRef(it.n, it.id?.let(::MediaId)) }, d.year, d.art.toModel(), subtitle = d.line)
}.getOrNull()

internal fun artistFromJson(json: String): Artist? = runCatching {
    val d = storeJson.decodeFromString(SavedDto.serializer(), json)
    Artist(MediaId(d.id), d.title, d.art.toModel(), d.line)
}.getOrNull()

internal fun playlistFromJson(json: String): Playlist? = runCatching {
    val d = storeJson.decodeFromString(SavedDto.serializer(), json)
    Playlist(MediaId(d.id), d.title, d.line, d.count, d.art.toModel())
}.getOrNull()
