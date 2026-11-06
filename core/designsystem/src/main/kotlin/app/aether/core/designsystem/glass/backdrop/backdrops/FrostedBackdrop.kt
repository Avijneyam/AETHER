package app.aether.core.designsystem.glass.backdrop.backdrops

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import app.aether.core.designsystem.glass.backdrop.Backdrop
import app.aether.core.designsystem.glass.backdrop.internal.InverseLayerScope

/*
 * AETHER addition to the vendored Backdrop library.
 */

/**
 * [source]'s plane, frosted once and kept. [frost] holds the plane at [scale] of its resolution,
 * blurred by [radiusPx] (in full-resolution pixels), as a layer of its own: recorded a single time,
 * so Android renders it again only when something in the plane changes, never because a piece of
 * glass over it moved. A sheet sliding over a still Now Playing samples this frost, already made,
 * and adds only its lens: what was a fresh blur on every frame of the slide is a texture read.
 *
 * Glass drawing it must record its backdrop at the same [scale] (the frost is drawn at its own
 * size inside that smaller recording, so one frost pixel is one recorded pixel).
 */
@Stable
class FrostedBackdrop(
    private val source: LayerBackdrop,
    private val frost: GraphicsLayer,
    val scale: Float,
    private val radiusPx: Float,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    private var recordedFor: IntSize = IntSize.Zero
    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = source.layerCoordinates ?: return
        val plane = layerCoordinates.size
        if (plane.width <= 0 || plane.height <= 0) return
        // Recorded once per size: it only says "the plane, smaller, blurred", so it never needs
        // recording again for the plane's own changes; Android re-renders it when the plane changes.
        if (plane != recordedFor) {
            val small = IntSize((plane.width * scale).toInt().coerceAtLeast(1), (plane.height * scale).toInt().coerceAtLeast(1))
            val r = (radiusPx * scale).coerceAtLeast(0.5f)
            frost.renderEffect = BlurEffect(r, r, TileMode.Clamp)
            frost.record(density, layoutDirection, small) {
                scale(scale, pivot = Offset.Zero) { drawLayer(source.graphicsLayer) }
            }
            recordedFor = plane
        }
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
            // Drawn here at full size; the glass's own recording shrinks it back to the frost's size.
            scale(1f / scale, 1f / scale, pivot = Offset.Zero)
        }) {
            drawLayer(frost)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
