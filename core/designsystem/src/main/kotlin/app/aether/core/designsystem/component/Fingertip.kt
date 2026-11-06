package app.aether.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The fingertip as a light source: wherever a finger presses something you can touch, a soft
 * light blooms under it, brightest at the point of contact, and fades slowly after release. It
 * only ever adds light (never covers words), and stays inside what was pressed. Every pressable
 * thing in AETHER uses it, so feedback is the same everywhere.
 */
object FingertipLight : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = LightNode(interactionSource)
    override fun hashCode(): Int = 17
    override fun equals(other: Any?): Boolean = other === this
}

private class LightNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val glow = Animatable(0f)
    private var at = Offset.Unspecified

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        at = interaction.pressPosition
                        launch { glow.animateTo(1f, tween(110)) { invalidateDraw() } }
                    }
                    // A quick tap in a list arrives as press and release together: it still blooms fully before it fades.
                    is PressInteraction.Release -> launch {
                        if (glow.value < 1f) glow.animateTo(1f, tween((110 * (1f - glow.value)).toInt().coerceAtLeast(40))) { invalidateDraw() }
                        glow.animateTo(0f, tween(560, easing = Motion.Editorial)) { invalidateDraw() }
                    }
                    is PressInteraction.Cancel -> launch { glow.animateTo(0f, tween(320, easing = Motion.Editorial)) { invalidateDraw() } }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = glow.value
        if (a <= 0.002f || at == Offset.Unspecified) return
        // Sized to what's pressed: a halo on a key, a pool of light on a row, never a floodlight.
        val r = min(size.maxDimension * 0.6f, size.minDimension * 1.6f) + 24.dp.toPx()
        clipRect {
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.24f * a),
                    0.4f to Color.White.copy(alpha = 0.08f * a),
                    1f to Color.Transparent,
                    center = at,
                    radius = r,
                ),
                radius = r,
                center = at,
                blendMode = BlendMode.Plus,
            )
        }
    }
}
