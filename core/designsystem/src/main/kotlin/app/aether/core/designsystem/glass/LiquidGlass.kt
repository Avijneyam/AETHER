package app.aether.core.designsystem.glass

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.glass.backdrop.Backdrop
import app.aether.core.designsystem.glass.backdrop.backdrops.LayerBackdrop
import app.aether.core.designsystem.glass.backdrop.backdrops.FrostedBackdrop
import androidx.compose.ui.graphics.rememberGraphicsLayer
import app.aether.core.designsystem.glass.backdrop.backdrops.layerBackdrop
import app.aether.core.designsystem.glass.backdrop.backdrops.rememberLayerBackdrop
import app.aether.core.designsystem.glass.backdrop.drawBackdrop
import app.aether.core.designsystem.glass.backdrop.effects.blur
import app.aether.core.designsystem.glass.backdrop.effects.lens
import app.aether.core.designsystem.glass.backdrop.effects.vibrancy
import app.aether.core.designsystem.glass.backdrop.highlight.Highlight
import app.aether.core.designsystem.glass.backdrop.highlight.HighlightElement
import app.aether.core.designsystem.glass.backdrop.internal.ShapeProvider
import app.aether.core.designsystem.glass.backdrop.shadow.Shadow
import app.aether.core.designsystem.theme.Ink
import com.kyant.shapes.RoundedRectangularShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The one capture of the content plane that every piece of glass samples. Glass is drawn only
 * above it, so glass never samples glass (except where one piece hands another its own glass, as
 * the tab bar does its selection lens: [liquidGlass]'s `export`).
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * The plane under the floating glass, frosted once and kept (see [FrostedBackdrop]): sheets sample
 * it instead of blurring what's behind them again on every frame they move.
 */
val LocalFrostedBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }

/** True when the listener asked for less motion: glass still answers, but without stretch or ripples. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * How much work glass does, the listener's choice in Settings. [Liquid] is BitChord's glass (after
 * Echo's Apple-matched values): little frost, a deep lens at the rim with colour fringing, a lit
 * rim, the screen's edges frosted live. [Simple] keeps a light frost on small floating controls,
 * draws panes (sheets, menus) solid with only glass's rim, and fades the screen's edges to black:
 * a fraction of the work. Both answer the finger the same way.
 */
enum class GlassQuality { Liquid, Simple }

val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.Simple }

/**
 * The fraction of a surface's resolution its backdrop is recorded and processed at (BitChord's
 * vendored addition to the backdrop library). A third is nine times fewer pixels through the
 * colour matrix, the blur and the lens; the frost hides the upscale. Pixel sizes handed to the
 * effects are scaled by the same factor.
 */
private const val LiquidScale = 0.33f
private const val SimpleScale = 0.25f

/**
 * [content] is the plane glass refracts; [overlay] is where glass floats. The plane is captured
 * with its [ground], so glass over words on black frosts the words instead of showing them sharp
 * through a tint.
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    ground: Color = Ink.Black,
    hideContent: () -> Boolean = { false },
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val frostLayer = rememberGraphicsLayer()
    val quality = LocalGlassQuality.current
    val density = LocalDensity.current
    val frosted = remember(backdrop, frostLayer, quality, density) {
        val r = with(density) { (if (quality == GlassQuality.Simple) GlassWeight.Pane.simpleBlurDp else GlassWeight.Pane.blurDp).dp.toPx() }
        FrostedBackdrop(backdrop, frostLayer, SimpleScale, r)
    }
    Box(modifier) {
        // [hideContent]: something opaque covers the whole plane, so it isn't drawn (Android would
        // otherwise draw it, every frame, under whatever hides it).
        Box(Modifier.matchParentSize().graphicsLayer { alpha = if (hideContent()) 0f else 1f }.layerBackdrop(backdrop).background(ground), content = content)
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalFrostedBackdrop provides frosted) {
            Box(Modifier.matchParentSize(), content = overlay)
        }
    }
}

/**
 * How thick and how clear a piece of glass is. There is one glass in AETHER, the tab bar's
 * (BitChord's: frost 8, a lens 24 by 24 with colour fringing, a #121212 surface at 40%, lit at its
 * rim), and every piece of it is made the same way: the mini player, the keys, the search field,
 * the sheets, the menus. The rim's refraction, its colour fringing and its light never change. A
 * pane only frosts a little deeper inside its rim, as Apple's regular material is to its clear
 * one: a sheet holds paragraphs, the bar three marks, and words over a busy cover must read.
 * Simple frosts them all alike, with no lens.
 */
