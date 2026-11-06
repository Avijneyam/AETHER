package app.aether.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.Container
import app.aether.core.designsystem.boot.LiquidLensBoot
import app.aether.core.designsystem.component.Haptics
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.ui.common.LocalContainer
import app.aether.ui.onboarding.MastheadStyle
import app.aether.ui.onboarding.Onboarding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import app.aether.core.designsystem.glass.GlassQuality
import app.aether.core.designsystem.glass.LocalGlassQuality
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import app.aether.core.model.Artwork
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import app.aether.R
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.FolderCard
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.glass.GlassHost
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.FluidSpring
import android.os.Build
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp

private enum class Stage { Boot, Welcome, App }

/**
 * What the opening hands on, and where to: the corner capsule's place on screen (the boot film rolls
 * up into it) and the welcome's wordmark's (the film's wordmark rises into it), each with its own
 * opacity, held back while what's arriving is still on its way.
 */
@Stable
class Arrival {
    var corner by mutableStateOf(Rect.Zero)
    var cornerAlpha by mutableFloatStateOf(1f)

    var masthead by mutableStateOf(Rect.Zero)
    var mastheadAlpha by mutableFloatStateOf(1f)

    /**
     * How far Home's page has scrolled its masthead up, in pixels, or null when the masthead isn't
     * the page's first row (or Home isn't showing): the day's capsule rides with it, and is gone
     * when it is.
     */
    var homeScroll by mutableStateOf<(() -> Int?)?>(null)
}

val LocalArrival = staticCompositionLocalOf { Arrival() }

/**
 * The whole app: the boot film on a cold start, the welcome the first time, then the rooms. The
 * rooms sit underneath once the welcome is done, so Home is already loading while the film plays.
 * The film doesn't just fade: once you've been in, it rolls up into the corner capsule over the
 * rooms; the first time, its wordmark rises to become the welcome's.
 */
@Composable
fun AetherRoot(container: Container, playBoot: Boolean, systemReducesMotion: Boolean, previewWelcome: Boolean = false) {
    val view = LocalView.current
    val reducePref by container.settings.reduceMotion.collectAsStateWithLifecycle()
    val reduce = reducePref || systemReducesMotion
    val onboarded by container.settings.onboarded.collectAsStateWithLifecycle()
    val simpleGlass by container.settings.simpleGlass.collectAsStateWithLifecycle()
    val haptics = remember(view) { Haptics(view) { container.settings.haptics.value } }
    val scope = rememberCoroutineScope()
    val arrival = remember { Arrival().apply { if (playBoot) cornerAlpha = 0f } }
    // Previewing (debug builds), the welcome is shown as if for the first time and nothing is saved.
    val welcomed = container.settings.onboarded.value && !previewWelcome
    var stage by remember { mutableStateOf(if (playBoot) Stage.Boot else if (welcomed) Stage.App else Stage.Welcome) }
    var filmShown by remember { mutableStateOf(playBoot) }
    var rising by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val film = remember { Animatable(1f) }
    val roll = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val welcome = remember { Animatable(1f) }

    fun filmEnded() {
        if (finished) return
        finished = true
        scope.launch {
            if (welcomed) {
                stage = Stage.App
                if (reduce) {
                    arrival.cornerAlpha = 1f
                    film.animateTo(0f, tween(Motion.Medium))
                } else {
                    roll.animateTo(1f, tween(RollMs, easing = LinearEasing)) { arrival.cornerAlpha = ((value - 0.82f) / 0.18f).coerceIn(0f, 1f) }
                }
            } else {
                stage = Stage.Welcome
                if (reduce) {
                    film.animateTo(0f, tween(Motion.Medium))
                } else {
                    // The welcome's own wordmark waits; the film's sharpens into it, then rises.
                    arrival.mastheadAlpha = 0f
                    rising = true
                    launch { film.animateTo(0f, tween(420, delayMillis = 120)) }
                    delay(260)
                    rise.animateTo(1f, Motion.fluid(0.9f, 1f))
                    arrival.mastheadAlpha = 1f
                    rising = false
                }
            }
            filmShown = false
        }
    }

    CompositionLocalProvider(
        LocalContainer provides container,
        LocalHaptics provides haptics,
        LocalReduceMotion provides reduce,
        LocalGlassQuality provides if (simpleGlass) GlassQuality.Simple else GlassQuality.Liquid,
        LocalArrival provides arrival,
    ) {
        // No black of its own: the window is already black (the theme), and a second coat was a whole
        // screen painted again on every frame for nothing.
        Box(Modifier.fillMaxSize()) {
            if (onboarded) {
                val blurPx = with(LocalDensity.current) { 22.dp.toPx() }
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        // While the film becomes the pill in the middle of the screen, the rooms around
                        // it are out of focus; they come into focus as the pill lands in its corner.
                        val v = if (filmShown && !reduce) veil(roll.value) else 0f
                        renderEffect = if (v > 0.002f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurEffect(blurPx * v, blurPx * v, TileMode.Clamp) else null
                    },
                ) { Shell() }
            }
            if (stage == Stage.Welcome) {
                Onboarding(
                    onDone = {
                        if (!previewWelcome) container.settings.setOnboarded()
                        scope.launch {
                            launch { Animatable(arrival.cornerAlpha).animateTo(1f, tween(900, delayMillis = 300)) { arrival.cornerAlpha = value } }
                            welcome.animateTo(0f, tween(900, easing = Motion.Editorial))
                            stage = Stage.App
                        }
                    },
                    modifier = Modifier.graphicsLayer { alpha = welcome.value },
                )
            }
            // While the film plays, the things a first touch will need are drawn once out of sight: their
            // code and their GPU shaders are ready before the rooms are, so nothing hitches the first time.
            if (filmShown && stage != Stage.Welcome) Warmup()
            if (filmShown) {
                Morph({ roll.value }, corner = { arrival.corner }, modifier = Modifier.graphicsLayer { alpha = film.value }) {
                    LiquidLensBoot(onFinished = ::filmEnded)
                }
            }
            if (rising) RisingWordmark(rise, arrival.masthead)
        }
    }
}

