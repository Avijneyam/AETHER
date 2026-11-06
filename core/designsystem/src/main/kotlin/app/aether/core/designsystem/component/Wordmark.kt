package app.aether.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type

/**
 * The cross of light from the CANTICUM mark: a long vertical beam, a shorter horizontal one, a
 * bright core in a soft halo, and faint diagonal glints. Additive, so it only ever adds light.
 */
@Composable
fun LightCross(modifier: Modifier = Modifier, size: Dp = 120.dp, intensity: Float = 1f) {
    Canvas(modifier.size(size)) { drawLightCross(intensity) }
}

fun DrawScope.drawLightCross(intensity: Float = 1f, centre: Offset = center, extent: Float = size.minDimension) {
    val i = intensity.coerceIn(0f, 1.5f)
    val s = extent
    val white = Color.White
    // Halo
    drawCircle(
        Brush.radialGradient(listOf(white.copy(alpha = 0.42f * i), white.copy(alpha = 0.08f * i), Color.Transparent), centre, s * 0.3f),
        radius = s * 0.3f, center = centre, blendMode = BlendMode.Plus,
    )
    fun beam(halfLength: Float, thickness: Float, alpha: Float, vertical: Boolean) {
        val brush = if (vertical) {
            Brush.verticalGradient(listOf(Color.Transparent, white.copy(alpha = alpha), Color.Transparent), centre.y - halfLength, centre.y + halfLength)
        } else {
            Brush.horizontalGradient(listOf(Color.Transparent, white.copy(alpha = alpha), Color.Transparent), centre.x - halfLength, centre.x + halfLength)
        }
        val topLeft = if (vertical) Offset(centre.x - thickness / 2, centre.y - halfLength) else Offset(centre.x - halfLength, centre.y - thickness / 2)
        val sz = if (vertical) Size(thickness, halfLength * 2) else Size(halfLength * 2, thickness)
        drawRect(brush, topLeft, sz, blendMode = BlendMode.Plus)
    }
    // Long vertical, shorter horizontal: a cross, not a plus.
    beam(s * 0.5f, s * 0.012f, 0.95f * i, vertical = true)
    beam(s * 0.5f, s * 0.05f, 0.18f * i, vertical = true)
    beam(s * 0.3f, s * 0.011f, 0.9f * i, vertical = false)
    beam(s * 0.3f, s * 0.045f, 0.15f * i, vertical = false)
    // Faint diagonal glints.
    rotate(45f, centre) {
        beam(s * 0.11f, s * 0.008f, 0.5f * i, vertical = true)
        beam(s * 0.11f, s * 0.008f, 0.5f * i, vertical = false)
    }
    // The core.
    drawCircle(Brush.radialGradient(listOf(white.copy(alpha = i), white.copy(alpha = 0f)), centre, s * 0.04f), radius = s * 0.04f, center = centre, blendMode = BlendMode.Plus)
}

/** Aether, signed in the script, over the cross of light, with the year below. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, crossSize: Dp = 132.dp, showYear: Boolean = true, aside: String? = "A Music Sanctuary") {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LightCross(size = crossSize)
        Spacer(Modifier.height(20.dp))
        Txt("Aether", Type.Inscription)
        if (aside != null) {
            Spacer(Modifier.height(6.dp))
            Txt(aside, Type.Aside, color = Ink.Grey)
        }
        if (showYear) {
            Spacer(Modifier.height(28.dp))
            Txt("MMXXVI", Type.Label.copy(fontSize = 10.sp), color = Ink.Dim)
        }
    }
}
