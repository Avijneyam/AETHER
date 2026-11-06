package app.aether.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.FingertipLight
import app.aether.core.designsystem.component.FolderCard
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.clock
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Sans
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Artwork
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import app.aether.ui.LocalActions
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.aether.core.designsystem.theme.Serif
import app.aether.core.designsystem.theme.separate
import app.aether.core.model.withoutEmoji
import app.aether.ui.folderHead
import app.aether.ui.pageLanded
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.player.Downloads
import app.aether.ui.common.LocalContainer

/*
 * A playlist's page as a record's sleeve: the folder that was touched flies up and stays open and
 * large at the head of the page; the tracklist is set as on the back of a sleeve, in sides; the
 * label's credit sits at the foot.
 */

/** How wide a folder stands at the head of its page. */
val FolderHeadWidth = 300.dp

/**
 * The folder held open at the head, where a folder that was touched lands, and the page's actions
 * under it. Its [files] are the first songs, or (before the list has loaded) what the folder showed.
 */
@Composable
fun SleeveHead(title: String, detail: String?, artwork: Artwork, files: List<FolderFile>, actions: @Composable () -> Unit) {
    Column(Modifier.separate().fillMaxWidth().padding(top = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FolderCard(
            title = title,
            detail = detail,
            artwork = artwork,
            files = files,
            onOpen = {},
            width = FolderHeadWidth,
            opened = true,
            modifier = Modifier.folderHead(),
        )
        Spacer(Modifier.height(28.dp))
        actions()
        Spacer(Modifier.height(34.dp))
    }
}

/** Shuffle, play, keep it in the library (when it can be), and keep its songs on the phone. */
@Composable
fun SleeveActions(tracks: List<Track>, saved: Boolean?, onSave: (() -> Unit)?) {
    val actions = LocalActions.current
    val container = LocalContainer.current
    val kept by container.downloads.entries.collectAsStateWithLifecycle()
    val allKept = tracks.isNotEmpty() && tracks.all { kept[it.id]?.status == Downloads.Status.Kept }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCircle(Glyph.Shuffle, "Shuffle", { actions.shuffle(tracks) }, size = 50.dp)
        GlassButton("Play", onClick = { actions.play(tracks, 0) })
        if (saved != null && onSave != null) GlassCircle(if (saved) Glyph.HeartFilled else Glyph.Heart, if (saved) "Saved" else "Save", onSave, size = 50.dp, lit = saved)
        else GlassCircle(Glyph.Plus, "Add to queue", { actions.playback.addToQueue(tracks) }, size = 50.dp)
        GlassCircle(if (allKept) Glyph.Check else Glyph.Download, if (allKept) "Kept on this phone" else "Keep on this phone", { if (!allKept) container.downloads.keep(tracks) }, size = 50.dp, lit = allKept)
    }
}

private val SideStyle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 18.sp)
private val RowStyle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 13.5.sp, lineHeight = 18.sp)
private val ByStyle = SpanStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 14.5.sp, color = Ink.Grey)

/** How many songs to a side: two sides for a short list, as an LP has; sides of twelve for a long one. */
private fun sideLength(count: Int) = if (count <= 24) ((count + 1) / 2).coerceAtLeast(1) else 12

private fun sideLetter(side: Int): String {
    val letters = ('A'..'Z').toList()
    return if (side < letters.size) letters[side].toString() else letters[side / letters.size - 1].toString() + letters[side % letters.size]
}

/**
 * The tracklist, as on a sleeve: a head (Side, Length, Tracklist), then each song with its side's
 * letter on the first of the side, its length, a mark (pause on the one that's playing) and
 * "Artist – Title". Touch to play from there; hold for the song's menu.
 */
fun LazyListScope.sleeveTracks(tracks: List<Track>, playing: MediaId?, isPlaying: Boolean, keyPrefix: String, onPlay: (Int) -> Unit) {
    if (tracks.isEmpty()) return
    val lengths = tracks.any { it.durationMs != null }
    item(key = "$keyPrefix:head") { SleeveHeader(lengths) }
    val side = sideLength(tracks.size)
    itemsIndexed(tracks, key = { i, t -> "$keyPrefix:$i:${t.id}" }) { i, t ->
        SleeveRow(
            letter = if (i % side == 0) sideLetter(i / side) else null,
            track = t,
            playing = t.id == playing,
            isPlaying = isPlaying,
            gapAbove = i % side == 0 && i > 0,
            lengths = lengths,
            onClick = { onPlay(i) },
        )
    }
}

