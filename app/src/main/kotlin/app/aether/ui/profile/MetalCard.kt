package app.aether.ui.profile

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.Pictures
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.art.glitterMetal
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.LightCross
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Glide
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Sans
import app.aether.core.designsystem.theme.Script
import app.aether.core.designsystem.theme.Serif
import app.aether.core.model.Artwork
import app.aether.core.model.withoutEmoji
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What goes on the card. */
class CardData(
    val name: String,
    val persona: String,
    val since: String?,
    val artists: List<ArtistStat>,
    val faces: List<Face?>,
    val hours: String,
    val plays: Int,
    val artistCount: Int,
    val bestStreak: Int,
    val song: String?,
    val genre: String?,
)

/** The picture is always this many pixels, whatever phone it's made on: a story's own size. */
private const val CanvasWidth = 1080
private const val CanvasHeight = 1920
private val CanvasDensity = Density(2.625f, 1f)
private val CanvasWidthDp = 411.43.dp
private val CanvasHeightDp = 731.43.dp

@Stable
class CardExport(private val scope: CoroutineScope) {
    var data by mutableStateOf<CardData?>(null)
        private set

    fun open(card: CardData) {
        data = card
    }

    fun close() {
        data = null
    }

    internal val launch: CoroutineScope get() = scope
}

private val Rise = FluidSpring(0.6f, 0.86f)
private val Settle = FluidSpring(0.5f, 0.75f)

/** Where the light rests, and so how the card is saved. */
private const val RestX = 0.3f
private const val RestY = 0.26f

/**
 * The card, over everything, on black: it rises, shines under the finger, and is made into a picture
 * of a story's size, saved to the phone and handed to Android's share sheet. Nothing leaves the phone
 * unless the listener sends it from there.
 */
@Composable
fun MetalCardOverlay(export: CardExport, onSaved: (String) -> Unit) {
    val data = export.data ?: return
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val rise = remember { Glide(if (reduce) 1f else 0f) }
    val lightX = remember { Glide(RestX) }
    val lightY = remember { Glide(RestY) }
    var time by remember { mutableFloatStateOf(0f) }
    var saved by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(true) }
    BackHandler { export.close() }

    LaunchedEffect(data) {
        launch { rise.to(1f, Rise) }
        if (!reduce) launch {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time = (it - start) / 1e9f }
        }
    }

    suspend fun make() {
        busy = true
        val image = layer.toImageBitmap()
        val uri = withContext(Dispatchers.IO) {
            val bitmap = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            saveToPhone(context, bitmap)
            shareFile(context, bitmap).also { bitmap.recycle() }
        }
        saved = uri
        busy = false
        onSaved("Saved to Pictures / AETHER")
        share(context, uri)
    }

    // Its artists' pictures first, so that nothing is missing from what's saved.
    LaunchedEffect(data) {
        withTimeoutOrNull(4_000) {
            data.faces.take(3).forEach { f -> f?.picture?.url(PortraitPx)?.let { Pictures.fetch(context, it, PortraitPx) } }
        }
        delay(1_100)
        make()
    }

    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 96.dp).navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            val fit = with(LocalDensity.current) { minOf(maxWidth.toPx() / CanvasWidth, maxHeight.toPx() / CanvasHeight) }
            Box(
                Modifier
                    .graphicsLayer {
                        val r = rise.value
                        scaleX = fit
                        scaleY = fit
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        translationY = (1f - r) * 500f
                        alpha = r.coerceIn(0f, 1f)
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                scope.launch { lightX.to(RestX, Settle) }
                                scope.launch { lightY.to(RestY, Settle) }
                            },
                        ) { change, drag ->
                            change.consume()
                            scope.launch { lightX.snap((lightX.value + drag.x / size.width).coerceIn(-0.2f, 1.2f)) }
                            scope.launch { lightY.snap((lightY.value + drag.y / size.height).coerceIn(-0.2f, 1.2f)) }
                        }
                    },
            ) {
                CompositionLocalProvider(LocalDensity provides CanvasDensity) {
                    Box(
                        Modifier
                            .requiredSize(CanvasWidthDp, CanvasHeightDp)
                            .drawWithContent {
                                layer.record { this@drawWithContent.drawContent() }
                                drawLayer(layer)
                            },
                    ) { CardCanvas(data, { lightX.value to lightY.value }, { time }) }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassCircle(Glyph.Close, "Close", { export.close() }, size = 54.dp, backdrop = null)
            GlassButton(if (busy) "Making it" else "Share", onClick = { saved?.let { share(context, it) } })
        }
    }
}

private const val PortraitPx = 320

private val FoilLight = listOf(Color(0xFFFFFFFF), Color(0xFFDDE0E5), Color(0xFFFFFFFF), Color(0xFFC4C8CF), Color(0xFFFFFFFF))
private val Foil = Brush.linearGradient(FoilLight)

