package app.aether.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink
import kotlin.math.PI
import kotlin.math.cos

/**
 * The loader (after the grid-dots reference): a small square of dots that brighten and swell in a
 * diagonal wave. Never a spinner. With reduced motion the wave slows to a breath.
 */
@Composable
fun GridDots(
    modifier: Modifier = Modifier,
    grid: Int = 3,
    size: Dp = 22.dp,
    color: Color = Ink.White,
    label: String = "Loading",
) {
    val reduce = LocalReduceMotion.current
    val phase by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (reduce) 2600 else 1100, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier.size(size).semantics { contentDescription = label }) {
        val step = this.size.minDimension / grid
        val r = step * 0.16f
        for (row in 0 until grid) for (col in 0 until grid) {
            // The wave runs from the top-left corner to the bottom-right.
            val offset = (row + col) / (2f * (grid - 1)).coerceAtLeast(1f)
            val wave = 0.5f + 0.5f * cos(2f * PI.toFloat() * (phase - offset * 0.6f))
            val alpha = 0.16f + 0.84f * wave * wave
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = r * (0.75f + 0.45f * wave),
                center = Offset(step * (col + 0.5f), step * (row + 0.5f)),
            )
        }
    }
}
