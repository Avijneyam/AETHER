package app.aether.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.glass.liquidTouch
import app.aether.core.designsystem.glass.rememberLiquidState
import app.aether.core.designsystem.glass.translucent
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The seek bar (after the wake slider), in a glass pill: thin bars and no thumb. Played bars are
 * white, the rest grey. Drag fast and the bars your finger sweeps past spring up in a wake behind
 * it, then settle. Tap to jump; drag for a precise seek, committed on release. Elapsed time on the
 * left, what's left on the right.
 */
@Composable
fun WakeSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bars: Int = 44,
    /** Its own liquid pill; false when it already sits on glass (Now Playing's deck). */
    liveGlass: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val reduce = LocalReduceMotion.current
    val wake = remember(bars) { List(bars) { Animatable(0f) } }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragging) (dragFraction * durationMs).toLong() else positionMs
    val liquid = rememberLiquidState()

    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (liveGlass) Modifier.liquidGlass(Corners.Pill, GlassWeight.Hairline, liquid).liquidTouch(liquid, reduce) else Modifier.translucent(Corners.Pill))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(clock(shownMs), Type.Meta.copy(fontFeatureSettings = "tnum"), color = if (dragging) Ink.White else Ink.Grey, modifier = Modifier.width(42.dp))
        Box(
            Modifier
                .weight(1f)
                .height(52.dp)
                .semantics {
                    contentDescription = "Position"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target -> onSeek((target.coerceIn(0f, 1f) * durationMs).toLong()); true }
                }
                .pointerInput(durationMs, bars) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (durationMs <= 0) return@awaitEachGesture
                        val w = max(1f, size.width.toFloat())
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        dragging = true
                        dragFraction = (down.position.x / w).coerceIn(0f, 1f)
                        var lastBar = (dragFraction * (bars - 1)).roundToInt()
                        haptics.select()
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            tracker.addPosition(c.uptimeMillis, c.position)
                            dragFraction = (c.position.x / w).coerceIn(0f, 1f)
                            val bar = (dragFraction * (bars - 1)).roundToInt()
                            if (bar != lastBar) {
                                val speed = abs(tracker.calculateVelocity().x)
                                // The faster the sweep, the higher the bars it passes spring up.
                                val height = if (reduce) 0f else min(1f, speed / 2600f)
                                val range = if (bar > lastBar) (lastBar + 1)..bar else bar until lastBar
                                for (b in range) {
                                    val anim = wake[b]
                                    scope.launch {
                                        anim.snapTo(max(anim.value, height))
                                        anim.animateTo(0f, spring(dampingRatio = 0.32f, stiffness = 140f))
                                    }
                                }
                                if (bar % 11 == 0) haptics.tick()
                                lastBar = bar
                            }
                        }
                        onSeek((dragFraction * durationMs).toLong())
                        dragging = false
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(52.dp)) {
                val gap = size.width / bars
                val barWidth = max(1.dp.toPx(), gap * 0.28f)
                val base = 10.dp.toPx()
                val tall = 30.dp.toPx()
                for (i in 0 until bars) {
                    val x = gap * (i + 0.5f)
                    val played = (i + 0.5f) / bars <= fraction
                    val h = base + (tall - base) * wake[i].value
                    drawRoundRect(
                        color = if (played) Ink.White else Ink.White.copy(alpha = 0.26f),
                        topLeft = Offset(x - barWidth / 2, (size.height - h) / 2),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(barWidth / 2),
                    )
                }
            }
        }
        Txt(
            if (durationMs > 0) "-" + clock(max(0, durationMs - shownMs)) else "",
            Type.Meta.copy(fontFeatureSettings = "tnum"),
            color = Ink.Grey,
            align = TextAlign.End,
            modifier = Modifier.width(46.dp),
        )
    }
}

fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
