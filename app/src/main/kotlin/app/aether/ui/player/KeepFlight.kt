package app.aether.ui.player

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.Picture
import app.aether.core.designsystem.art.Pictures
import app.aether.core.designsystem.art.PullRipple
import app.aether.core.designsystem.component.Haptics
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Glide
import app.aether.core.designsystem.theme.Ink
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import app.aether.player.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val FlightPx = 1080

/** How long the song waits at the phone's edge with nothing coming down before it's left to finish in the background. */
private const val StalledMs = 40_000L

private val LiftSpring = FluidSpring(0.62f, 0.84f)
private val SinkSpring = FluidSpring(0.3f, 1f)
private val DrawIn = FluidSpring(0.2f, 1f)
private val LetGo = FluidSpring(0.55f, 0.62f)
private val Words = FluidSpring(0.4f, 1f)

private val DiscSize = 56.dp

/** How far below the screen's foot the middle of the waiting disc is held, so most of it is in the phone already. */
private val DiscAbove = 8.dp

/** How much further into the phone the disc goes as the song comes down. */
private val DiscSinks = 10.dp

/** What's left of a song on its way in: it goes under the screen's foot. */
private val DiscGone = 48.dp

/**
 * A song going into the phone. Asked to keep it, the cover lifts off its place with a small ripple and
 * is carried down to the foot of the screen, where it waits half inside the phone, a thin ring saying
 * how much of it has come down, going a little further in as it does. Only when it's really there
 * does it go in: the screen is drawn down after it, a bright mouth opens at the foot, and a wave runs
 * up from it through everything on the screen, as AirDrop's does. If it fails, it says so instead.
 */
@Stable
class KeepFlight(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloads: Downloads,
    private val haptics: Haptics,
) {
    val ripple = PullRipple()

    /** Where Now Playing's cover stands, for the song to lift off. */
    var cover: LayoutCoordinates? = null

    internal var screen = Size.Zero
    internal var density = 1f

    var track by mutableStateOf<Track?>(null)
        private set
    internal var from by mutableStateOf(Rect.Zero)
        private set
    internal var word by mutableStateOf<String?>(null)
        private set
    internal var progress by mutableFloatStateOf(0f)
        private set

    /** 0 at the cover, 1 waiting at the foot; a little past 1 as it lands. */
    internal val flying = Glide(0f, rest = 0.0008f)

    /** 0 waiting at the foot, 1 gone into the phone. */
    internal val sinking = Glide(0f)

    /** The words at the foot and the dark behind them. */
    internal val saying = Glide(0f)

    private var underWay = false

    /** Where a piece of the screen is, if it's still there. */
    fun boundsOf(place: LayoutCoordinates?): Rect? = runCatching { place?.takeIf { it.isAttached }?.boundsInRoot() }.getOrNull()

    /** Sends [song] down from [start] (or from the foot of the screen, with nowhere to lift off). */
    fun fly(song: Track, start: Rect?, reduce: Boolean) {
        if (underWay) return
        underWay = true
        scope.launch {
            try {
                if (!reduce) lift(song, start)
                when (outcome(song.id)) {
                    Result.Kept -> if (reduce) say("Kept on this phone") else goIn()
                    Result.Failed -> say("Couldn't keep this song")
                    Result.Waiting -> say("Still keeping this song")
                    Result.Stopped -> Unit
                }
            } finally {
                track = null
                underWay = false
            }
        }
    }

    private suspend fun lift(song: Track, start: Rect?) {
        Pictures.fetch(context, song.artwork.url(FlightPx), FlightPx)
        flying.snap(0f)
        sinking.snap(0f)
        progress = 0f
        val disc = DiscSize.value * density
        from = start ?: Rect(Offset((screen.width - disc) / 2f, screen.height - disc), Size(disc, disc))
        track = song
        // What rises from where the cover stood.
        scope.launch { ripple.wave(from.center, 13f * density, 17f, 6f, screen.height * 1.5f, 0.14f, 0.8f) }
        flying.to(1f, LiftSpring)
    }

    private enum class Result { Kept, Failed, Stopped, Waiting }

    /** Waits for the phone to really have the song, for as long as it's still coming. */
    private suspend fun outcome(id: MediaId): Result {
        var seen = false
        while (true) {
            val before = progress
            // What changed: it's finished, it's gone, or some more of it has come down. Nothing for a long while: it's stalled.
            withTimeoutOrNull(StalledMs) {
                downloads.entries.map { it[id] }.first { e -> settled(e, seen) || (e != null && (!seen || e.progress != before)) }
            } ?: return Result.Waiting
            val e = downloads.entries.value[id]
            if (e != null) {
                seen = true
                progress = e.progress
            }
            if (settled(e, seen)) return when (e?.status) {
                Downloads.Status.Kept -> Result.Kept
                Downloads.Status.Failed -> Result.Failed
                else -> Result.Stopped
            }
        }
    }

    private fun settled(e: Downloads.Entry?, seen: Boolean) =
        (e == null && seen) || e?.status == Downloads.Status.Kept || e?.status == Downloads.Status.Failed

    private suspend fun goIn() = coroutineScope {
        val mouth = Offset(screen.width / 2f, screen.height)
        ripple.aim(mouth)
        launch { sinking.to(1f, SinkSpring) }
        delay(110)
        haptics.strong()
        // The screen goes after it, and springs back.
        launch {
            ripple.pull.to(1f, DrawIn)
            ripple.pull.to(0f, LetGo)
        }
        launch {
            ripple.glow.to(1f, DrawIn)
            ripple.glow.to(0f, FluidSpring(0.9f, 1f))
        }
        launch { ripple.wave(mouth, 30f * density, 14f, 2.9f, screen.height * 1.05f, 0.6f, 1.7f) }
        launch { say("Kept on this phone") }
    }

    /** A few words at the foot of the screen. */
    fun note(words: String) {
        scope.launch { say(words) }
    }

    private suspend fun say(words: String) {
        word = words
        saying.to(1f, Words)
        delay(1500)
        saying.to(0f, Words)
        word = null
    }
}