enum class GlassWeight(
    val blurDp: Float,
    val lensHeightDp: Float,
    val lensAmountDp: Float,
    val surface: Float,
    val simpleBlurDp: Float,
    val simpleSurface: Float,
) {
    /** Floating controls: the tab bar, the mini player, the keys. The glass every other piece matches. */
    Control(blurDp = 8f, lensHeightDp = 24f, lensAmountDp = 24f, surface = 0.4f, simpleBlurDp = 18f, simpleSurface = 0.5f),

    /** Sheets and menus: the same rim and lens, a deeper frost and a shade more surface under words. */
    Pane(blurDp = 14f, lensHeightDp = 24f, lensAmountDp = 24f, surface = 0.55f, simpleBlurDp = 22f, simpleSurface = 0.62f),

    /** The seek pill and other thin bars: the same glass, its lens no deeper than they are. */
    Hairline(blurDp = 8f, lensHeightDp = 16f, lensAmountDp = 18f, surface = 0.4f, simpleBlurDp = 18f, simpleSurface = 0.5f),

    /**
     * Keys and pills, made as the tab bar's dragged selector is: a clear lens. Barely frosted (just
     * enough for a word on it to read), a deep band of refraction at the rim with its colour
     * fringing plain to see, the lit rim and the contact shadow.
     */
    Key(blurDp = 3f, lensHeightDp = 18f, lensAmountDp = 22f, surface = 0.3f, simpleBlurDp = 18f, simpleSurface = 0.5f),
}

/** Glass's surface colour: near-black, so the room stays dark under it. */
private val Surface = Color(0xFF121212)

/**
 * A finger on liquid. [press] swells the surface and flattens it like a pressed drop, centred on
 * [touch]; [stretch] pulls it toward a drag in proportion to the drag's speed; on release both spring
 * back, the surface overshooting a little as a drop does, and a [ripple] runs out from the touch.
 */
@Stable
class LiquidState internal constructor(private val scope: CoroutineScope) {
    internal val press = Animatable(0f)
    internal val stretch = Animatable(Offset.Zero, Offset.VectorConverter)
    internal val ripple = Animatable(1f)

    /** Where the finger is, as a fraction of the surface (0..1 both ways). */
    var touch by mutableStateOf(Offset(0.5f, 0.5f))
        internal set

    var pressed by mutableStateOf(false)
        internal set

    internal fun down(at: Offset) {
        touch = at
        pressed = true
        scope.launch { press.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 900f)) }
    }

    internal fun drag(velocity: Offset, reduce: Boolean) {
        if (reduce) return
        // Stretch grows with speed and saturates: a believable drop, never rubber.
        val speed = hypot(velocity.x, velocity.y)
        val k = min(1f, speed / 3200f) * 0.09f
        val target = if (speed > 1f) Offset(velocity.x / speed * k, velocity.y / speed * k) else Offset.Zero
        scope.launch { stretch.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 1400f)) }
    }

    internal fun up(reduce: Boolean) {
        pressed = false
        scope.launch { press.animateTo(0f, spring(dampingRatio = if (reduce) 1f else 0.42f, stiffness = 520f)) }
        scope.launch { stretch.animateTo(Offset.Zero, spring(dampingRatio = 0.48f, stiffness = 380f)) }
        if (!reduce) scope.launch {
            ripple.snapTo(0f)
            ripple.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 90f))
        }
    }
}

@Composable
fun rememberLiquidState(): LiquidState {
    val scope = rememberCoroutineScope()
    return remember { LiquidState(scope) }
}

