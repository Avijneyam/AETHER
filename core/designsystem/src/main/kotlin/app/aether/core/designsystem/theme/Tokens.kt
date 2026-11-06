package app.aether.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

/**
 * Black is the room; white is precious. Most type is grey, and only what matters most is full
 * white. Artwork brings the only colour.
 */
object Ink {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)

    /** Secondary type: artists, details. */
    val Grey = Color(0xFF9A9A9A)

    /** Tertiary type: timestamps, counts, straplines. */
    val Dim = Color(0xFF5E5E5E)

    /** Faint: placeholders, disabled. */
    val Faint = Color(0xFF3A3A3A)

    /** Raised surfaces: a sheet, a skeleton. */
    val Charcoal = Color(0xFF0D0D0D)
    val Coal = Color(0xFF161616)

    /** Hairlines and edges: white at a whisper. */
    val Hairline = Color(0x1AFFFFFF)
    val Edge = Color(0x33FFFFFF)

    /** Glass tint over what it floats on. */
    val GlassTint = Color(0x52000000)

    /** The one colour AETHER owns: the red of the dial's hand, and nothing else. */
    val Signal = Color(0xFFFF3B30)
}

/** Spacing on a 4 dp grid. */
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val xxxl = 72.dp

    /** The one distance between the screen's edge and anything on it. Everywhere. */
    val edge = 20.dp
    val gutter = edge
}

/** Motion: slow in, gentle out; springs that settle without a bounce you'd notice. */
object Motion {
    val Editorial = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
    val Glide = CubicBezierEasing(0.33f, 0f, 0.1f, 1f)
    val Sink = CubicBezierEasing(0.55f, 0f, 0.75f, 0.35f)

    const val Short = 220
    const val Medium = 420
    const val Long = 760

    /** Glass under a finger: soft, quick to answer, settles in under half a second. */
    fun <T> liquid() = spring<T>(dampingRatio = 0.62f, stiffness = 420f)
    fun <T> settle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /*
     * Fluid motion as Apple describes it ("Designing Fluid Interfaces", WWDC18). A spring is named by
     * its response (the seconds one undamped swing would take) and its damping (the fraction of
     * critical: 1 settles without a bounce, less overshoots). Compose's spring is the same
     * closed-form oscillator, so this only converts: stiffness = (2 pi / response)^2, mass 1. It
     * keeps its velocity when interrupted, and runs the same at 60, 90 and 120 Hz.
     */
    fun <T> fluid(response: Float, damping: Float, threshold: T? = null) =
        spring(dampingRatio = damping, stiffness = (2f * PI.toFloat() / response).let { it * it }, visibilityThreshold = threshold)

    /** Quick, with a cushion: a selection landing, a key coming back. */
    fun <T> snappy(threshold: T? = null) = fluid(0.38f, 0.82f, threshold)

    /** The default: pages, sheets, anything that travels. No bounce. */
    fun <T> fluid(threshold: T? = null) = fluid(0.5f, 1f, threshold)

    /** Alive: the Dynamic Island's give. */
    fun <T> bouncy(threshold: T? = null) = fluid(0.52f, 0.72f, threshold)

    /**
     * Where a flick would carry something if it were let go and slowed as iOS slows a scroll: from
     * [position], at [velocity] (per second), losing [rate] of its speed each millisecond. The
     * resting place nearest to this is where it should land.
     */
    fun project(position: Float, velocity: Float, rate: Float = 0.99f): Float = position + velocity / 1000f * rate / (1f - rate)

    /**
     * Resistance past an edge, as iOS gives it: the further past, the less each extra pixel moves it
     * ([overshoot] dragged past, along a [dimension], with Apple's coefficient [c]).
     */
    fun rubberBand(overshoot: Float, dimension: Float, c: Float = 0.55f): Float {
        val x = abs(overshoot)
        return sign(overshoot) * (x * dimension * c) / (dimension + c * x)
    }
}
