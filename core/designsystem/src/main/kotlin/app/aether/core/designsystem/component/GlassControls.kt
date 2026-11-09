package app.aether.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.aether.core.designsystem.theme.Motion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalGlassBackdrop
import app.aether.core.designsystem.glass.backdrop.Backdrop
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.glass.liquidTouch
import app.aether.core.designsystem.glass.rememberLiquidState
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type

/** A round glass key with one mark. [strong] gives the stronger haptic (play, pause, skip). */
@Composable
fun GlassIconButton(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    iconSize: Dp = 22.dp,
    strong: Boolean = false,
    weight: GlassWeight = GlassWeight.Key,
) {
    val liquid = rememberLiquidState()
    val haptics = LocalHaptics.current
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = label; role = Role.Button }
            .liquidGlass(Corners.Pill, weight, liquid)
            .liquidTouch(liquid, LocalReduceMotion.current) { if (strong) haptics.strong() else haptics.light(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, size = iconSize) }
}

/** A plain mark with a generous touch target and no glass: for secondary actions. */
@Composable
fun IconAction(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true, size: Dp = 22.dp, tint: Color? = null) {
    Box(
        modifier.size(48.dp).semantics { contentDescription = label; role = Role.Button }.clip(Corners.Pill).quietClick(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, size = size, color = tint ?: if (active) Ink.White else Ink.Dim) }
}

/** A capsule of glass with words, for permissions and confirmations; [lit] for the one to take. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, lit: Boolean = false) {
    val liquid = rememberLiquidState()
    val haptics = LocalHaptics.current
    Box(
        modifier
            .height(54.dp)
            .semantics { role = Role.Button }
            .liquidGlass(Corners.Pill, GlassWeight.Key, liquid)
            .then(if (lit) Modifier.litDrop(Corners.Pill) { 1f } else Modifier)
            .liquidTouch(liquid, LocalReduceMotion.current) { haptics.light(); onClick() }
            .padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, Type.LabelLarge, upper = true) }
}

/**
 * The search field (after the reference input): a capsule of the tab bar's glass, refracting
 * [backdrop] when the page gives it one (Discover's drifting covers), a hairline mark, the query in
 * the serif. Clears with one touch; shows the loader while results come.
 */
@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    focusRequester: FocusRequester = FocusRequester(),
    onFocus: (Boolean) -> Unit = {},
    backdrop: Backdrop? = LocalGlassBackdrop.current,
) {
    val liquid = rememberLiquidState()
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .liquidGlass(Corners.Pill, GlassWeight.Key, liquid, backdrop = backdrop)
            .liquidTouch(liquid, LocalReduceMotion.current) { focusRequester.requestFocus() }
            .padding(start = 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.Search, size = 18.dp, color = Ink.Grey)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Txt("Songs, artists, albums", Type.TitleSmall, color = Ink.Dim, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                // The grotesk, as every name of a thing is here, without forcing capitals on what's typed.
                textStyle = Type.TitleSmall.copy(color = Ink.White, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.02.em, fontFeatureSettings = null),
                cursorBrush = SolidColor(Ink.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(query) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocus(it.isFocused) },
            )
        }
        when {
            loading -> GridDots(Modifier.padding(horizontal = 12.dp), size = 16.dp)
            query.isNotEmpty() -> IconAction(Glyph.Close, "Clear", { onQuery("") }, size = 16.dp)
        }
    }
}

/**
 * A switch, as iOS has them, in black and white: on, a white track with a black knob; off, a dark
 * track with a white one. The knob springs across, and spreads under the finger.
 */
@Composable
fun PillToggle(on: Boolean, onToggle: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    val touch = remember { MutableInteractionSource() }
    val pressed by touch.collectIsPressedAsState()
    val across by animateFloatAsState(if (on) 1f else 0f, Motion.fluid(0.34f, 0.8f), label = "toggle")
    val spread by animateFloatAsState(if (pressed) 1f else 0f, Motion.fluid(0.26f, 1f), label = "toggle press")
    Canvas(
        modifier
            .size(ToggleWidth, ToggleHeight)
            .semantics {
                role = Role.Switch
                contentDescription = label
                stateDescription = if (on) "On" else "Off"
            }
            .clickable(interactionSource = touch, indication = null) { haptics.select(); onToggle() },
    ) {
        val inset = 2.dp.toPx()
        val tall = size.height - inset * 2
        val wide = tall + 6.dp.toPx() * spread
        val tone = across.coerceIn(0f, 1f)
        drawRoundRect(lerp(ToggleOff, Ink.White, tone), cornerRadius = CornerRadius(size.height / 2f))
        drawRoundRect(
            lerp(Ink.White, Ink.Black, tone),
            topLeft = Offset(inset + (size.width - inset * 2 - wide) * across, inset),
            size = Size(wide, tall),
            cornerRadius = CornerRadius(tall / 2f),
        )
    }
}

private val ToggleWidth = 51.dp
private val ToggleHeight = 31.dp
private val ToggleOff = Color(0xFF39393D)
