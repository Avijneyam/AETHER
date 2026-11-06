package app.aether.core.designsystem.glass

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.glass.backdrop.backdrops.LayerBackdrop

/**
 * The page behind something rising over it, going out of focus as it rises and coming back as it
 * falls: the blur follows [amount] (0 clear, 1 gone) frame for frame, and the page darkens with it.
 *
 * The page isn't drawn again for this. It's a picture of the plane a quarter the size, recorded once,
 * and a blur at that size is what's redone each frame, for little more than a texture read; a page
 * that goes small and soft loses nothing. Only what's above [coveredFrom] is painted: below it is
 * whatever is rising, which covers it.
 */
@Composable
fun BackdropVeil(amount: () -> Float, coveredFrom: () -> Float, modifier: Modifier = Modifier) {
    val plane = LocalGlassBackdrop.current as? LayerBackdrop
    val layer = rememberGraphicsLayer()
    val deepest = with(LocalDensity.current) { VeilBlur.toPx() } * VeilScale
    val recordedFor = remember { arrayOf(IntSize.Zero) }
    Spacer(
        modifier.fillMaxSize().drawBehind {
            val p = amount().coerceIn(0f, 1f)
            if (p <= 0.002f) return@drawBehind
            clipRect(bottom = coveredFrom().coerceIn(0f, size.height) + 1f) {
                if (plane != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val small = IntSize((size.width * VeilScale).toInt().coerceAtLeast(1), (size.height * VeilScale).toInt().coerceAtLeast(1))
                    if (small != recordedFor[0]) {
                        layer.record(this@drawBehind, layoutDirection, small) {
                            scale(VeilScale, VeilScale, pivot = Offset.Zero) { drawLayer(plane.graphicsLayer) }
                        }
                        recordedFor[0] = small
                    }
                    // In over the first of its travel: at the very start a small blurred copy over the
                    // sharp page reads as the page softening, not as a picture swapped in.
                    layer.alpha = (p / VeilArrives).coerceAtMost(1f)
                    val radius = (deepest * p).coerceAtLeast(0.5f)
                    layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    scale(1f / VeilScale, 1f / VeilScale, pivot = Offset.Zero) { drawLayer(layer) }
                }
                drawRect(Color.Black.copy(alpha = VeilDim * p))
            }
        },
    )
}

/** The resolution the veil works at, against the page's. */
private const val VeilScale = 0.25f

/** How much of its travel it takes to come in. */
private const val VeilArrives = 0.12f

/** How dark the page goes at the end of it. */
private const val VeilDim = 0.5f

/** How soft the page goes at the end of it. */
private val VeilBlur = 36.dp
