package app.aether.core.designsystem.theme

import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Apple's fluid physics (WWDC18, "Designing Fluid Interfaces"), as a closed-form damped harmonic
 * oscillator. Nothing here is a tween: a spring is named by its [response] (the seconds one
 * undamped swing takes) and its [damping] (the fraction of critical: 1 settles without overshoot,
 * less gives the elastic give). Positions come from the exact solution at elapsed time, so the
 * motion is identical at 60, 90 and 120 Hz, and interrupting it keeps the velocity it had.
 *
 * Compose's own `spring()` is the same oscillator ([Motion.fluid] converts to it); this solver is
 * for the frame loops that animate many things at once without an Animatable each: the lyrics'
 * camera, its stars, anything drawn in a Canvas.
 */
class FluidSpring(val response: Float, val damping: Float) {
    /** Natural angular frequency, mass 1: ωn = 2π / response. */
    val omega: Float = (2.0 * PI / response).toFloat()
    val stiffness: Float get() = omega * omega
    val friction: Float get() = 2f * damping * omega

    /**
     * Where a spring released [x0] from its target with velocity [v0] is after [t] seconds, as
     * (offset from the target, velocity). Units are whatever [x0] is in, per second for velocity.
     */
    fun solve(x0: Float, v0: Float, t: Float): Pair<Float, Float> {
        val w = omega
        val z = damping
        return when {
            abs(z - 1f) < 1e-5f -> {
                // Critically damped.
                val a = x0
                val b = v0 + w * x0
                val e = exp(-w * t)
                (a + b * t) * e to (b - w * (a + b * t)) * e
            }
            z < 1f -> {
                // Underdamped: the elastic give.
                val wd = w * sqrt(1f - z * z)
                val a = x0
                val b = (v0 + z * w * x0) / wd
                val e = exp(-z * w * t)
                val c = cos(wd * t)
                val s = sin(wd * t)
                e * (a * c + b * s) to e * ((b * wd - a * z * w) * c - (a * wd + b * z * w) * s)
            }
            else -> {
                // Overdamped.
                val g = sqrt(z * z - 1f)
                val r1 = -w * (z - g)
                val r2 = -w * (z + g)
                val c2 = (v0 - r1 * x0) / (r2 - r1)
                val c1 = x0 - c2
                val e1 = exp(r1 * t)
                val e2 = exp(r2 * t)
                c1 * e1 + c2 * e2 to c1 * r1 * e1 + c2 * r2 * e2
            }
        }
    }

    /** A value springing from [from] (moving at [velocity]) to [to], [t] seconds in. */
    fun value(from: Float, to: Float, t: Float, velocity: Float = 0f): Float = to + solve(from - to, velocity, t).first

    companion object {
        /** Quick, with a cushion: a selection landing, a key coming back. */
        val Snappy = FluidSpring(0.38f, 0.82f)

        /** Pages, sheets, anything that travels. No bounce. */
        val Default = FluidSpring(0.5f, 1f)

        /** The Dynamic Island's give. */
        val Bouncy = FluidSpring(0.52f, 0.72f)

        /** Slow and elastic: a camera drifting across the sky and settling past its mark. */
        val Drift = FluidSpring(1.15f, 0.78f)

        /** Momentum's decay for snapping (paging, detents) and for free scrolling. */
        const val SnapDeceleration = 0.990f
        const val ScrollDeceleration = 0.998f

        /** Where a flick at [velocity] (per second) would carry something from [position], slowing at [rate] per millisecond. */
        fun project(position: Float, velocity: Float, rate: Float = SnapDeceleration): Float = position + velocity / 1000f * rate / (1f - rate)

        /** Apple's rubber band: [overshoot] past an edge, along a [dimension], with resistance [c]. */
        fun rubberBand(overshoot: Float, dimension: Float, c: Float = 0.55f): Float {
            val x = abs(overshoot)
            return Math.signum(overshoot) * (x * dimension * c) / (dimension + c * x)
        }

        /** Rest: within half a pixel and moving under five pixels a second. */
        fun atRest(offset: Float, velocity: Float): Boolean = abs(offset) < 0.5f && abs(velocity) < 5f
    }
}

