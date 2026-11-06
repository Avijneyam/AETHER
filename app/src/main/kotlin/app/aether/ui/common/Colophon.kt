package app.aether.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import app.aether.core.designsystem.component.GridDots
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.R
import app.aether.core.designsystem.component.LightCross
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type

/**
 * The end of every page: AETHER's cross of light and its name, and just beneath them the Earth at
 * night in black and white, its horizon lit. The page ends on the city lights themselves, with no
 * black beneath: by the time you reach it you've been reading down, so the dock has folded low and
 * floats over the lights, the horizon clear above it.
 */
@Composable
fun Colophon(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LightCross(size = 48.dp, intensity = 0.7f)
        Spacer(Modifier.height(14.dp))
        Txt("Aether", Type.Inscription.copy(fontSize = 30.sp, lineHeight = 34.sp), color = Ink.Grey)
        Box(Modifier.fillMaxWidth().aspectRatio(740f / 493f)) {
            Image(
                painterResource(R.drawable.art_earth),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Only the sky fades into the page above; the lights run to the very foot.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black, 0.32f to Color.Transparent)))
        }
    }
}

/** What a page leaves at its foot for the dock when it doesn't end on the colophon. */
val DockClearance = 230.dp

/**
 * A page still on its way: the loader in the middle of the room, where the page will be, and no
 * colophon yet (a page's foot only shows once there's a page above it).
 */
fun LazyListScope.pageLoading(key: String = "loading") {
    item(key = key) {
        Box(Modifier.fillParentMaxWidth().fillParentMaxHeight(0.72f), contentAlignment = Alignment.Center) { GridDots() }
    }
}