/** The song in the air and the words at the foot, over everything. Takes no touches. */
@Composable
fun KeepOverlay(flight: KeepFlight) {
    val pxPerDp = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        SideEffect {
            flight.screen = Size(width, height)
            flight.density = pxPerDp
        }
        val disc = DiscSize.value * pxPerDp
        val above = DiscAbove.value * pxPerDp
        val sinks = DiscSinks.value * pxPerDp
        val gone = DiscGone.value * pxPerDp
        // How far down the screen the middle of the disc is, once it has come down.
        fun restY() = height - above + flight.progress * sinks + flight.sinking.value * gone

        flight.track?.let { song ->
            val from = flight.from
            val widthDp = with(LocalDensity.current) { from.width.toDp() }
            val heightDp = with(LocalDensity.current) { from.height.toDp() }
            Box(
                Modifier
                    .size(widthDp, heightDp)
                    .graphicsLayer {
                        val f = flight.flying.value
                        val toX = width / 2f
                        scaleX = 1f + (disc / from.width - 1f) * f
                        scaleY = 1f + (disc / from.height - 1f) * f
                        translationX = from.left + (toX - from.width / 2f - from.left) * f
                        translationY = from.top + (restY() - from.height / 2f - from.top) * f
                        shape = RoundedCornerShape(percent = (6 + 44 * f.coerceIn(0f, 1f)).toInt())
                        clip = true
                    },
            ) {
                Picture(song.artwork.url(FlightPx), FlightPx, Modifier.fillMaxSize())
            }
            Canvas(
                Modifier
                    .size(DiscSize + 8.dp)
                    .graphicsLayer {
                        val settled = ((flight.flying.value - 0.92f) / 0.08f).coerceIn(0f, 1f)
                        alpha = settled * (1f - flight.sinking.value)
                        translationX = width / 2f - size.width / 2f
                        translationY = restY() - size.height / 2f
                    },
            ) {
                val line = 1.5.dp.toPx()
                drawCircle(Ink.White.copy(alpha = 0.18f), style = Stroke(line))
                drawArc(Ink.White, -90f, 360f * flight.progress, useCenter = false, style = Stroke(line))
            }
        }
        flight.word?.let { Words(it, flight) }
    }
}

@Composable
private fun BoxScope.Words(words: String, flight: KeepFlight) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(190.dp)
            .graphicsLayer { alpha = flight.saying.value }
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.72f), 1f to Color.Black)),
    ) {
        Strapline(
            words,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 26.dp)
                .graphicsLayer { translationY = (1f - flight.saying.value) * 12f * density },
            color = Color.White,
        )
    }
}
