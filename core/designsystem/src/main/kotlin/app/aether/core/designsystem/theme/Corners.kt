package app.aether.core.designsystem.theme

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle

/**
 * Apple's corners: continuous curvature (a squircle's G2 join), never a circle's arc stuck to a
 * straight edge. Capsules for every bar and button; rounded rectangles in a few sizes; and only
 * these, so every corner in the app agrees with every other.
 */
object Corners {
    fun of(radius: Dp): Shape = RoundedRectangle(radius, RoundedCornerStyle.Continuous)

    /** Bars, buttons, pills, chips. */
    val Pill: Shape = Capsule(RoundedCornerStyle.Continuous)

    /** Small artwork in rows, tags. */
    val Small: Shape = of(10.dp)

    /** Cards and artwork. */
    val Medium: Shape = of(16.dp)

    /** Grouped panels, tiles. */
    val Large: Shape = of(26.dp)

    /** Sheets rising from the bottom: only their top corners round. */
    val Sheet: Shape = UnevenRoundedRectangle(38.dp, 38.dp, 0.dp, 0.dp, RoundedCornerStyle.Continuous)
}
