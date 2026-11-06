package app.aether.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Something that opens by being dragged and closes the same way: a sheet, Now Playing, the lyrics.
 * [value] is 0 closed, 1 open.
 *
 * Under a finger it follows exactly, frame for frame: the finger's place is held in plain state, so
 * nothing can arrive late and undo it (the lyrics' "stuck" pane was a late step cancelling the
 * spring that should have closed it). Let go, and it lands where the flick would carry it (Apple's
 * projection), open or closed, keeping the finger's speed. Caught in flight, it's held from exactly
 * where it is. A sheet pulled past open stretches with Apple's rubber band.
 */
@Stable
class DragState internal constructor(private val scope: CoroutineScope) {
    private val anim = Animatable(0f)
    private var held by mutableFloatStateOf(0f)
    private var raw = 0f
    private var settling: Job? = null

    /** A finger has it. */
    var holding by mutableStateOf(false)
        private set

    /** How far a full open is, in pixels: the sheet's height, or the screen's for Now Playing. */
    var travelPx by mutableFloatStateOf(1f)


    /** 0 closed, 1 open. */
    val value: Float get() = if (holding) held else anim.value

    /** Where it's going: where the finger has it, or where its spring is taking it. */
    val target: Float get() = if (holding) held else anim.targetValue

    // Read while composing, "is it showing" changes twice an opening, not every frame of it: a
    // screen asking it isn't composed again on each frame the sheet moves.
    private val shown = derivedStateOf { value > 0.0005f }
    val visible: Boolean get() = shown.value

    fun show() = animate(1f, 0f)
    fun hide() = animate(0f, 0f)

    /** [dy] in pixels, down positive: dragging down closes, up opens. Applied at once. */
    fun dragBy(dy: Float) {
        if (!holding) {
            // Caught: held from exactly where it is. Whatever spring was carrying it is stopped by
            // the next settle's snap, never by a stop that could arrive after that settle began.
            settling?.cancel()
            raw = anim.value
            held = raw
            holding = true
        }
        raw = (raw - dy / travelPx).coerceAtLeast(0f)
        // Pulled past open, it stays open: a sheet is as tall as it is, and no taller.
        held = raw.coerceAtMost(1f)
        if (raw > 1f) raw = 1f
    }

    /**
     * Let go at [velocityY] pixels per second, down positive. A deliberate flick goes the way it
     * was thrown, however short the pull (iOS's sheets close on a flick); otherwise it lands where
     * the flick would carry it (Apple's projection), open or closed, whichever is nearer. Either
     * way the spring starts at the finger's speed.
     */
    fun settle(velocityY: Float) {
        val velocity = -velocityY / travelPx
        val target = when {
            velocity < -Flick -> 0f
            velocity > Flick -> 1f
            FluidSpring.project(value.coerceAtMost(1f), velocity) > 0.5f -> 1f
            else -> 0f
        }
        animate(target, velocity)
    }

    private fun animate(to: Float, velocity: Float) {
        val from = value
        settling?.cancel()
        settling = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            anim.snapTo(from)
            if (!isActive) return@launch
            holding = false
            anim.animateTo(to, if (to >= 1f) OpenSpring else CloseSpring, initialVelocity = velocity)
        }
    }

    private companion object {
        /** A flick: faster than 0.55 of the whole travel a second (about 1,300 px/s on this phone). */
        const val Flick = 0.55f

        /** Opening comes to rest with a cushion; closing doesn't bounce. */
        val OpenSpring = Motion.fluid(0.44f, 0.86f, 0.0008f)
        val CloseSpring = Motion.fluid(0.4f, 1f, 0.0008f)
    }
}

@Composable
fun rememberDragState(): DragState {
    val scope = rememberCoroutineScope()
    return remember { DragState(scope) }
}

/**
 * Vertical drags on this element move [state] with the finger, and a release lands it, flicks
 * included (velocity from Compose's tracker, which reads the last 100 ms of the finger). Give it to
 * whatever a sheet is pulled from, and to the sheet itself.
 */
