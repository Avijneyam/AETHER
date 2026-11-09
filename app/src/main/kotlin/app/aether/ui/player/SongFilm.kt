package app.aether.ui.player

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.theme.Motion
import app.aether.core.model.Track
import app.aether.ui.common.LocalContainer
import app.aether.ui.detail.HeroFilm
import kotlinx.coroutines.delay

/** Long enough for a cover's arrival, and for a song skipped straight past never to start a player. */
private const val BeginAfterMs = 1_200L

/** A film that hasn't shown a frame by now isn't going to be worth waiting for. */
private const val GiveUpMs = 9_000L

/** Below this the film would stutter, or take the music's bandwidth. */
private const val SlowestKbps = 3_000

private val Rising = Motion.fluid(0.7f, 1f, 0.002f)
private val Going = Motion.fluid(0.34f, 1f, 0.002f)

/** Dimmed a little where the words at the top stand on it. */
private val UnderTheHeading = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.46f), 0.2f to Color.Transparent)

private fun fastEnough(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
    return capabilities.linkDownstreamBandwidthKbps >= SlowestKbps
}

/** The film's lower edge, as circles round the dial's centre at [pivotY]: nothing within [gone] of it, all of it beyond [full]. */
class FilmFade(val pivotY: Dp, val gone: Dp, val full: Dp)

/** One song's film on Now Playing: how far up it is, which the print gives way to, and whether it's on its way out. */
@Stable
class FilmSlot(val track: Track) {
    val up = Animatable(0f, visibilityThreshold = 0.002f)
    var leaving by mutableStateOf(false)
}

/**
 * The cover's own short film, in colour, across the top of Now Playing for the songs whose album has
 * one, going to nothing along an arc that runs with the dial's, before it reaches the song's name.
 * The cover is always there first. The film
 * comes up from its first frame, once it's loaded, and the print gives way to it; on a slow connection
 * or the data saver it isn't asked for at all, and one that doesn't arrive in time is let go. Skipped,
 * it fades as the next cover arrives. Silent and looping: it never touches the music.
 */
@Composable
fun SongFilm(slot: FilmSlot, playing: Boolean, onGone: () -> Unit, fade: FilmFade, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val track = slot.track
    val wanted by container.settings.songVideo.collectAsStateWithLifecycle()
    val consent by container.settings.artistArt.collectAsStateWithLifecycle()
    val saver by container.settings.efficient.collectAsStateWithLifecycle()
    val on = wanted && consent && !saver && !slot.leaving && track.id.source != container.local.id
    var begun by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(BeginAfterMs)
        begun = fastEnough(context)
    }
    val url by produceState(container.albumArt.cachedSongFilm(track.id.value), on, begun) {
        if (!on || !begun || value != null) return@produceState
        val artist = track.artists.firstOrNull()?.name ?: return@produceState
        value = container.albumArt.findSongFilm(track.id.value, track.title, artist, track.album?.title)
    }
    var framed by remember { mutableStateOf(false) }
    var gaveUp by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        delay(GiveUpMs)
        if (!framed) gaveUp = true
    }
    LaunchedEffect(framed, on, gaveUp) {
        slot.up.animateTo(if (framed && on && !gaveUp) 1f else 0f, if (on) Rising else Going)
        if (slot.leaving) onGone()
    }
    val showing by remember { derivedStateOf { slot.up.value > 0.002f } }
    val film = url
    if (film != null && begun && !gaveUp && (on || showing)) {
        Box(
            modifier
                .clipToBounds()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = slot.up.value
                }
                .drawWithCache {
                    val outer = fade.full.toPx()
                    val start = fade.gone.toPx() / outer
                    val span = 1f - start
                    val rings = Brush.radialGradient(
                        0f to Color.Transparent,
                        start to Color.Transparent,
                        start + span * 0.25f to Color.Black.copy(alpha = 0.12f),
                        start + span * 0.5f to Color.Black.copy(alpha = 0.5f),
                        start + span * 0.75f to Color.Black.copy(alpha = 0.88f),
                        1f to Color.Black,
                        center = Offset(size.width / 2f, fade.pivotY.toPx()),
                        radius = outer,
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(UnderTheHeading)
                        drawRect(rings, blendMode = BlendMode.DstIn)
                    }
                },
        ) {
            HeroFilm(
                url = film,
                playing = playing && !slot.leaving,
                onFirstFrame = { framed = true },
                onVideoAspect = { aspect = it },
                mono = false,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // A TextureView stretches what it's given: this crops it to the space instead.
                    val here = size.width / size.height.coerceAtLeast(1f)
                    if (aspect > here) scaleX = aspect / here else if (aspect > 0f) scaleY = here / aspect
                },
            )
        }
    }
}