/**
 * Feeds a finger to [state]: down, drag (with its velocity) and release. [onTap] fires for a touch
 * that didn't travel; a drag that left the surface is not a tap.
 */
fun Modifier.liquidTouch(state: LiquidState, reduceMotion: Boolean = false, onTap: (() -> Unit)? = null): Modifier =
    pointerInput(state, onTap) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = max(1f, size.width.toFloat())
            val h = max(1f, size.height.toFloat())
            state.down(Offset(down.position.x / w, down.position.y / h))
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var travelled = 0f
            var inside = true
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                travelled += change.positionChange().getDistance()
                tracker.addPosition(change.uptimeMillis, change.position)
                val p = change.position
                inside = p.x in 0f..w && p.y in 0f..h
                state.touch = Offset((p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))
                val v = tracker.calculateVelocity()
                state.drag(Offset(v.x, v.y), reduceMotion)
            }
            state.up(reduceMotion)
            if (inside && travelled < viewConfiguration.touchSlop) onTap?.invoke()
        }
    }

/**
 * The press and stretch, as a transform. However hard it's pulled, no edge goes more than [grow]
 * past where it rests: scaling about the finger moves the far edge most, so the room on each side
 * caps the scale, and the lean is held inside what's left. A bar the width of the screen stays on it.
 */
private fun liquidDeform(state: LiquidState?, growPx: Float): (GraphicsLayerScope.() -> Unit)? {
    if (state == null) return null
    return {
        val p = state.press.value
        val s = state.stretch.value
        val ox = state.touch.x
        val oy = state.touch.y
        val w = max(1f, size.width)
        val h = max(1f, size.height)
        // A pressed drop spreads and flattens; a dragged one leans and lengthens along the drag.
        val wantX = 1f + 0.045f * p + abs(s.x) - 0.5f * abs(s.y)
        val wantY = 1f + 0.03f * p + abs(s.y) - 0.5f * abs(s.x) - 0.02f * p
        val sx = min(wantX, 1f + growPx / (max(ox, 1f - ox) * w))
        val sy = min(wantY, 1f + growPx / (max(oy, 1f - oy) * h))
        scaleX = sx
        scaleY = sy
        transformOrigin = TransformOrigin(ox, oy)
        // Where the edges went, and how far the lean may carry them before they pass [grow].
        val left = ox * w * (1f - sx)
        val right = (1f - ox) * w * (sx - 1f)
        val top = oy * h * (1f - sy)
        val bottom = (1f - oy) * h * (sy - 1f)
        translationX = (s.x * w * 0.35f).coerceIn(-growPx - left, growPx - right)
        translationY = (s.y * h * 0.35f).coerceIn(-growPx - top, growPx - bottom)
    }
}

/**
 * Just glass's rim: the lit edge, brighter where light from the upper left would catch it. For a
 * surface that keeps its own fill (a card on the page, a solid pane); it costs a stroke and
 * captures nothing.
 */
fun Modifier.glassRim(shape: Shape, alpha: Float = 1f): Modifier =
    this.then(HighlightElement(ShapeProvider { shape }) { Highlight.Default.copy(alpha = alpha) })

