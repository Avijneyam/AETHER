@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.library

import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import app.aether.ui.common.rememberPageListState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.component.AlbumCard
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.GlassSegments
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Space
import app.aether.ui.common.TrackList
import app.aether.core.designsystem.component.ArtistCard
import app.aether.core.designsystem.component.FolderCard
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.Notice
import app.aether.core.designsystem.component.TrackRow
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.songCount
import app.aether.core.model.Artwork
import app.aether.core.model.Track
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.NameDialog
import app.aether.ui.detail.BackLine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import app.aether.R
import app.aether.ui.common.Colophon
import app.aether.ui.detail.SleeveActions
import app.aether.ui.detail.SleeveHead
import app.aether.ui.detail.SleeveLabel
import app.aether.ui.detail.sleeveTracks
import app.aether.ui.FolderLink
import app.aether.player.Downloads
import app.aether.ui.FolderPreview
import app.aether.ui.pageLanded

private enum class Shelf(val label: String) { Playlists("Playlists"), Kept("Kept"), Phone("On this phone"), Songs("Songs"), Albums("Albums"), Artists("Artists"), History("History") }

/** The listener's own archive. */
@Composable
fun LibraryScreen() {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    var shelf by rememberSaveable { mutableStateOf(Shelf.Playlists) }
    var naming by remember { mutableStateOf(false) }
    val liked by container.library.liked.collectAsStateWithLifecycle(emptyList())
    val playlists by container.library.playlistsWithPreview().collectAsStateWithLifecycle(emptyList())
    val saved by container.library.savedPlaylists.collectAsStateWithLifecycle(emptyList())
    val albums by container.library.albums.collectAsStateWithLifecycle(emptyList())
    val artists by container.library.artists.collectAsStateWithLifecycle(emptyList())
    val history by container.library.history(200).collectAsStateWithLifecycle(emptyList())
    val kept by container.downloads.entries.collectAsStateWithLifecycle()
    // The phone's own music: read only with the listener's permission.
    val music = rememberPhoneMusic()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
            item {
                // The settings key floats in the shell's top corner, over the frost. Over the room's
                // name, the keeper's crossed keys.
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(64.dp))
                    Image(painterResource(R.drawable.emblem_keys), contentDescription = null, modifier = Modifier.height(46.dp))
                    Spacer(Modifier.height(14.dp))
                    Txt("Library", Type.Monument)
                    Spacer(Modifier.height(26.dp))
                    GlassSegments(Shelf.entries.map { it.label }, shelf.ordinal, { shelf = Shelf.entries[it] }, Modifier.padding(horizontal = Space.edge))
                    Spacer(Modifier.height(18.dp))
                }
            }
            when (shelf) {
                Shelf.Playlists -> {
                    val cards = buildList<@Composable () -> Unit> {
                        add {
                            FolderCard(
                                title = "Liked songs",
                                detail = songCount(liked.size),
                                artwork = liked.firstOrNull()?.artwork ?: Artwork.None,
                                files = liked.take(3).map { FolderFile(it.title, it.artwork) },
                                onOpen = { actions.nav.open(Route.Liked) },
                            )
                        }
                        playlists.forEach { p ->
                            add {
                                FolderLink(
                                    FolderPreview("folder:local:${p.id}", p.title, songCount(p.count), p.preview.firstOrNull()?.artwork ?: Artwork.None, p.preview.map { FolderFile(it.title, it.artwork) }),
                                    Route.LocalPlaylist(p.id),
                                )
                            }
                        }
                        saved.forEach { p ->
                            add {
                                FolderLink(FolderPreview("folder:${p.id}", p.title, p.author, p.artwork, emptyList()), Route.Playlist(p.id))
                            }
                        }
                        add { NewTile(onClick = { naming = true }) }
                    }
                    items(cards.chunked(2)) { pair ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            pair.forEach { it() }
                            if (pair.size == 1) Spacer(Modifier.width(168.dp))
                        }
                    }
                }
                Shelf.Songs -> {
                    if (liked.isEmpty()) item { Notice("No liked songs yet", "Tap the heart on Now Playing to keep a song here.") }
                    else {
                        item { PlayRow(liked) }
                        item { TrackList(liked, playContext = liked) }
                    }
                }
                Shelf.Kept -> {
                    val ready = kept.values.filter { it.status == Downloads.Status.Kept }.map { it.track }
                    val under = kept.values.filter { it.status == Downloads.Status.Keeping || it.status == Downloads.Status.Queued }
                    if (kept.isEmpty()) item { Notice("Nothing kept yet", "Hold a song and choose Keep on this phone, or keep a whole playlist from its page. Kept songs play without a connection.") }
                    else {
                        if (under.isNotEmpty()) item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Strapline("Keeping ${under.size}  ·  ${(under.sumOf { it.progress.toDouble() } / under.size * 100).toInt()}%")
                            }
                        }
                        if (ready.isNotEmpty()) {
                            item { PlayRow(ready) }
                            item { TrackList(ready, playContext = ready) }
                        }
                    }
                }
                Shelf.Phone -> phoneShelf(music)
                Shelf.Albums -> {
                    if (albums.isEmpty()) item { Notice("No albums yet", "Save an album with the heart on its page.") }
                    else items(albums.chunked(2)) { pair ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            pair.forEach { AlbumCard(it, onClick = { actions.open(it) }, width = 160.dp) }
                            if (pair.size == 1) Spacer(Modifier.width(160.dp))
                        }
                    }
                }
                Shelf.Artists -> {
                    if (artists.isEmpty()) item { Notice("No artists yet", "Follow an artist from their page.") }
                    else items(artists.chunked(2)) { pair ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            pair.forEach { ArtistCard(it, onClick = { actions.open(it) }, width = 140.dp) }
                            if (pair.size == 1) Spacer(Modifier.width(140.dp))
                        }
                    }
                }
                Shelf.History -> {
                    if (history.isEmpty()) item { Notice("Nothing played yet", "Songs you listen to past their first half-minute appear here.") }
                    else {
                        val byDay = history.groupBy { Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalDate() }
                        byDay.forEach { (day, plays) ->
                            item(key = "d:$day") { Strapline(dayLabel(day), Modifier.padding(start = Space.edge, top = 28.dp, bottom = 10.dp)) }
                            item(key = "p:$day") { TrackList(plays.map { it.track }, playContext = plays.map { it.track }) }
                        }
                    }
                }
            }
            item(key = "colophon") { Colophon() }
        }
        if (naming) NameDialog("New playlist", "", onDone = { name -> naming = false; scope.launch { container.library.createPlaylist(name) } }, onDismiss = { naming = false })
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
    }
}

