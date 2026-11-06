package app.aether.core.designsystem.art

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import app.aether.core.model.Artwork
import coil3.size.Size
import coil3.transform.Transformation

/**
 * A cover as light: a few dozen pixels of it, blurred once on the CPU and cached with the picture,
 * then drawn stretched. The frost under a card's glass, a folder's front and Now Playing's glow are
 * all this, so the screen never runs a blur for them: what was a full-size blur every frame is now
 * a tiny bitmap drawn with bilinear filtering, which is what a heavy blur looks like anyway. Kept in
 * memory under its own key, so a cover seen once is light at once everywhere after.
 */
@Composable
fun SoftArt(artwork: Artwork, modifier: Modifier = Modifier, px: Int = 40, colorFilter: ColorFilter? = null) {
    // One copy (software) serves both this and anything that samples it ([rememberSoftArt]).
    Picture(artwork.url(px), px, modifier, colorFilter = colorFilter, transformation = SoftBlur, software = true, filterQuality = FilterQuality.Low)
}

/** The soft cover itself, for drawing through glass (a card's frost, a folder's front). Software, so shaders can sample it. */
@Composable
fun rememberSoftArt(artwork: Artwork, px: Int = 40): ImageBitmap? =
    rememberPicture(artwork.url(px), px, software = true, transformation = SoftBlur)

/** Three box blurs either way (close to a Gaussian), edges held. For tiny bitmaps only. */
object SoftBlur : Transformation() {
    private const val RADIUS = 3

    override val cacheKey: String = "soft-blur-$RADIUS"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val a = IntArray(w * h)
        input.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h)
        repeat(3) {
            pass(a, b, w, h, horizontal = true)
            pass(b, a, w, h, horizontal = false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val span = 2 * RADIUS + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, length - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -RADIUS..RADIUS) {
                val c = at(i)
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until length) {
                val out = ((sa / span) shl 24) or ((sr / span) shl 16) or ((sg / span) shl 8) or (sb / span)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val gone = at(i - RADIUS)
                val come = at(i + RADIUS + 1)
                sa += (come ushr 24) - (gone ushr 24)
                sr += ((come shr 16) and 0xFF) - ((gone shr 16) and 0xFF)
                sg += ((come shr 8) and 0xFF) - ((gone shr 8) and 0xFF)
                sb += (come and 0xFF) - (gone and 0xFF)
            }
        }
    }
}
