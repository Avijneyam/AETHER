package app.aether.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.edgeRefraction
import app.aether.core.designsystem.component.AlbumCard
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Loading
import app.aether.core.designsystem.component.Notice
import app.aether.core.designsystem.component.SectionHeading
import app.aether.core.designsystem.component.StepIndicator
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.separate
import app.aether.core.model.Album
import app.aether.core.model.AlbumPage
import app.aether.core.model.ArtistRef
import app.aether.core.model.Artwork
import app.aether.core.model.MediaId
import app.aether.core.model.PhoneSong
import app.aether.core.model.Track
import app.aether.core.model.songCount
import app.aether.ui.FolderLink
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.Colophon
import app.aether.ui.common.TrackList
import app.aether.ui.common.rememberPageListState
import app.aether.ui.detail.AlbumHero
import app.aether.ui.detail.BackLine

/**
 * An album made of what plays without a connection: songs on the phone, grouped as the phone groups
 * them (its own album, its album artist, disc by disc and track by track, as PODIUM reads them), or
 * songs kept from the catalogue, grouped by the album they're from.
 */
internal class OfflineAlbum(val key: String, val title: String, val artist: String?, val tracks: List<Track>, val newest: Long) {
    val artwork: Artwork get() = tracks.firstOrNull { !it.artwork.isEmpty }?.artwork ?: Artwork.None

    /** Whether its songs are by more than one artist, so each row says whose. */
    val various: Boolean get() = tracks.map { it.artistLine }.distinct().size > 1

    fun asAlbum(): Album = Album(
        id = MediaId.of("offline", key),
        title = title,
        // A compilation's "Various artists" names no one, so nothing is looked up by it.
        artists = listOfNotNull(artist?.takeIf { it != VariousArtists }?.let { ArtistRef(it) }),
        year = tracks.mapNotNull { it.year }.maxOrNull(),
        artwork = artwork,
        subtitle = listOfNotNull(artist, songCount(tracks.size)).joinToString("  ·  "),
    )

    fun page(): AlbumPage {
        val minutes = tracks.sumOf { it.durationMs ?: 0L } / 60_000
        return AlbumPage(asAlbum(), tracks, details = listOfNotNull(songCount(tracks.size), minutes.takeIf { it > 0 }?.let { "$it minutes" }).joinToString(" • "))
    }
}

private const val VariousArtists = "Various artists"

/** The phone's songs as albums, the ones that came onto the phone last first. */
internal fun phoneAlbums(songs: List<PhoneSong>): List<OfflineAlbum> = songs
    .filter { it.track.album != null }
    .groupBy { song -> song.albumKey ?: "${song.track.album!!.title.lowercase()}|${(song.albumArtist ?: song.track.artistLine).lowercase()}" }
    .map { (key, group) ->
        val ordered = group.sortedWith(compareBy<PhoneSong>({ it.disc }, { it.track.trackNumber ?: Int.MAX_VALUE }, { it.track.title.lowercase() }))
        val artist = group.firstNotNullOfOrNull { it.albumArtist }
            ?: group.map { it.track.artistLine }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }
            ?: VariousArtists
        OfflineAlbum("phone:$key", group.first().track.album!!.title, artist, ordered.map { it.track }, group.maxOf { it.addedAt })
    }
    .sortedByDescending { it.newest }

/** The songs kept from the catalogue as albums (a song from no album stands as its own), the last kept first. */
internal fun keptAlbums(kept: List<Track>): List<OfflineAlbum> = kept.withIndex()
    .groupBy { (_, t) -> t.album?.id?.value ?: t.album?.title?.let { "${it.lowercase()}|${t.artists.firstOrNull()?.name?.lowercase()}" } ?: "song:${t.id.value}" }
    .map { (key, group) ->
        val tracks = group.map { it.value }
        val first = tracks.first()
        val ordered = tracks.sortedWith(compareBy<Track>({ it.trackNumber ?: Int.MAX_VALUE }, { it.title.lowercase() }))
        val artist = tracks.mapNotNull { it.artists.firstOrNull()?.name }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        OfflineAlbum("kept:$key", first.album?.title ?: first.title, artist, ordered, group.maxOf { it.index }.toLong())
    }
    .sortedByDescending { it.newest }

/**
 * On Listen: the music that needs no connection, as albums, in two shelves: the phone's own, and
 * what's been kept from the catalogue. Before the phone's music can be read, or while it has none,
 * its shelf is the one way in, as a folder.
 */
@Composable
fun OfflineShelves() {
    val actions = LocalActions.current
    val music = rememberPhoneMusic()
    val phone = remember(music) { phoneAlbums(music.songs) }
    val kept = remember(music) { keptAlbums(music.kept) }
    if (phone.isNotEmpty()) {
        AlbumShelf("On this phone", "Plays without a connection", phone) { actions.nav.open(Route.OnThisPhone()) }
    } else {
        Column(Modifier.separate().fillMaxWidth().padding(top = 52.dp)) {
            SectionHeading("On this phone", strapline = "Plays without a connection")
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 22.dp)) { FolderLink(music.allCard.preview, music.allCard.route) }
        }
    }
    if (kept.isNotEmpty()) AlbumShelf("Kept", "Kept on this phone", kept) { actions.nav.open(Route.Kept) }
}

@Composable
private fun AlbumShelf(title: String, strapline: String, albums: List<OfflineAlbum>, onMore: () -> Unit) {
    val actions = LocalActions.current
    val state = rememberLazyListState()
    Column(Modifier.separate().fillMaxWidth().padding(top = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionHeading(title, strapline = strapline, onMore = onMore)
        Spacer(Modifier.height(18.dp))
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().edgeRefraction(state),
            contentPadding = PaddingValues(horizontal = Space.edge, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(albums, key = { it.key }) { album -> AlbumCard(album.asAlbum(), onClick = { actions.nav.open(Route.OfflineAlbum(album.key)) }) }
        }
        StepIndicator(state, Modifier.offset(y = (-6).dp))
    }
}

/** An album of the phone's songs or of kept ones: its head as every album's, and its songs, all playing offline. */
@Composable
fun OfflineAlbumScreen(key: String) {
    val actions = LocalActions.current
    val music = rememberPhoneMusic()
    val album = remember(music, key) {
        if (key.startsWith("kept:")) keptAlbums(music.kept).firstOrNull { it.key == key } else phoneAlbums(music.songs).firstOrNull { it.key == key }
    }
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        when {
            album != null -> {
                val page = album.page()
                item(key = "hero") { AlbumHero(page, onArtist = null) }
                item(key = "actions") {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassCircle(Glyph.Shuffle, "Shuffle", { actions.shuffle(album.tracks) }, size = 50.dp)
                            GlassButton("Play", onClick = { actions.play(album.tracks, 0) })
                            GlassCircle(Glyph.Plus, "Add to queue", { actions.playback.addToQueue(album.tracks) }, size = 50.dp)
                        }
                    }
                }
                item(key = "tracks") { TrackList(album.tracks, numbered = true, artists = album.various) }
            }
            !music.scanned && key.startsWith("phone:") -> {
                item { BackLine() }
                item { Loading() }
            }
            else -> {
                item { BackLine() }
                item { Notice("This album isn't here any more", "Its songs are no longer on this phone.") }
            }
        }
        item(key = "colophon") { Colophon() }
    }
}