/** An empty folder outline: makes a new playlist. */
@Composable
private fun NewTile(onClick: () -> Unit) {
    Column(Modifier.width(168.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(168.dp, 144.dp).liquidGlass(Corners.Large, GlassWeight.Control, backdrop = null).quietClick(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                app.aether.core.designsystem.component.Icon(Glyph.Plus, size = 22.dp, color = Ink.Grey)
                Spacer(Modifier.height(10.dp))
                Txt("New playlist", Type.Aside, color = Ink.Grey)
            }
        }
    }
}

@Composable
internal fun PlayRow(tracks: List<Track>) {
    val actions = LocalActions.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        GlassCircle(Glyph.Shuffle, "Shuffle", { actions.shuffle(tracks) }, size = 52.dp)
        Spacer(Modifier.width(14.dp))
        GlassButton("Play", onClick = { actions.play(tracks, 0) })
        Spacer(Modifier.width(14.dp))
        GlassCircle(Glyph.Plus, "Add to queue", { actions.playback.addToQueue(tracks) }, size = 52.dp)
    }
}

@Composable
fun LikedScreen() {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val liked by container.library.liked.collectAsStateWithLifecycle(emptyList())
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item { Txt("Liked songs", Type.Monument, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) }
        item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Strapline(songCount(liked.size)) } }
        if (liked.isEmpty()) item { Notice("No liked songs yet", "Tap the heart on Now Playing to keep a song here.") }
        else {
            item { PlayRow(liked) }
            item { TrackList(liked, playContext = liked) }
        }
        item(key = "colophon") { Colophon() }
    }
}

