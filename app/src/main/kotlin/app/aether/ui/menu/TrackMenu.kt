package app.aether.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.GlassPanel
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.PanelDivider
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Track
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.NameDialog
import kotlinx.coroutines.launch
import app.aether.player.Downloads
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box

/** What can be done with a track: grouped glass rows, each with its flat mark. */
@Composable
fun TrackMenu(track: Track, close: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val liked by container.library.isLiked(track.id).collectAsStateWithLifecycle(false)
    val playlists by container.library.playlists.collectAsStateWithLifecycle(emptyList())
    val kept by container.downloads.entries.collectAsStateWithLifecycle()
    val keeping = kept[track.id]
    var choosing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.edge, vertical = 14.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TreatedArtwork(track.artwork, ArtTreatment.Print, Modifier.size(58.dp).clip(Corners.Small), sizeHintPx = 200)
            Spacer(Modifier.width(14.dp))
            Column {
                Txt(track.title, Type.TitleSmall, maxLines = 2)
                Txt(track.artistLine, Type.Meta, color = Ink.Grey, maxLines = 1)
            }
        }
        if (!choosing) {
            Head("This song")
            Entry(Glyph.Next, "Play next") { container.playback.playNext(listOf(track)); close() }
            Entry(Glyph.Queue, "Add to queue") { container.playback.addToQueue(listOf(track)); close() }
            Entry(Glyph.Plus, "Add to playlist") { choosing = true }
            Entry(if (liked) Glyph.HeartFilled else Glyph.Heart, if (liked) "Remove from liked songs" else "Like") {
                scope.launch { container.library.setLiked(track, !liked) }
                close()
            }
            run {
                when (keeping?.status) {
                    null -> Entry(Glyph.Download, "Keep on this phone") { container.downloads.keep(listOf(track)); close() }
                    Downloads.Status.Kept -> Entry(Glyph.Check, "Kept on this phone  ·  Remove") { container.downloads.forget(track.id); close() }
                    Downloads.Status.Failed -> Entry(Glyph.Download, "Couldn't keep it  ·  Try again") { container.downloads.forget(track.id); container.downloads.keep(listOf(track)); close() }
                    else -> Entry(Glyph.Download, "Keeping  ${(keeping.progress * 100).toInt()}%  ·  Stop") { container.downloads.forget(track.id); close() }
                }
            }
            Head("Go to")
            Entry(Glyph.Discover, "Start radio") { actions.play(listOf(track)); close() }
            track.album?.id?.let { album -> Entry(Glyph.Library, "Go to album") { actions.go(Route.Album(album)) } }
            track.artists.firstOrNull { it.id != null }?.let { a -> Entry(Glyph.Listen, "Go to ${a.name}") { actions.go(Route.Artist(a.id!!)) } }
        } else {
            Head("Add to playlist")
            Entry(Glyph.Plus, "New playlist") { naming = true }
            playlists.forEach { p ->
                Entry(Glyph.Library, p.title) {
                    scope.launch { container.library.add(p.id, listOf(track)) }
                    close()
                }
            }
        }
    }
    if (naming) NameDialog("New playlist", track.title, onDone = { name ->
        naming = false
        scope.launch { container.library.createPlaylist(name, tracks = listOf(track)) }
        close()
    }, onDismiss = { naming = false })
}

/** A group's name in small spaced capitals, over a hairline: no boxes, the type carries it. */
@Composable
private fun Head(title: String) {
    Txt(title, Type.Label, color = Ink.Grey, upper = true, modifier = Modifier.padding(start = 4.dp, top = 30.dp, bottom = 10.dp))
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}

/** One thing to do: its mark, its name in the engraved capitals, a hairline under it. */
@Composable
private fun Entry(glyph: Glyph, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().quietClick(onClick).padding(horizontal = 4.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(glyph, size = 17.dp, color = Ink.Grey)
        Spacer(Modifier.width(20.dp))
        Txt(label, Type.TitleSmall, maxLines = 1)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}
