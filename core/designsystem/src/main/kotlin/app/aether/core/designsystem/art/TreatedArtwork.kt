package app.aether.core.designsystem.art

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink
import app.aether.core.model.Artwork
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.max
import app.aether.core.designsystem.glass.GlassQuality
import app.aether.core.designsystem.glass.LocalGlassQuality

/**
 * How a picture is shown. Not every picture gets the same: the screen and the artwork decide
 * (docs/product.md §2).
 */
sealed interface ArtTreatment {
    /** The artwork as it is. */
    data object Original : ArtTreatment

    /** Black and white with film grain. */
    data class Monochrome(val contrast: Float = 1.12f, val grain: Float = 0.5f) : ArtTreatment

    /** Rebuilt from characters, a cell [cellDp] wide. */
    data class Ascii(val cellDp: Float = 5f, val contrast: Float = 1.25f) : ArtTreatment

    /** A dot screen, [dotDp] apart at [angle] radians. */
    data class Halftone(val dotDp: Float = 4.5f, val angle: Float = 0.785f, val contrast: Float = 1.1f) : ArtTreatment

    /** One bit per dot, [dotDp] square. */
    data class Dither(val dotDp: Float = 1.6f, val contrast: Float = 1.1f) : ArtTreatment

    companion object {
        /**
         * Every picture in AETHER that isn't the song on: black and white, with a little contrast. No
         * grain to compute, so no shader, however large. Colour belongs to the song that's playing.
         */
        val Print: ArtTreatment = Monochrome(grain = 0f)

        /** The cycle Now Playing offers. */
        val Cycle: List<ArtTreatment> = listOf(Monochrome(), Original, Halftone(), Ascii(), Dither())
    }
}

/**
 * A picture with a treatment, cropped to fill. While it loads the space stays black (never a grey
 * box). [develop] (0..1, read as it draws, so a print developing doesn't compose anything again) is
 * Now Playing's darkroom arrival; given at all, the picture always takes the shader's path, so it's
 * the same picture from its first frame to its last. [animateGrain] keeps the grain alive on screens
 * where the picture is the subject.
 */