/**
 * The film becoming the corner capsule, in two movements. First the screen draws in around the
 * wordmark, as the Dynamic Island takes something in, until the film is a capsule of black holding
 * AETHER with the rooms showing all round it. Then the capsule travels to the corner, narrowing to
 * the capsule's size and settling with a little give; on the way AETHER gives way to the cross of
 * light and the day, and the black to glass, until it is the capsule, which takes over.
 */
@Composable
private fun Morph(t: () -> Float, corner: () -> Rect, modifier: Modifier = Modifier, film: @Composable () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val path = remember(w, h, density) { MorphPath(w, h, density) }
        // The capsule's body, under the film: it shows as the film thins away.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val k = t()
                if (k <= Split) return@drawBehind
                val win = path.window(k, corner())
                val body = ((k - Split) / 0.2f).coerceIn(0f, 1f) * path.leaving(k)
                val shape = RoundRect(win, CornerRadius(win.height / 2f))
                val outline = Path().apply { addRoundRect(shape) }
                // Black as it leaves the middle; half way to the corner it greys toward the capsule's glass.
                val fill = lerp(Color.Black.copy(alpha = 0.9f), CapsuleGrey, greying(k))
                drawPath(outline, fill.copy(alpha = fill.alpha * body))
                drawPath(outline, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f * body), Color.White.copy(alpha = 0.06f * body)), startY = win.top, endY = win.bottom), style = Stroke(1.dp.toPx()))
            },
        )
        // The film, drawn in to the capsule and then carried with it.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = t()
                    if (k <= 0f) return@graphicsLayer
                    val win = path.window(k, corner())
                    shape = Window(win, path.radius(k, win))
                    clip = true
                    alpha = 1f - ((k - Split - 0.03f) / 0.22f).coerceIn(0f, 1f)
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val k = t()
                        if (k <= Split) return@graphicsLayer
                        val win = path.window(k, corner())
                        val s = win.height / path.capsule.height
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(path.capsule.center.x / size.width, path.capsule.center.y / size.height)
                        translationX = win.center.x - path.capsule.center.x
                        translationY = win.center.y - path.capsule.center.y
                    },
            ) { film() }
        }
        // The capsule's face arriving: drawn at the real capsule's size and carried to wherever the capsule is.
        val target = corner()
        if (target.width > 0f) {
            DayCapsule(
                Modifier
                    .offset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
                    .graphicsLayer {
                        val k = t()
                        if (k <= Split) { alpha = 0f; return@graphicsLayer }
                        val win = path.window(k, corner())
                        val s = win.height / target.height
                        scaleX = s
                        scaleY = s
                        translationX = win.center.x - target.center.x
                        translationY = win.center.y - target.center.y
                        alpha = ((k - Split - 0.08f) / 0.25f).coerceIn(0f, 1f) * path.leaving(k)
                    },
            )
        }
    }
}

/** Where the first movement ends and the second begins, as a fraction of the whole. */
private const val Split = 0.42f

/**
 * The morph's geometry. The capsule the screen draws in to sits round the film's wordmark: the
 * film's world is two units tall at least, widened to fit 0.82 either side of centre, and its
 * wordmark's ink fills 1.09 units of it.
 */