/** The edge a stamped letter has: a hard line under it, not a soft shadow, which smudged the thin strokes. */
private val Struck = Shadow(Color.Black.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(0f, 1.6f), 0.6f)

/** What YouTube adds to a title that isn't the song's name. */
private val TitleNoise = Regex("""\s*[(\[][^)\]]*(official|lyric|video|audio|visuali[sz]er)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

private fun foil(family: androidx.compose.ui.text.font.FontFamily, size: Float, line: Float, spacing: Float = 0f, weight: FontWeight = FontWeight.W400, numbers: Boolean = false) = TextStyle(
    brush = Foil,
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.em,
    shadow = Struck,
    fontFeatureSettings = if (numbers) "lnum" else null,
)

@Composable
private fun Foiled(text: String, style: TextStyle, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, lines: Int = 1) {
    BasicText(text.withoutEmoji(), modifier, style = style.copy(textAlign = align), maxLines = lines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

@Composable
private fun CardCanvas(data: CardData, light: () -> Pair<Float, Float>, time: () -> Float) {
    val cardShape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxSize().background(Ink.Black), contentAlignment = Alignment.Center) {
        // The card's glow on the black it lies on.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.11f), Color.Transparent), radius = 900f)))
        Box(
            Modifier
                .size(356.dp, 644.dp)
                .clip(cardShape)
                .glitterMetal(light, time)
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.5f))), cardShape),
        ) { CardFace(data) }
    }
}

@Composable
private fun CardFace(data: CardData) {
    val stamp = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)).uppercase() }
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LightCross(size = 34.dp)
        Foiled("Aether", foil(Script, 42f, 44f))
        Spacer(Modifier.height(8.dp))
        Foiled(data.name, foil(if (data.name == data.name.uppercase()) Serif else Script, if (data.name == data.name.uppercase()) 28f else 40f, 44f), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Foiled(listOfNotNull(data.persona, data.since?.let { "since $it" }).joinToString("  ·  ").uppercase(), foil(Sans, 8.5f, 13f, 0.18f, FontWeight.W600), align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        Spacer(Modifier.height(14.dp))
        Rule()
        Spacer(Modifier.height(12.dp))
        Foiled("MOST PLAYED", foil(Sans, 9f, 12f, 0.26f, FontWeight.W600), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
            data.faces.take(3).forEach { Portrait(it?.picture ?: Artwork.None) }
        }
        Spacer(Modifier.height(10.dp))
        data.artists.take(4).forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp), verticalAlignment = Alignment.CenterVertically) {
                Foiled("${i + 1}", foil(Serif, 15f, 20f, numbers = true), Modifier.width(22.dp))
                Foiled(a.name.uppercase(), foil(Sans, 12.5f, 18f, 0.08f, FontWeight.W600), Modifier.weight(1f))
                Foiled("${a.plays}", foil(Serif, 15f, 20f, numbers = true))
            }
        }
        Spacer(Modifier.height(12.dp))
        Rule()
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Figure(data.hours, "of music", Modifier.weight(1f))
            Figure("${data.plays}", "plays", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Figure("${data.artistCount}", "artists", Modifier.weight(1f))
            Figure("${data.bestStreak}", if (data.bestStreak == 1) "day streak" else "days in a row", Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        data.song?.let {
            Foiled("ON REPEAT", foil(Sans, 8.5f, 11f, 0.26f, FontWeight.W600), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Foiled(it.replace(TitleNoise, "").trim(), foil(Serif, 19f, 25f), align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        data.genre?.let { Foiled(it.uppercase(), foil(Sans, 8.5f, 12f, 0.2f, FontWeight.W600), align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
        Spacer(Modifier.height(8.dp))
        Foiled(stamp, foil(Sans, 7.5f, 10f, 0.3f, FontWeight.W600), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(0.75.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent))))
}

@Composable
private fun Portrait(picture: Artwork) {
    if (picture.isEmpty) return
    TreatedArtwork(
        picture,
        ArtTreatment.Monochrome(contrast = 1.2f, grain = 0f),
        Modifier.size(52.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
        sizeHintPx = PortraitPx,
    )
}

@Composable
private fun Figure(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Foiled(value, foil(Serif, 30f, 35f, numbers = true), align = TextAlign.Center)
        Foiled(label.uppercase(), foil(Sans, 8.5f, 11f, 0.2f, FontWeight.W600), align = TextAlign.Center)
    }
}

/** Into the phone's Pictures, where the gallery finds it. From Android 10 this needs no permission. */
private fun saveToPhone(context: Context, bitmap: Bitmap) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "AETHER card ${LocalDate.now()} ${System.currentTimeMillis() % 100000}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AETHER")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
}

/** A copy in the app's own cache to hand to whichever app the listener shares with. */
private fun shareFile(context: Context, bitmap: Bitmap): Uri {
    val folder = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(folder, "AETHER card.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("AETHER card", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share your card").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