/**
 * Liquid glass: what's behind, frosted, refracted at the rim and lit along its edge, deformed by
 * [state] when given. It samples [backdrop]: the content plane under the floating glass, or a
 * page's own layer (Discover's drifting covers under its search field). [export] records this
 * glass (the frosted backdrop and its surface, without rim or shadow) for a lens above it to
 * refract.
 *
 * Where there's nothing to sample (glass that is itself part of a page, where it can't sample the
 * page it's in), it is the same glass over the room's black: the same surface, the same light from
 * above, the same lit rim, the same answer to the finger. Before Android 12 it's that too.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape,
    weight: GlassWeight = GlassWeight.Control,
    state: LiquidState? = null,
    tint: Color = Surface,
    export: LayerBackdrop? = null,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
): Modifier {
    val quality = LocalGlassQuality.current
    val density = LocalDensity.current
    val growPx = with(density) { 6.dp.toPx() }
    val deform = liquidDeform(state, growPx)
    val simple = quality == GlassQuality.Simple
    val surface = tint.copy(alpha = if (simple) weight.simpleSurface else weight.surface)
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return this
            .then(if (deform != null) Modifier.graphicsLayer(deform) else Modifier)
            .then(GlassSurface(shape, surface, state, ripple = !simple))
    }
    // A sheet frosts deeply enough that a quarter of its resolution looks the same as a third, at
    // under two thirds of the work while it moves. Everything else as it was.
    val scale = if (simple || weight == GlassWeight.Pane) SimpleScale else LiquidScale
    // A sheet over its host's plane reads the plane's kept frost: no blur of its own each frame.
    val frosted = LocalFrostedBackdrop.current
    val useFrost = weight == GlassWeight.Pane && frosted != null && backdrop === LocalGlassBackdrop.current
    val frostPx = with(density) { (if (simple) weight.simpleBlurDp else weight.blurDp).dp.toPx() } * scale
    val lensHeightPx = with(density) { weight.lensHeightDp.dp.toPx() } * scale
    val lensAmountPx = with(density) { weight.lensAmountDp.dp.toPx() } * scale
    return this.drawBackdrop(
        backdrop = if (useFrost) frosted!! else backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            if (!useFrost) blur(frostPx)
            // The lens can only measure rounded rectangles; any other outline keeps its frost.
            if (!simple && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (shape is RoundedRectangularShape || shape is CornerBasedShape)) {
                val p = state?.press?.value ?: 0f
                // The band can be no deeper than half the glass; pressing deepens it, as a thicker drop would.
                val band = min(lensHeightPx * (1f + 0.25f * p), min(size.width, size.height) * scale / 2f)
                lens(band, lensAmountPx * (1f + 0.5f * p), depthEffect = true, chromaticAberration = true)
            }
        },
        // A sheet's rim is stroked straight onto it (below); every other piece keeps the library's
        // lit rim. A rim layer the size of a sheet, clear but for its edge, was a sheet's worth of
        // compositing on every frame it moved.
        highlight = if (weight == GlassWeight.Pane) null else ({
            val p = state?.press?.value ?: 0f
            Highlight.Default.copy(alpha = (0.85f + 0.15f * p).coerceAtMost(1f))
        }),
        // A sheet needs no shadow: the room dims behind it, and most of a shadow that size would lie
        // hidden under the sheet itself, filled for nothing on every frame.
        shadow = if (weight == GlassWeight.Pane) null else ({ Shadow.Default }),
        layerBlock = deform,
        exportedBackdrop = export,
        onDrawSurface = { if (weight == GlassWeight.Pane) drawPaneSurface(surface) else drawGlassSurface(surface, state, ripple = !simple) },
        onDrawFront = if (weight == GlassWeight.Pane) ({ drawLensRim(paneOutline(shape)) }) else null,
        backdropScale = scale,
    )
}

/**
 * The edge of the tab bar's dragged selector, as it shows over the room's black, for glass that has
 * nothing behind it to bend (it's part of a page): the band of light a lens gathers inside its rim,
 * a hairline of dispersion along the rim (cool where the light strikes, warm where it leaves), and
 * the rim itself, lit from the upper left with a faint bounce at the lower right. Plain strokes
 * inside [outline]: a page can hold dozens, so nothing here takes a layer of its own.
 */
fun DrawScope.drawLensRim(outline: Path, strength: Float = 1f) {
    val s = strength.coerceIn(0f, 1.2f)
    if (s <= 0.004f) return
    val w = size.width
    val h = size.height
    val diagonal = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.16f * s),
        0.5f to Color.White.copy(alpha = 0.03f * s),
        1f to Color.White.copy(alpha = 0.09f * s),
        start = Offset.Zero,
        end = Offset(w, h),
    )
    val px = 1.dp.toPx()
    clipPath(outline) {
        // The refraction band: light gathered just inside the rim.
        drawPath(outline, diagonal, style = Stroke(7f * px))
        // Dispersion: the rim split a hair's breadth into its cool and warm edges.
        translate(-0.7f * px, -0.7f * px) { drawPath(outline, Color(0xFFA9DCFF).copy(alpha = 0.22f * s), style = Stroke(1.4f * px)) }
        translate(0.7f * px, 0.7f * px) { drawPath(outline, Color(0xFFFFC9A3).copy(alpha = 0.16f * s), style = Stroke(1.4f * px)) }
    }
    drawPath(
        outline,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.62f * s),
            0.45f to Color.White.copy(alpha = 0.12f * s),
            1f to Color.White.copy(alpha = 0.3f * s),
            start = Offset.Zero,
            end = Offset(w, h),
        ),
        style = Stroke(0.8f * px),
    )
}