@Composable
fun HistoryScreen() {
    LibraryScreen()
}

/**
 * A playlist the listener made, as a record's sleeve: the folder open at its head, the tracklist in
 * sides, the label at its foot. Edit turns the list into one you can reorder and take songs out of;
 * rename it, or let it go.
 */
@Composable
fun LocalPlaylistScreen(id: Long) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val playlist by container.library.playlist(id).collectAsStateWithLifecycle(null)
    val entries by container.library.entries(id).collectAsStateWithLifecycle(emptyList())
    val playback by container.playback.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val p = playlist
    val tracks = entries.map { it.track }
    // The folder that was touched stands open at once; the list follows once it has landed.
    val preview = actions.preview?.takeIf { it.key == "folder:local:$id" }
    val landed = pageLanded()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
            item { BackLine() }
            if (p == null) {
                if (preview != null) item(key = "head") { SleeveHead(preview.title, preview.detail, preview.artwork, preview.files) {} }
                else item { Notice("This playlist is gone", "It may have been deleted.") }
                return@LazyColumn
            }
            item(key = "head") {
                // As it was when touched: the folder never reloads into something else on its page.
                val files = preview?.files ?: tracks.take(3).map { FolderFile(it.title, it.artwork) }
                SleeveHead(p.title, songCount(entries.size), preview?.artwork ?: tracks.firstOrNull()?.artwork ?: Artwork.None, files) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (tracks.isNotEmpty()) {
                            SleeveActions(tracks, saved = null, onSave = null, follows = app.aether.KeepSync.local(id))
                            Spacer(Modifier.height(16.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (tracks.isNotEmpty()) GlassPill(if (editing) "Done" else "Edit", icon = if (editing) Glyph.Check else Glyph.Edit, onClick = { editing = !editing }, lit = editing, small = true)
                            GlassPill("Rename", onClick = { renaming = true }, small = true)
                            GlassPill("Delete", icon = Glyph.Trash, onClick = {
                                container.keepSync.stop(app.aether.KeepSync.local(id))
                                scope.launch { container.library.deletePlaylist(id) }
                                actions.nav.back()
                            }, small = true)
                        }
                        p.description?.let {
                            Spacer(Modifier.height(16.dp))
                            Txt(it, Type.Aside, color = Ink.Grey, align = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
                        }
                    }
                }
            }
            if (!landed) return@LazyColumn
            if (entries.isEmpty()) item { Notice("No songs yet", "Hold a song anywhere and choose Add to playlist.") }
            if (!editing) {
                sleeveTracks(tracks, playing = playback.current?.id, isPlaying = playback.isPlaying, keyPrefix = "t") { i -> actions.play(tracks, i) }
            } else itemsIndexed(entries, key = { _, e -> e.entryId }) { i, e ->
                var dy by remember { mutableFloatStateOf(0f) }
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp).graphicsLayer { translationY = dy }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TrackRow(e.track, onClick = { actions.play(tracks, i) }, onLongClick = { actions.openMenu(e.track) }, trailing = {}) }
                    IconAction(Glyph.Close, "Remove", { scope.launch { container.library.remove(id, e.entryId) } }, size = 13.dp)
                    Box(
                        Modifier.size(40.dp).pointerInput(e.entryId) {
                            detectVerticalDragGestures(onDragEnd = { dy = 0f }) { change, amount ->
                                change.consume()
                                dy += amount
                                val step = 62.dp.toPx()
                                if (abs(dy) > step) {
                                    val to = (i + if (dy > 0) 1 else -1).coerceIn(0, entries.lastIndex)
                                    scope.launch { container.library.move(id, e.entryId, to) }
                                    dy = 0f
                                }
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Txt("≡", Type.Title, color = Ink.Dim) }
                }
            }
            if (tracks.isNotEmpty()) item(key = "label") { SleeveLabel(tracks.first().artwork, p.title, "Kept by you", tracks) }
            item(key = "colophon") { Colophon() }
        }
        if (renaming && p != null) NameDialog("Rename", p.title, onDone = { name -> renaming = false; scope.launch { container.library.editPlaylist(id, name, p.description) } }, onDismiss = { renaming = false })
    }
}
