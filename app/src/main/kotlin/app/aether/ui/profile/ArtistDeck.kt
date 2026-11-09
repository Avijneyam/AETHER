package app.aether.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.Picture
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Artwork
import app.aether.core.model.Outcome
import app.aether.core.model.Artist
import app.aether.core.model.SearchResults
import app.aether.core.model.withoutEmoji
import app.aether.Container
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.LocalContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How an artist looks: a picture, and their own lettering where they have one. */
class Face(val id: app.aether.core.model.MediaId?, val picture: Artwork, val logo: String?, val logoAspect: Float)

/**
 * Finds artists' pictures. A play knows an artist's name and not always their page, so a name with
 * no page is looked up. A song's cover stands in for nobody: failing all else, the card has no picture.
 */
class Faces(private val container: Container) {
    private val known = HashMap<String, Face>()

    fun cached(a: ArtistStat): Face? = synchronized(known) { known[a.name.lowercase()] }

    suspend fun of(a: ArtistStat): Face {
        cached(a)?.let { return it }
        val face = try {
            look(a)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: Face(a.id, Artwork.None, null, 1f)
        if (!face.picture.isEmpty) synchronized(known) { known[a.name.lowercase()] = face }
        return face
    }

    private suspend fun look(a: ArtistStat): Face? {
        val primary = container.sources.primary
        val id = a.id ?: (primary.search(a.name, null) as? Outcome.Ok<SearchResults>)?.value?.items
            ?.filterIsInstance<Artist>()?.firstOrNull { it.title.equals(a.name, ignoreCase = true) }?.id
            ?: return null
        val source = container.sources.of(id) ?: return null
        val page = (source.artist(id) as? Outcome.Ok)?.value ?: return null
        val art = if (container.settings.artistArt.value) container.artistArt.find(id.value, page.artist.name, page.topTracks.map { it.title }) else null
        return Face(id, art?.picture ?: page.artist.artwork, art?.logo, art?.logoAspect ?: 1f)
    }
}

private const val Peek = 0.05f
private val Thrown = Motion.fluid<Float>(0.42f, 1f)
private val Back = Motion.fluid<Float>(0.46f, 0.72f)

/**
 * The listener's most played artists as a stack of cards, one to a person: a big picture, the name
 * across its foot, and what they've meant lately. A card thrown either way goes, and the one behind
 * it comes up; touched, it opens the artist.
 */
@Composable
fun ArtistDeck(artists: List<ArtistStat>, faces: Faces, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val actions = LocalActions.current
    var top by remember { mutableIntStateOf(0) }
    val x = remember { Animatable(0f) }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = Space.edge)) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val tracker = remember { VelocityTracker() }
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f)) {
            val shown = (2 downTo 0).filter { it < artists.size }
            shown.forEach { depth ->
                val index = (top + depth) % artists.size
                key(index) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val carried = (abs(x.value) / width).coerceIn(0f, 1f)
                                if (depth == 0) {
                                    translationX = x.value
                                    rotationZ = x.value / width * 9f
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1.2f)
                                } else {
                                    val place = depth - carried
                                    val s = 1f - Peek * place
                                    scaleX = s
                                    scaleY = s
                                    translationY = -place * 16.dp.toPx()
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                                }
                            }
                            .then(
                                if (depth == 0) Modifier
                                    .pointerInput(top) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { tracker.resetTracking() },
                                            onDragEnd = {
                                                val v = tracker.calculateVelocity().x
                                                val away = abs(x.value) > width * 0.3f || abs(v) > 1500f
                                                scope.launch {
                                                    if (away && artists.size > 1) {
                                                        val side = if (if (abs(v) > 1500f) v > 0f else x.value > 0f) 1 else -1
                                                        haptics.tick()
                                                        x.animateTo(side * width * 1.4f, Thrown, initialVelocity = v)
                                                        top = (top + 1) % artists.size
                                                        x.snapTo(0f)
                                                    } else x.animateTo(0f, Back, initialVelocity = v)
                                                }
                                            },
                                            onDragCancel = { scope.launch { x.animateTo(0f, Back) } },
                                        ) { change, dx ->
                                            change.consume()
                                            tracker.addPosition(change.uptimeMillis, change.position)
                                            scope.launch { x.snapTo(x.value + dx) }
                                        }
                                    }
                                    .pointerInput(top) {
                                        detectTapGestures {
                                            haptics.select()
                                            val a = artists[index]
                                            (faces.cached(a)?.id ?: a.id)?.let { actions.nav.open(Route.Artist(it)) }
                                        }
                                    }
                                else Modifier,
                            ),
                    ) { ArtistFace(artists[index], index, artists.size, faces) }
                }
            }
        }
    }
}

@Composable
private fun ArtistFace(a: ArtistStat, rank: Int, of: Int, faces: Faces) {
    val face by produceState(faces.cached(a), a.name) { value = faces.of(a) }
    Box(Modifier.fillMaxSize().clip(Corners.Large).background(Ink.Coal)) {
        val picture = face?.picture ?: Artwork.None
        if (!picture.isEmpty) TreatedArtwork(picture, ArtTreatment.Monochrome(contrast = 1.14f, grain = 0f), Modifier.fillMaxSize(), sizeHintPx = 1080)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.34f), 0.2f to Color.Transparent, 0.52f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.94f))))
        Strapline("Nº ${rank + 1} of $of", Modifier.align(Alignment.TopStart).padding(18.dp), color = Color.White)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            val logo = face?.logo
            if (logo != null) {
                Picture(
                    logo,
                    1000,
                    Modifier.widthIn(max = 220.dp).heightIn(max = 76.dp).aspectRatio(face?.logoAspect ?: 1f),
                    colorFilter = ColorFilter.tint(Color.White),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Txt(a.name.withoutEmoji(), Type.name(a.name, Type.Headline.copy(fontSize = Type.Headline.fontSize * 1.2f)), maxLines = 2, align = TextAlign.Start)
            }
            Spacer(Modifier.height(10.dp))
            Strapline("${a.plays} ${if (a.plays == 1) "play" else "plays"}  ·  ${listening(a.minutes.toLong())}", color = Color.White)
            Spacer(Modifier.height(6.dp))
            Txt("On repeat: ${a.topSong.title.withoutEmoji()}", Type.Aside, color = Color.White.copy(alpha = 0.82f), maxLines = 1)
        }
    }
}

/** "48 min", "6 hr". */
internal fun listening(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 600 -> "${minutes / 60} hr ${minutes % 60} min"
    else -> "${minutes / 60} hr"
}
