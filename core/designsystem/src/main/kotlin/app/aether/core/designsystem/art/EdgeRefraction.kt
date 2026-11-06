package app.aether.core.designsystem.art

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import app.aether.core.designsystem.glass.LocalReduceMotion

/**
 * The edge glass for a row of folders: the same bending of light, about half as strong, so each
 * folder's own frosted front stays the thing you see, and a folder passing the edge is only gently lifted.
 */
@Composable
fun Modifier.gentleEdgeRefraction(state: LazyListState?): Modifier =
    edgeRefraction(state, edgeFraction = 0.13f, pull = 0.38f, liftFraction = 0.08f, chromaDp = 2f, smearDp = 3f)

/**
 * Light bends at the sides of a horizontal list, as through the curved edge of thick glass: what
 * scrolls in or out is magnified, lifted and smeared, with a breath of colour (the carousel recording).
 * Only where something is passing: with [state], an edge with nothing beyond it (the start of the
 * list, its end) stays clear, so items at rest are never distorted. Give the list some vertical
 * padding: magnified items grow up to [liftFraction] taller.
 */
@Composable
fun Modifier.edgeRefraction(
    state: LazyListState? = null,
    edgeFraction: Float = 0.16f,
    pull: Float = 0.62f,
    liftFraction: Float = 0.16f,
    chromaDp: Float = 3.5f,
    smearDp: Float = 6f,
): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || LocalReduceMotion.current) return this
    val density = LocalDensity.current.density
    val left by animateFloatAsState(if (state == null || state.canScrollBackward) 1f else 0f, tween(260), label = "left")
    val right by animateFloatAsState(if (state == null || state.canScrollForward) 1f else 0f, tween(260), label = "right")
    val shader = remember { RuntimeShader(Shaders.EDGE_REFRACTION) }
    val cache = remember { arrayOfNulls<Any>(2) }
    return this.graphicsLayer {
        val s = size
        if (s.width <= 0f || s.height <= 0f) return@graphicsLayer
        if (left <= 0.001f && right <= 0.001f) { renderEffect = null; return@graphicsLayer }
        // Rebuilt only when the size or an edge's strength changes.
        val key = listOf(s.width, s.height, left, right)
        if (cache[0] != key) {
            shader.setFloatUniform("size", s.width, s.height)
            shader.setFloatUniform("edge", s.width * edgeFraction)
            shader.setFloatUniform("pull", pull)
            shader.setFloatUniform("lift", liftFraction)
            shader.setFloatUniform("chroma", chromaDp * density)
            shader.setFloatUniform("smear", smearDp * density)
            shader.setFloatUniform("leftOn", left)
            shader.setFloatUniform("rightOn", right)
            cache[0] = key
            cache[1] = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
        renderEffect = cache[1] as androidx.compose.ui.graphics.RenderEffect
    }
}