private class MorphPath(private val w: Float, private val h: Float, private val density: Density) {
    val capsule: Rect
    private val screenRadius = with(density) { 36.dp.toPx() }

    init {
        val half = max(1f, 0.82f / (w / h))
        val unit = h / (2f * half)
        val cx = w / 2f
        val cy = h / 2f * (1f - 0.01f / half)
        val cw = 1.09f * unit + with(density) { 56.dp.toPx() }
        val ch = with(density) { 76.dp.toPx() }
        capsule = Rect(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
    }

    fun window(t: Float, corner: Rect): Rect {
        if (t <= Split) return lerp(Rect(0f, 0f, w, h), capsule, Motion.Glide.transform((t / Split).coerceIn(0f, 1f)))
        return lerp(capsule, landing(corner, density), land(t))
    }

    fun radius(t: Float, win: Rect): Float =
        if (t <= Split) lerp(screenRadius, capsule.height / 2f, Motion.Glide.transform((t / Split).coerceIn(0f, 1f))) else win.height / 2f

    /** The body and face give way to the real capsule at the very end. */
    fun leaving(t: Float): Float = 1f - ((t - 0.92f) / 0.08f).coerceIn(0f, 1f)

}

/** How long the film's whole roll takes, and so its second movement. */
private const val RollMs = 1250

/**
 * The second movement's progress at [t]: a critically damped spring (Apple's, response 0.42 s),
 * so the pill comes to its corner and stops exactly there. It never passes the capsule and comes
 * back: the old ease did, and the pill rose above its place at the very end and snapped down.
 */
private fun land(t: Float): Float {
    val x = ((t - Split) / (1f - Split)).coerceIn(0f, 1f)
    if (x >= 1f) return 1f
    val seconds = x * (1f - Split) * RollMs / 1000f
    return (1f + Landing.solve(-1f, 0f, seconds).first).coerceIn(0f, 1f)
}

private val Landing = FluidSpring(0.42f, 1f)

/** How out of focus the rooms are: rising as the film draws in, clearing as the pill lands. */
private fun veil(t: Float): Float = when {
    t <= 0f -> 0f
    t <= Split -> Motion.Glide.transform((t / Split).coerceIn(0f, 1f))
    else -> 1f - land(t)
}

/** From black to the capsule's grey, beginning half way to the corner. */
private fun greying(t: Float): Float {
    val l = land(t)
    return ((l - 0.35f) / 0.5f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
}

/** The corner capsule's glass, as the pill arrives at it: its grey tint over the page. */
private val CapsuleGrey = Color(0xFF3A3A3A).copy(alpha = 0.62f)

/** Where the film lands: the capsule, or where it will be if it hasn't been laid out yet. */
private fun landing(corner: Rect, density: Density): Rect = with(density) {
    if (corner.width > 0f) corner else Rect(20.dp.toPx(), 52.dp.toPx(), 210.dp.toPx(), 96.dp.toPx())
}

private class Window(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * The film's wordmark, taken over in type at the size and place the film left it, rising into the
 * welcome's. Where the film draws it follows from the film's own stage: its world is two units tall
 * at least, widened to fit 0.82 either side of centre, and the wordmark's ink fills 1.09 units of it.
 */
@Composable
private fun RisingWordmark(rise: Animatable<Float, *>, target: Rect) {
    if (target.width <= 0f) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val half = max(1f, 0.82f / (w / h))
        val unit = h / (2f * half)
        val from = Offset(w / 2f, h / 2f * (1f - 0.01f / half))
        val startScale = 1.09f * unit * 1.04f / target.width
        Txt(
            "Aether",
            MastheadStyle,
            modifier = Modifier
                .offset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
                .graphicsLayer {
                    val e = rise.value
                    val s = lerp(startScale, 1f, e)
                    scaleX = s
                    scaleY = s
                    translationX = lerp(from.x, target.center.x, e) - target.center.x
                    translationY = lerp(from.y, target.center.y, e) - target.center.y
                },
        )
    }
}

/** A folder, a capsule of glass and a printed picture, drawn invisibly for a moment under the film. */
@Composable
private fun Warmup() {
    val context = LocalContext.current
    val art = remember { Artwork.single("android.resource://${context.packageName}/${R.drawable.art_statue}", 493, 740) }
    GlassHost(Modifier.size(160.dp).graphicsLayer { alpha = 0.004f }, content = {
        TreatedArtwork(art, ArtTreatment.Halftone(), Modifier.fillMaxSize(), sizeHintPx = 200)
    }, overlay = {
        FolderCard("", null, art, listOf(FolderFile("", art)), onOpen = {}, width = 120.dp, opened = true)
        Box(Modifier.size(120.dp, 44.dp).liquidGlass(Corners.Pill))
    })
}
