// After Stats Band by kuratlielia (21st.dev, MIT), rebuilt for Compose and for a finger.
package app.aether.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Glide
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Serif
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.max

/** The tiny picture that proves a number. */
sealed interface BandVisual {
    class Trend(val values: List<Int>) : BandVisual
    class Bars(val values: List<Int>) : BandVisual
    class Distribution(val bins: List<Int>, val marked: Int) : BandVisual
    class Strip(val days: List<Boolean>) : BandVisual
}

class BandStat(val label: String, val value: Double, val decimals: Int = 0, val suffix: String = "", val detail: String, val context: String, val visual: BandVisual)

private const val StaggerMs = 90L
private val CountUp = FluidSpring(1.3f, 1f)
private val DrawIn = FluidSpring(0.9f, 1f)

private val FigureStyle = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 46.sp, lineHeight = 52.sp, fontFeatureSettings = "lnum, tnum")
private val FigureSuffix = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 22.sp, lineHeight = 30.sp, fontFeatureSettings = "lnum")

/**
 * Headline numbers set in a hairline grid, two to a row, each counting up the first time it's seen
 * with a tiny picture drawn in beneath it. Holding one swaps its line for a line of context.
 */
@Composable
fun StatsBand(stats: List<BandStat>, onScreen: () -> Boolean, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    var run by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) {
        snapshotFlow { onScreen() }.first { it }
        run = true
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge).drawBehind {
            drawLine(Ink.Hairline, Offset.Zero, Offset(size.width, 0f), 0.5.dp.toPx())
            drawLine(Ink.Hairline, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx())
        },
    ) {
        stats.chunked(2).forEachIndexed { r, pair ->
            if (r > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).drawBehind { drawRect(Ink.Hairline) })
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { c, stat ->
                    if (c > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().drawBehind { drawRect(Ink.Hairline) })
                    Cell(stat, run, reduce, (r * 2 + c) * StaggerMs, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Cell(stat: BandStat, run: Boolean, reduce: Boolean, delayMs: Long, modifier: Modifier) {
    val count = remember { Glide(if (reduce) 1f else 0f, rest = 0.002f) }
    val draw = remember { Glide(if (reduce) 1f else 0f, rest = 0.002f) }
    LaunchedEffect(run) {
        if (!run) return@LaunchedEffect
        delay(delayMs)
        count.to(1f, CountUp)
    }
    LaunchedEffect(run) {
        if (!run) return@LaunchedEffect
        delay(delayMs + 260L)
        draw.to(1f, DrawIn)
    }
    var held by remember { mutableStateOf(false) }
    Column(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    held = true
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.id == down.id && it.pressed })
                    held = false
                }
            }
            .padding(horizontal = 14.dp, vertical = 18.dp),
    ) {
        Txt(stat.label, Type.Label, color = Ink.Grey, upper = true, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Row {
            Box {
                // The final value holds the width, so nothing moves while the digits change.
                Txt(format(stat.value, stat.decimals), FigureStyle, color = Color.Transparent, maxLines = 1)
                Txt(format(stat.value * count.value.coerceIn(0f, 1f), stat.decimals), FigureStyle, maxLines = 1)
            }
            if (stat.suffix.isNotEmpty()) Txt(stat.suffix, FigureSuffix, color = Ink.Grey, modifier = Modifier.padding(start = 4.dp, top = 17.dp), maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(36.dp)) {
            Txt(stat.detail, Type.Meta, color = Ink.Grey, maxLines = 2, modifier = Modifier.graphicsLayer { alpha = if (held) 0f else 1f })
            Txt(stat.context, Type.Meta, color = Ink.White, maxLines = 2, modifier = Modifier.graphicsLayer { alpha = if (held) 1f else 0f })
        }
        Spacer(Modifier.height(10.dp))
        Visual(stat.visual, { draw.value }, held, Modifier.fillMaxWidth().height(44.dp))
    }
}

private fun format(value: Double, decimals: Int): String = String.format(Locale.US, "%,.${decimals}f", value)

@Composable
private fun Visual(visual: BandVisual, drawn: () -> Float, lit: Boolean, modifier: Modifier) {
    val ink = if (lit) Ink.White else Ink.White.copy(alpha = 0.72f)
    val quiet = Ink.White.copy(alpha = 0.14f)
    Canvas(modifier) {
        val at = drawn().coerceIn(0f, 1f)
        when (visual) {
            is BandVisual.Trend -> {
                val values = visual.values
                val top = max(values.max(), 1)
                val step = size.width / (values.size - 1).coerceAtLeast(1)
                fun y(v: Int) = size.height - 4f - (v.toFloat() / top) * (size.height - 8f)
                val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * step, y(v)) } }
                val area = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                clipRect(right = size.width * at) {
                    drawPath(area, Brush.verticalGradient(listOf(Ink.White.copy(alpha = 0.18f), Color.Transparent)))
                    drawPath(line, ink, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
                }
                if (at > 0.98f) drawCircle(Ink.White, 2.4.dp.toPx(), Offset(size.width - 1f, y(values.last())))
            }
            is BandVisual.Bars -> bars(visual.values, at, ink, quiet) { false }
            is BandVisual.Distribution -> bars(visual.bins, at, ink, quiet) { it >= visual.marked }
            is BandVisual.Strip -> bars(visual.days.map { if (it) 1 else 0 }, at, ink, quiet, floor = 0.32f) { false }
        }
    }
}

/** A row of thin bars rising in from the left; those [dim] says, and any with nothing in them, are quiet. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.bars(values: List<Int>, at: Float, ink: Color, quiet: Color, floor: Float = 0.08f, dim: (Int) -> Boolean) {
    val top = max(values.maxOrNull() ?: 1, 1)
    val gap = 2.dp.toPx()
    val width = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
    values.forEachIndexed { i, v ->
        val rise = ((at * (values.size + 6) - i) / 6f).coerceIn(0f, 1f)
        val share = (v.toFloat() / top).coerceAtLeast(floor)
        val h = size.height * share * rise
        drawRoundRect(
            if (v == 0 || dim(i)) quiet else ink,
            Offset(i * (width + gap), size.height - h),
            Size(width, h),
            CornerRadius(width / 2f),
        )
    }
}
