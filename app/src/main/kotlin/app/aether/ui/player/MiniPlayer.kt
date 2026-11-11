package app.aether.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.glass.liquidTouch
import app.aether.core.designsystem.glass.rememberLiquidState
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.PlaybackState
import app.aether.core.model.PlaybackStatus
import app.aether.ui.LocalActions
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The mini player's own shape: a rounded slab, not a capsule, so the song's progress can fill it. */
private val Slab = Corners.of(15.dp)

/**
 * The song that's playing, wherever you are: a slab of the tab bar's glass above the rooms. Tap or
 * lift it to open Now Playing (its keys only do what they say). How far the song has got fills the
 * slab with light from its left edge.
 */
@Composable
fun MiniPlayer(state: PlaybackState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val track = state.current ?: return
    val actions = LocalActions.current
    val haptics = LocalHaptics.current
    val liquid = rememberLiquidState()
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .semantics { contentDescription = "Now playing: ${track.title}, ${track.artistLine}. Open" }
            .liquidGlass(Slab, GlassWeight.Control, liquid)
            .liquidTouch(liquid, LocalReduceMotion.current, childrenFirst = true) { onOpen() }
            .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -12) onOpen() } },
    ) {
        // The position is read while drawing: a tick redraws this fill and nothing else.
        val position = actions.playback.position.collectAsStateWithLifecycle()
        val duration = state.durationMs
        Canvas(Modifier.matchParentSize().clip(Slab)) {
            val fraction = if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
            drawRect(Ink.White.copy(alpha = 0.16f), size = Size(size.width * fraction, size.height))
        }
        Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 3.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            TreatedArtwork(track.artwork, ArtTreatment.Original, Modifier.size(36.dp).clip(Corners.of(8.dp)), sizeHintPx = 144)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(track.title, Type.TitleSmall, maxLines = 1)
                Txt(if (state.status == PlaybackStatus.ERROR) state.error ?: "" else track.artistLine, Type.Meta, color = Ink.Grey, maxLines = 1)
            }
            IconAction(Glyph.Previous, "Previous", { haptics.strong(); actions.playback.previous() }, Modifier.size(44.dp), size = 18.dp)
            IconAction(
                if (state.isPlaying || state.status == PlaybackStatus.BUFFERING || state.status == PlaybackStatus.LOADING) Glyph.Pause else Glyph.Play,
                if (state.isPlaying) "Pause" else "Play",
                { haptics.strong(); actions.playback.toggle() },
                Modifier.size(44.dp),
                size = 20.dp,
            )
            IconAction(Glyph.Next, "Next", { haptics.strong(); actions.playback.next() }, Modifier.size(44.dp), size = 18.dp)
        }
    }
}