@Composable
fun TreatedArtwork(
    artwork: Artwork,
    treatment: ArtTreatment,
    modifier: Modifier = Modifier,
    sizeHintPx: Int = 600,
    develop: (() -> Float)? = null,
    animateGrain: Boolean = false,
    contentDescription: String? = null,
) {
    val url = artwork.url(sizeHintPx)
    // The original is a plain image unless it's developing: then it takes the shader path.
    if (url == null || (treatment == ArtTreatment.Original && develop == null)) {
        Box(modifier.background(Ink.Charcoal)) {
            if (url != null) Picture(url, sizeHintPx, Modifier.fillMaxSize(), contentDescription = contentDescription)
        }
        return
    }
    // A thumbnail in black and white is too small to show grain: a colour filter does it without a shader.
    if (treatment is ArtTreatment.Monochrome && (sizeHintPx <= 240 || treatment.grain == 0f) && develop == null) {
        Box(modifier.background(Ink.Charcoal)) {
            Picture(url, sizeHintPx, Modifier.fillMaxSize(), colorFilter = monoFilter(treatment.contrast), contentDescription = contentDescription)
        }
        return
    }
    val picture = rememberPictureState(url, sizeHintPx)
    val shown = picture.arrival()
    val bitmap = picture.bitmap
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // No runtime shaders: every treatment falls back to plain monochrome.
        Box(modifier.background(Ink.Charcoal)) {
            if (bitmap != null) Image(bitmap, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }))
        }
        return
    }
    val alive = animateGrain && !LocalReduceMotion.current && LocalGlassQuality.current == GlassQuality.Liquid
    ShaderArtwork(
        bitmap,
        treatment,
        modifier.graphicsLayer {
            alpha = shown()
            // A print that isn't changing is a texture: its shader runs once, not on every frame that
            // anything anywhere moves. It has to be here, on the drawing itself, to stay kept: Android
            // draws again any layer with another layer inside it, whenever glass samples the page.
            compositingStrategy = if (!alive && (develop == null || develop() <= 0f)) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        },
        develop,
        alive,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderArtwork(bitmap: ImageBitmap?, treatment: ArtTreatment, modifier: Modifier, develop: (() -> Float)?, animateGrain: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // One per treatment for the whole app: compiling one for every new print is what a cover arriving
    // paid for. Sharing is safe: each drawing sets everything first, and keeps what it set.
    val shader = remember(treatment::class) { sharedShader(treatment) }
    val atlas = remember(treatment is ArtTreatment.Ascii) { if (treatment is ArtTreatment.Ascii) GlyphAtlas.ramp(context) else null }
    val glyphShader = remember(atlas) { atlas?.let { BitmapShader(it.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } }
    val imageShader = remember(bitmap) { bitmap?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } }
    var time by remember { mutableFloatStateOf(0f) }
    if (animateGrain) {
        LaunchedEffect(Unit) {
            // Film grain moves at film's own pace, twenty-four frames a second: it reads as film, not
            // as a screen refreshing, and the print (and the glass refracting it) redraws two and a
            // half to five times less often than at 60 or 120 Hz.
            val start = withFrameNanos { it }
            while (true) withFrameNanos {
                val stepped = (((it - start) / 1e9f) * GrainRate).toInt() / GrainRate
                if (stepped != time) time = stepped
            }
        }
    }
    val matrix = remember { Matrix() }
    Canvas(modifier.background(Ink.Black)) {
        val img = imageShader ?: return@Canvas
        val bmp = bitmap ?: return@Canvas
        // Crop to fill, centred.
        val scale = max(size.width / bmp.width, size.height / bmp.height)
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate((size.width - bmp.width * scale) / 2f, (size.height - bmp.height * scale) / 2f)
        img.setLocalMatrix(matrix)
        shader.setInputShader("image", img)
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", time)
        // Developing, a print comes up overexposed, and settles.
        val developed = develop?.invoke()?.coerceIn(0f, 1f) ?: 0f
        shader.setFloatUniform("exposure", 1f + 0.25f * developed)
        shader.setFloatUniform("develop", developed)
        when (treatment) {
            is ArtTreatment.Monochrome -> {
                shader.setFloatUniform("contrast", treatment.contrast)
                shader.setFloatUniform("grain", treatment.grain)
            }
            is ArtTreatment.Ascii -> {
                val a = atlas ?: return@Canvas
                val cw = treatment.cellDp * density
                shader.setFloatUniform("contrast", treatment.contrast)
                shader.setFloatUniform("grain", 0.4f)
                shader.setInputShader("glyphs", glyphShader!!)
                shader.setFloatUniform("cell", cw, cw * a.glyphHeight / a.glyphWidth)
                shader.setFloatUniform("glyphPx", a.glyphWidth.toFloat(), a.glyphHeight.toFloat())
                shader.setFloatUniform("glyphCount", a.count.toFloat())
                shader.setFloatUniform("floorLevel", 0.06f)
            }
            is ArtTreatment.Halftone -> {
                shader.setFloatUniform("contrast", treatment.contrast)
                shader.setFloatUniform("grain", 0.3f)
                shader.setFloatUniform("dotPx", treatment.dotDp * density)
                shader.setFloatUniform("angle", treatment.angle)
            }
            is ArtTreatment.Dither -> {
                shader.setFloatUniform("contrast", treatment.contrast)
                shader.setFloatUniform("grain", 0f)
                shader.setFloatUniform("dotPx", treatment.dotDp * density)
            }
            ArtTreatment.Original -> {
                shader.setFloatUniform("contrast", 1f)
                // Only while developing: settled, it's the plain picture.
                shader.setFloatUniform("grain", 0.25f * developed)
            }
        }
        drawRect(ShaderBrush(shader))
    }
}

private const val GrainRate = 24f

private val shaders = HashMap<kotlin.reflect.KClass<out ArtTreatment>, RuntimeShader>()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun sharedShader(treatment: ArtTreatment): RuntimeShader = shaders.getOrPut(treatment::class) {
    RuntimeShader(
        when (treatment) {
            is ArtTreatment.Ascii -> Shaders.ASCII
            is ArtTreatment.Halftone -> Shaders.HALFTONE
            is ArtTreatment.Dither -> Shaders.DITHER
            ArtTreatment.Original -> Shaders.COLOR
            else -> Shaders.MONOCHROME
        },
    )
}

/** The picture at [url], decoded in software (shaders sample it), from memory on the first frame when it's there. */
@Composable
fun rememberArtBitmap(url: String?, sizePx: Int): ImageBitmap? = rememberPicture(url, sizePx, software = true)

/** The print as a colour filter, for whatever isn't drawn through [TreatedArtwork]. */
val PrintFilter: ColorFilter = monoFilter(1.12f)

/** Grey, with [contrast] about the middle: the monochrome treatment as a colour filter. */
private fun monoFilter(contrast: Float): ColorFilter {
    val c = contrast
    val o = (1f - c) * 128f
    val r = 0.2126f * c
    val g = 0.7152f * c
    val b = 0.0722f * c
    return ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(r, g, b, 0f, o, r, g, b, 0f, o, r, g, b, 0f, o, 0f, 0f, 0f, 1f, 0f)))
}
