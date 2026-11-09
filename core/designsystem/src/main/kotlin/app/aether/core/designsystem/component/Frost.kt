package app.aether.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import app.aether.core.designsystem.art.PrintFilter
import app.aether.core.designsystem.art.SoftArt
import app.aether.core.model.Artwork

/**
 * The frost under a card's glass. The floating glass frosts whatever scrolls beneath it; a card
 * sits on the page with nothing beneath it but black, so its glass frosts its own picture instead:
 * the cover far out of focus ([SoftArt], blurred once and cached), overscanned so no edge shows,
 * under the room's darkness so the words on it read. Without a picture it's the darkness alone.
 * Grey whatever the cover: blurred and darkened, a cover's colours only go to mud, and the cover is
 * right there beside it in colour.
 */
@Composable
fun Frost(artwork: Artwork, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (!artwork.isEmpty) {
            SoftArt(
                artwork,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                    },
                colorFilter = PrintFilter,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.36f), Color.Black.copy(alpha = 0.6f)))))
    }
}