/** The tracklist's head: Side, Length (when the source gives lengths), Tracklist. */
@Composable
private fun SleeveHeader(lengths: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.edge + 4.dp).padding(bottom = 4.dp)) {
        Txt("Side", Type.Label, upper = true, modifier = Modifier.width(40.dp))
        if (lengths) Txt("Length", Type.Label, color = Ink.Dim, upper = true, modifier = Modifier.width(78.dp))
        else Spacer(Modifier.width(22.dp))
        Txt("Tracklist", Type.Label, color = Ink.Dim, upper = true)
    }
}

/**
 * A tracklist as on a sleeve, drawn whole: for a list that sits among a page's other things (an
 * album's songs, an artist's best, a day of history). Touch to play from there; hold for the menu.
 */
@Composable
fun SleeveList(tracks: List<Track>, playContext: List<Track> = tracks, alone: Boolean = false, artists: Boolean = true) {
    if (tracks.isEmpty()) return
    val lengths = tracks.any { it.durationMs != null }
    val container = LocalContainer.current
    val actions = LocalActions.current
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val side = sideLength(tracks.size)
    Column(Modifier.fillMaxWidth()) {
        SleeveHeader(lengths)
        tracks.forEachIndexed { i, t ->
            SleeveRow(
                letter = if (i % side == 0) sideLetter(i / side) else null,
                track = t,
                playing = t.id == playback.current?.id,
                isPlaying = playback.isPlaying,
                gapAbove = i % side == 0 && i > 0,
                lengths = lengths,
                artists = artists,
                // Alone, a song brings its own radio: what follows it is what's like it, not its neighbours in the list.
                onClick = { if (alone) actions.playRadio(t) else actions.play(playContext, playContext.indexOf(t).coerceAtLeast(0)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SleeveRow(letter: String?, track: Track, playing: Boolean, isPlaying: Boolean, gapAbove: Boolean, lengths: Boolean, artists: Boolean = true, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    val actions = LocalActions.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (gapAbove) 18.dp else 0.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = FingertipLight,
                onLongClick = { haptics.strong(); actions.openMenu(track) },
                onClick = { haptics.select(); onClick() },
            )
            .padding(horizontal = Space.edge + 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(letter ?: "", SideStyle, modifier = Modifier.width(40.dp))
        if (lengths) Txt(track.durationMs?.let { clock(it) } ?: "", Type.Meta, color = Ink.Dim, maxLines = 1, modifier = Modifier.width(56.dp))
        Icon(if (playing && isPlaying) Glyph.Pause else Glyph.Play, size = 10.dp, color = if (playing) Ink.White else Ink.Grey)
        Spacer(Modifier.width(12.dp))
        // The song first, and who it's by after it, so it's their names the line runs out on and never
        // the song's. Each in its own voice, as everywhere: the name in the grotesk, who in the serif.
        val line = remember(track.title, track.artistLine, artists) {
            buildAnnotatedString {
                append(track.title.withoutEmoji())
                if (artists && track.artistLine.isNotBlank()) {
                    withStyle(ByStyle) {
                        append("   ")
                        append(track.artistLine.withoutEmoji())
                    }
                }
            }
        }
        BasicText(
            line,
            Modifier.weight(1f),
            style = RowStyle.copy(color = if (playing) Ink.White else Ink.White.copy(alpha = if (track.available) 0.88f else 0.35f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The label's credit at the foot of the sleeve: the cover small, the name, who made it, and its count and length. */
@Composable
fun SleeveLabel(artwork: Artwork, title: String, by: String?, tracks: List<Track>) {
    val total = tracks.sumOf { it.durationMs ?: 0L }
    val length = when {
        total <= 0 -> null
        total >= 3_600_000 -> "${total / 3_600_000} hr ${(total % 3_600_000) / 60_000} min"
        else -> "${total / 60_000} min"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.edge + 4.dp).padding(top = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        TreatedArtwork(artwork, ArtTreatment.Print, Modifier.size(64.dp).clip(Corners.Small), sizeHintPx = 200)
        Spacer(Modifier.width(16.dp))
        Column {
            Txt(title, SideStyle.copy(fontSize = 17.sp, lineHeight = 21.sp), maxLines = 2)
            if (!by.isNullOrBlank()) Txt(by, Type.Body, color = Ink.Grey, maxLines = 1)
            Txt(listOfNotNull("${tracks.size} songs", length).joinToString("  ·  "), Type.Label, color = Ink.Dim, upper = true, maxLines = 1)
        }
    }
}