/**
 * A value in a frame loop that follows a target on a [spring]. Changing the target mid-flight
 * starts a new spring from where it is, at the speed it's going: never a jump, never a stop.
 * [step] advances it by real elapsed time, so it runs the same at any refresh rate. [unit] is how
 * many of its units make a pixel (1 for pixels; a 0..1 value spanning 300 px is 1/300), so it
 * comes to rest at Apple's half pixel whatever it measures.
 */
class FluidValue(initial: Float, private val spring: FluidSpring, private val unit: Float = 1f) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var from = initial
    private var startVelocity = 0f
    private var elapsed = 0f

    fun retarget(to: Float) {
        if (to == target) return
        from = value
        startVelocity = velocity
        target = to
        elapsed = 0f
    }

    fun snap(to: Float) {
        value = to; target = to; from = to; velocity = 0f; startVelocity = 0f; elapsed = 0f
    }

    /** Pushes it, as a flick would: the spring carries on from here at [v] per second. */
    fun kick(v: Float) {
        from = value
        startVelocity = v
        elapsed = 0f
    }

    fun step(dt: Float): Float {
        elapsed += dt
        val (offset, v) = spring.solve(from - target, startVelocity, elapsed)
        if (FluidSpring.atRest(offset / unit, v / unit)) {
            value = target; velocity = 0f
        } else {
            value = target + offset; velocity = v
        }
        return value
    }

    val resting: Boolean get() = value == target && velocity == 0f
}

/**
 * A value carried on a fluid spring that can't skip. An Animatable runs on the clock: after one long
 * frame it jumps to where it should be by then, and the long frames of something opening for the first
 * time could lose its whole movement (a folder that was simply there at the head of its page). This
 * runs on frames: no more than a thirtieth of a second of its spring passes in any one of them, so a
 * long frame only holds the motion for a moment, and it carries on from where it was. Interrupted, it
 * carries on from where it is, at the speed it had.
 */
@Stable
class Glide(initial: Float, private val rest: Float = 0.0005f) {
    var value by mutableFloatStateOf(initial)
        private set
    var velocity = 0f
        private set
    var isRunning by mutableStateOf(false)
        private set
    private val mutex = MutatorMutex()

    suspend fun to(target: Float, spring: FluidSpring) = mutex.mutate {
        val from = value
        val startVelocity = velocity
        if (from == target && startVelocity == 0f) return@mutate
        isRunning = true
        try {
            var elapsed = 0f
            var last = -1L
            while (true) {
                val now = withFrameNanos { it }
                // The first frame moves too: the answer to a touch shouldn't wait a frame for its clock.
                elapsed += if (last < 0L) FirstStep else ((now - last) / 1e9f).coerceAtMost(LongestStep)
                last = now
                val (offset, v) = spring.solve(from - target, startVelocity, elapsed)
                if (abs(offset) < rest && abs(v) < rest * 8f) {
                    value = target
                    velocity = 0f
                    break
                }
                value = target + offset
                velocity = v
            }
        } finally {
            isRunning = false
        }
    }

    suspend fun snap(to: Float) = mutex.mutate {
        value = to
        velocity = 0f
    }

    private companion object {
        const val FirstStep = 1f / 90f
        const val LongestStep = 1f / 30f
    }
}

/** [FluidValue] on both axes: a camera, a point. */
class FluidPoint(initial: Offset, spring: FluidSpring) {
    private val x = FluidValue(initial.x, spring)
    private val y = FluidValue(initial.y, spring)
    fun kick(v: Offset) { x.kick(v.x); y.kick(v.y) }
    val value: Offset get() = Offset(x.value, y.value)
    val velocity: Offset get() = Offset(x.velocity, y.velocity)
    fun retarget(to: Offset) { x.retarget(to.x); y.retarget(to.y) }
    fun snap(to: Offset) { x.snap(to.x); y.snap(to.y) }
    fun step(dt: Float): Offset = Offset(x.step(dt), y.step(dt))
}