/**
 * A sheet's surface: its tint and the light falling from above it as one gradient, painted once (the
 * same colours the two separate coats made, a sheet's worth of paint less on every frame it moves).
 */
private fun DrawScope.drawPaneSurface(surface: Color) {
    val lit = Color.White.copy(alpha = 0.07f).compositeOver(surface)
    drawRect(Brush.verticalGradient(0f to lit, 0.6f to surface, 1f to surface))
}

/** A sheet's outline as a path, kept per size. */
private val paneOutlines = HashMap<Pair<Shape, Size>, Path>()

private fun DrawScope.paneOutline(shape: Shape): Path = paneOutlines.getOrPut(shape to size) {
    if (paneOutlines.size > 24) paneOutlines.clear()
    Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@paneOutline)) }
}

/** Glass's surface over what it frosts: its tint, light falling from above, and the finger's light. */
private fun DrawScope.drawGlassSurface(surface: Color, state: LiquidState?, ripple: Boolean) {
    val p = state?.press?.value ?: 0f
    drawRect(surface.copy(alpha = (surface.alpha + 0.06f * p).coerceAtMost(1f)))
    // Light falls on the glass from above and fades down it.
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), endY = size.height * 0.6f))
    if (state != null) {
        val c = Offset(state.touch.x * size.width, state.touch.y * size.height)
        // The light gathers where the finger is, and a ring of it runs out on release.
        if (p > 0.01f) {
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f * p), Color.Transparent), center = c, radius = max(size.width, size.height) * 0.55f))
        }
        val r = state.ripple.value
        if (r < 1f && ripple) {
            val reach = max(size.width, size.height) * (0.15f + 0.9f * r)
            drawCircle(Color.White.copy(alpha = 0.10f * (1f - r)), radius = reach, center = c, style = Stroke(width = 1.5.dp.toPx() * (1f - r) + 0.5f))
        }
    }
}

/** The surface alone, for glass with nothing to sample: drawn inside the glass's outline, behind what the glass holds. */
private class GlassSurface(val shape: Shape, val surface: Color, val state: LiquidState?, val ripple: Boolean) : ModifierNodeElement<GlassSurfaceNode>() {
    override fun create() = GlassSurfaceNode(shape, surface, state, ripple)
    override fun update(node: GlassSurfaceNode) {
        node.shape = shape; node.surface = surface; node.state = state; node.ripple = ripple
        node.path = null
        node.invalidateDraw()
    }
    override fun equals(other: Any?) = other is GlassSurface && other.shape == shape && other.surface == surface && other.state === state && other.ripple == ripple
    override fun hashCode() = ((shape.hashCode() * 31 + surface.hashCode()) * 31 + (state?.hashCode() ?: 0)) * 31 + ripple.hashCode()
}

private class GlassSurfaceNode(var shape: Shape, var surface: Color, var state: LiquidState?, var ripple: Boolean) : Modifier.Node(), DrawModifierNode {
    var path: Path? = null
    private var pathSize = Size.Unspecified

    override fun ContentDrawScope.draw() {
        val outline = path?.takeIf { pathSize == size } ?: Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@draw)) }.also { path = it; pathSize = size }
        clipPath(outline) { drawGlassSurface(surface, state, ripple) }
        drawContent()
        drawLensRim(outline, (0.85f + 0.15f * (state?.press?.value ?: 0f)).coerceAtMost(1f))
    }
}
