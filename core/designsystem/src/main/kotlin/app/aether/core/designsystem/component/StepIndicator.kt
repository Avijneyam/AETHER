package app.aether.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The indicator's height: its dots and the capsule round them. */
private val Height = 21.dp

/** How many screens a row holds, and where it is among them (0 to pages - 1, between them while moving). */
private class RowPages(val pages: Int, val position: Float)

private fun LazyListState.rowPages(maxPages: Int): RowPages {
    val info = layoutInfo
    val first = info.visibleItemsInfo.firstOrNull() ?: return RowPages(0, 0f)
    val span = first.size + info.mainAxisItemSpacing
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val content = info.beforeContentPadding + info.totalItemsCount * span - info.mainAxisItemSpacing + info.afterContentPadding
    val range = (content - viewport).coerceAtLeast(0)
    if (range <= 0) return RowPages(1, 0f)
    val pages = (ceil(range / viewport.toFloat()).toInt() + 1).coerceIn(2, maxPages)
    val scrolled = when {
        !canScrollForward -> range.toFloat()
        else -> (firstVisibleItemIndex * span + firstVisibleItemScrollOffset).toFloat().coerceIn(0f, range.toFloat())
    }
    return RowPages(pages, scrolled / range * (pages - 1))
}

/**
 * Where a row is, after the step player (bencho.dev): pages behind are white dots, pages ahead are
 * grey, and the page you're on is a bar. When the row comes to rest the bar fills, left to right;
 * swipe on and it eases into a dot as the next one stretches into the bar, on a spring. It follows
 * the finger and never moves the row itself: shelves are for browsing, so nothing scrolls by its
 * own accord. On a small capsule of the tab bar's glass. Nothing shows for a row that fits.
 */
@Composable
fun StepIndicator(state: LazyListState, modifier: Modifier = Modifier, maxPages: Int = 9) {
    val geometry by remember(state) { derivedStateOf { state.rowPages(maxPages) } }
    val pages = geometry.pages
    // Its room is kept even when there's nothing to show (a row that fits, or one not yet measured):
    // a row that grew a frame after it appeared made a fast-scrolled page jump.
    if (pages < 2) {
        Spacer(modifier.height(Height))
        return
    }
    val reduce = LocalReduceMotion.current
    val current = geometry.position.roundToInt().coerceIn(0, pages - 1)
    // Which element is the bar, eased between dots on a spring with a little give.
    val bar by animateFloatAsState(current.toFloat(), Motion.fluid(0.42f, 0.8f), label = "step")
    val fill = remember { Animatable(if (reduce) 1f else 0f) }
    val moving = state.isScrollInProgress
    LaunchedEffect(current, moving, reduce) {
        when {
            reduce -> fill.snapTo(1f)
            moving -> if (fill.targetValue < 1f || fill.value < 1f) fill.snapTo(0f)
            else -> fill.animateTo(1f, Motion.fluid(0.9f, 1f))
        }
    }
    val dot = 5.dp
    val wide = 24.dp
    val gap = 6.dp
    val width = dot * (pages - 1) + wide + gap * (pages - 1)
    Canvas(
        modifier
            .semantics { contentDescription = "Page ${current + 1} of $pages" }
            .liquidGlass(Corners.Pill, GlassWeight.Key, backdrop = null)
            .padding(horizontal = 11.dp, vertical = 8.dp)
            .size(width, dot),
    ) {
        val d = dot.toPx()
        val w = wide.toPx()
        val g = gap.toPx()
        val r = CornerRadius(d / 2f)
        var x = 0f
        for (j in 0 until pages) {
            val weight = (1f - abs(bar - j)).coerceIn(0f, 1f)
            val size = d + (w - d) * weight
            val done = j < current
            val track = if (done) Ink.White else Ink.White.copy(alpha = 0.26f)
            drawRoundRect(track, Offset(x, 0f), Size(size, d), r)
            if (j == current) {
                val f = fill.value.coerceIn(0f, 1f)
                if (f > 0.002f) drawRoundRect(Ink.White, Offset(x, 0f), Size(d + (size - d) * f, d), r)
            }
            x += size + g
        }
    }
}
