package app.aether.core.model

/*
 * What every screen works with, whichever source the music comes from. A source turns its own
 * answers into these (sources:*); nothing outside a source reads a source's own ids or types.
 */

/**
 * A source-qualified id: `ytm:dQw4w9WgXcQ`, `local:1234`. The part after the colon belongs to the
 * source that made it and is never read anywhere else.
 */
@JvmInline
value class MediaId(val value: String) {
    val source: String get() = value.substringBefore(':')
    val key: String get() = value.substringAfter(':')
    override fun toString(): String = value

    companion object {
        fun of(source: String, key: String) = MediaId("$source:$key")
    }
}

/** How a playable item sounds and looks. */
enum class MediaKind { SONG, MUSIC_VIDEO, VIDEO, EPISODE }

/** One picture at the sizes its source offers, smallest first. */
data class Artwork(val sizes: List<ArtworkSize>) {
    val isEmpty: Boolean get() = sizes.isEmpty()

    /** The smallest size at least [px] wide, else the largest there is. */
    fun url(px: Int): String? = (sizes.firstOrNull { it.width >= px } ?: sizes.lastOrNull())?.url

    /** Width over height of the largest size; 1 when unknown. */
    val aspect: Float
        get() = sizes.lastOrNull()?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: 1f

    companion object {
        val None = Artwork(emptyList())
        fun single(url: String, width: Int = 0, height: Int = 0) = Artwork(listOf(ArtworkSize(url, width, height)))
    }
}

data class ArtworkSize(val url: String, val width: Int, val height: Int)

data class ArtistRef(val name: String, val id: MediaId? = null)

data class AlbumRef(val title: String, val id: MediaId? = null)

/** Where a tile, filter or "more" goes: a page of its source, opaque to everyone else. */
data class Destination(val source: String, val key: String, val params: String? = null)

/** How the source wants a track started (within its playlist, as a radio). Opaque outside the source. */
data class PlayContext(val key: String?, val params: String?)

/** How to get more of a list. Opaque: hand it back to the call that gave it. */
@JvmInline
value class PageToken(val value: String)

data class Page<T>(val items: List<T>, val next: PageToken?)

/** Anything a section, grid or result list can hold. */
sealed interface MediaEntity {
    val title: String
    val subtitle: String?
    val artwork: Artwork
}

data class Track(
    val id: MediaId,
    override val title: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef? = null,
    val durationMs: Long? = null,
    override val artwork: Artwork = Artwork.None,
    val kind: MediaKind = MediaKind.SONG,
    val explicit: Boolean = false,
    val trackNumber: Int? = null,
    val year: Int? = null,
    /** "1B plays", "13M views": as the source says. */
    val plays: String? = null,
    /** False when the source has it but can't play it here. */
    val available: Boolean = true,
    val context: PlayContext? = null,
    override val subtitle: String? = null,
) : MediaEntity {
    val artistLine: String get() = artists.joinToString(", ") { it.name }
}

data class Album(
    val id: MediaId,
    override val title: String,
    val artists: List<ArtistRef>,
    val year: Int? = null,
    override val artwork: Artwork = Artwork.None,
    /** "Album", "Single", "EP", as the source labels it. */
    val type: String? = null,
    val explicit: Boolean = false,
    override val subtitle: String? = null,
) : MediaEntity

data class Artist(
    val id: MediaId,
    val name: String,
    override val artwork: Artwork = Artwork.None,
    override val subtitle: String? = null,
) : MediaEntity {
    override val title: String get() = name
}

data class Playlist(
    val id: MediaId,
    override val title: String,
    val author: String? = null,
    val trackCount: Int? = null,
    override val artwork: Artwork = Artwork.None,
    override val subtitle: String? = null,
) : MediaEntity

data class Podcast(
    val id: MediaId,
    override val title: String,
    val author: String? = null,
    override val artwork: Artwork = Artwork.None,
    override val subtitle: String? = null,
) : MediaEntity

/** A tile that opens a page: a mood, a genre, one of Explore's entries. */
data class Link(
    override val title: String,
    val destination: Destination,
    /** ARGB, for moods and genres. */
    val color: Long? = null,
    /** The source's icon name, for Explore's entries. */
    val icon: String? = null,
) : MediaEntity {
    override val subtitle: String? get() = null
    override val artwork: Artwork get() = Artwork.None
}