fun Modifier.dragsOpen(state: DragState): Modifier = this.pointerInput(state) {
    val tracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = { tracker.resetTracking() },
        onDragEnd = { state.settle(tracker.calculateVelocity().y) },
        onDragCancel = { state.settle(0f) },
    ) { change, dy ->
        change.consume()
        tracker.addPosition(change.uptimeMillis, change.position)
        state.dragBy(dy)
    }
}

/**
 * For a pane with a list in it: the list scrolls first; once it's at its top, the rest of a pull
 * down takes the pane with it, and a pane not fully open opens before its list scrolls (iOS's
 * sheet). A flick that ends a pull lands the pane.
 */
@Composable
fun rememberSheetScroll(state: DragState): NestedScrollConnection = remember(state) {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < 0f && (state.holding || state.value < 1f) && source == NestedScrollSource.UserInput) {
                state.dragBy(available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0f && source == NestedScrollSource.UserInput) {
                state.dragBy(available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (state.holding) {
                state.settle(available.y)
                return available
            }
            return Velocity.Zero
        }
    }
}

/**
 * The grabber: a short capsule of light at the top of anything you can pull down. It's a key too:
 * a touch closes what it's on.
 */
@Composable
fun Grabber(state: DragState, modifier: Modifier = Modifier, label: String = "Close", touchHeight: Dp = 28.dp) {
    Box(
        modifier
            .size(72.dp, touchHeight)
            .semantics { role = Role.Button; contentDescription = label; onClick(label) { state.hide(); true } }
            .dragsOpen(state)
            .pointerInput(state) { detectTapGestures { state.hide() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(38.dp, 5.dp)
                .graphicsLayer {
                    // Held, it lengthens a little, as iOS's does under the thumb.
                    val k = if (state.holding) 1.12f else 1f
                    scaleX = k
                }
                .clip(Corners.Pill)
                .background(Ink.White.copy(alpha = 0.34f)),
        )
    }
}

/**
 * A pane of the tab bar's glass rising from the foot of the screen over a darkening room, [height]
 * tall and never taller. It follows the finger both ways; a list inside scrolls first, and once it's
 * at its top a further pull down takes the sheet with it. [onClosed] runs when it has fully gone.
 */
@Composable
fun DragSheet(state: DragState, height: Dp = 620.dp, onClosed: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val closed by rememberUpdatedState(onClosed)
    val density = LocalDensity.current
    LaunchedEffect(state) {
        var wasOpen = false
        snapshotFlow { state.value }.collect { p ->
            if (p > 0.01f) wasOpen = true
            if (wasOpen && p <= 0.0005f && state.target == 0f) { wasOpen = false; closed() }
        }
    }
    if (!state.visible) return
    val nested = rememberSheetScroll(state)
    Box(Modifier.fillMaxSize()) {
        val travelPx = with(density) { height.toPx() }
        val corner = with(density) { 40.dp.toPx() }
        // The room dims above the sheet only (and a little under its rounded corners): dimming what the
        // sheet covers was a screen's worth of paint on every frame it moved.
        Box(
            Modifier.fillMaxSize()
                .drawBehind {
                    val p = state.value.coerceIn(0f, 1f)
                    if (p > 0.001f) {
                        val top = size.height - p * travelPx + corner
                        drawRect(Color.Black.copy(alpha = 0.6f * p), size = androidx.compose.ui.geometry.Size(size.width, top.coerceIn(0f, size.height)))
                    }
                }
                .pointerInput(state) { detectTapGestures { state.hide() } },
        )
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val travel = travelPx
            SideEffect { state.travelPx = travel }
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .graphicsLayer { translationY = (1f - state.value.coerceIn(0f, 1f)) * travel }
                    .liquidGlass(Corners.Sheet, GlassWeight.Pane)
                    .nestedScroll(nested)
                    .dragsOpen(state),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) { Grabber(state) }
                Column(Modifier.fillMaxWidth().height(height - 32.dp).navigationBarsPadding(), content = content)
            }
        }
    }
}

