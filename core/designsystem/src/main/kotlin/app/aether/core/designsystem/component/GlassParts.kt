package app.aether.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import app.aether.core.designsystem.glass.drawLensRim
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalGlassBackdrop
import app.aether.core.designsystem.glass.backdrop.Backdrop
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.glass.liquidTouch
import app.aether.core.designsystem.glass.rememberLiquidState
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Artwork
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Words and marks always sit on glass, and it is always the tab bar's glass: a capsule, a circle, a
 * segmented bar, a grouped panel. Floating, it refracts what passes under it; on a page it's the
 * same glass over the room's black. Every key swells and leans under the finger as the bar does, and
 * a choice is marked the way the bar marks its room: a lit drop that slides to it.
 */

/**
 * The lit drop the tab bar marks its room with, drawn inside [shape] at [amount] (0 to 1): a brighter
 * frost and a brighter rim. [amount] is read while drawing, so it can ride a spring for nothing.
 */
fun Modifier.litDrop(shape: Shape, amount: () -> Float): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    val fill = Brush.verticalGradient(listOf(Ink.White.copy(alpha = 0.20f), Ink.White.copy(alpha = 0.11f)))
    onDrawBehind {
        val a = amount().coerceIn(0f, 1f)
        if (a > 0.004f) {
            drawOutline(outline, fill, alpha = a)
            // Its edge is the dragged selector's: a lens's rim, light gathered inside it.
            drawLensRim(path, a * 1.1f)
        }
    }
}

/** A capsule of glass holding a few words, a flat mark, or both. [lit] when it's the chosen one. */
@Composable
fun GlassPill(
    text: String?,
    modifier: Modifier = Modifier,
    icon: Glyph? = null,
    trailingIcon: Glyph? = null,
    onClick: (() -> Unit)? = null,
    lit: Boolean = false,
    @Suppress("UNUSED_PARAMETER") live: Boolean = false,
    small: Boolean = false,
) {
    val liquid = rememberLiquidState()
    val haptics = LocalHaptics.current
    val reduce = LocalReduceMotion.current
    val tint by animateColorAsState(if (lit || onClick == null) Ink.White else Ink.White.copy(alpha = 0.78f), Motion.fluid(0.4f, 1f), label = "pill")
    val glow by animateFloatAsState(if (lit) 1f else 0f, Motion.snappy(), label = "lit")
    Row(
        modifier
            .then(if (onClick != null) Modifier.semantics { role = Role.Button; selected = lit } else Modifier)
            .liquidGlass(Corners.Pill, GlassWeight.Key, if (onClick != null) liquid else null)
            .litDrop(Corners.Pill) { glow }
            .then(if (onClick != null) Modifier.liquidTouch(liquid, reduce) { haptics.select(); onClick() } else Modifier)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, size = if (small) 12.dp else 15.dp, color = tint)
            if (text != null) Spacer(Modifier.width(8.dp))
        }
        if (text != null) Txt(text, if (small) Type.Label.copy(fontSize = Type.Label.fontSize * 0.92f) else Type.Label, color = tint, upper = true, maxLines = 1)
        if (trailingIcon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(trailingIcon, size = if (small) 11.dp else 13.dp, color = tint)
        }
    }
}

/** A round key of the tab bar's glass with one flat mark; [lit] when it's on. */
@Composable
fun GlassCircle(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 18.dp,
    @Suppress("UNUSED_PARAMETER") live: Boolean = false,
    lit: Boolean = false,
    iconModifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
) {
    val liquid = rememberLiquidState()
    val haptics = LocalHaptics.current
    val glow by animateFloatAsState(if (lit) 1f else 0f, Motion.snappy(), label = "lit")
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = label; role = Role.Button; selected = lit }
            .liquidGlass(Corners.Pill, GlassWeight.Key, liquid, backdrop = backdrop)
            .litDrop(Corners.Pill) { glow }
            .liquidTouch(liquid, LocalReduceMotion.current) { haptics.light(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, iconModifier, size = iconSize) }
}

/**
 * Choices side by side in one capsule of the tab bar's glass; the chosen one is marked by the bar's
 * lit drop, which slides to a new choice on a spring (with a little give) and stretches as it goes,
 * as a drop of water would. Wider than the screen, the bar scrolls.
 */
@Composable
fun GlassSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Where each choice sits in the bar, once laid out.
    val slots = remember(options.size) { mutableStateListOf<Pair<Float, Float>>().apply { repeat(options.size) { add(0f to 0f) } } }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    val slot = slots.getOrNull(selected)
    LaunchedEffect(selected, slot) {
        val (sx, sw) = slot ?: return@LaunchedEffect
        if (sw <= 0f) return@LaunchedEffect
        if (w.value <= 0f || reduce) {
            x.snapTo(sx); w.snapTo(sw)
        } else {
            // The width answers a little faster than the place: the drop leans into its move.
            launch { w.animateTo(sw, Motion.fluid(0.34f, 0.8f)) }
            x.animateTo(sx, Motion.snappy())
        }
    }
    Box(
        modifier
            .liquidGlass(Corners.Pill, GlassWeight.Key)
            .clip(Corners.Pill)
            .horizontalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .offset { IntOffset(x.value.roundToInt(), 0) }
                .graphicsLayer { alpha = if (w.value > 0f) 1f else 0f }
                .size(with(density) { w.value.toDp() }, 36.dp)
                .litDrop(Corners.Pill) { 1f },
        )
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val ink by animateColorAsState(if (on) Ink.White else Ink.Grey, Motion.fluid(0.4f, 1f), label = "segment")
                Box(
                    Modifier
                        .height(36.dp)
                        .onPlaced { c -> if (i < slots.size) slots[i] = c.positionInParent().x to c.size.width.toFloat() }
                        .semantics { role = Role.Tab; this.selected = on }
                        .clip(Corners.Pill)
                        .quietClick { if (!on) { haptics.select(); scope.launch { onSelect(i) } } }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt(label, Type.Label, color = ink, upper = true, maxLines = 1) }
            }
        }
    }
}

/**
 * Rows grouped on one pane of the tab bar's glass (iOS's inset grouped list), with hairlines
 * between them. Given [frost], the glass lies over that picture, frosted (see [Frost]).
 */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, frost: Artwork? = null, content: @Composable ColumnScope.() -> Unit) {
    if (frost == null) {
        Column(modifier.fillMaxWidth().liquidGlass(Corners.Large, GlassWeight.Pane).padding(horizontal = 16.dp, vertical = 6.dp), content = content)
        return
    }
    Box(modifier.fillMaxWidth().clip(Corners.Large)) {
        Frost(frost, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().liquidGlass(Corners.Large, GlassWeight.Control).padding(horizontal = 16.dp, vertical = 6.dp), content = content)
    }
}

@Composable
fun PanelDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 60.dp).height(0.5.dp).background(Ink.Hairline))
}

/**
 * A small label: a section's strapline, a fact about a page. Plain spaced capitals: glass is for what
 * can be touched, and a label in a capsule looked just like the key beside it.
 */
@Composable
fun Strapline(text: String, modifier: Modifier = Modifier, color: Color = Ink.Grey) =
    Txt(text, Type.Label, modifier, color = color, align = TextAlign.Center, upper = true)
