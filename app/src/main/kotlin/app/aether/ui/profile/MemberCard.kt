package app.aether.ui.profile

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.aether.core.data.Library
import app.aether.core.data.Played
import app.aether.core.data.Settings
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.Pictures
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.art.cardMetal
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.GlassSegments
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.clock
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Glide
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Sans
import app.aether.core.designsystem.theme.Script
import app.aether.core.model.Artwork
import app.aether.core.model.Track
import app.aether.core.model.withoutEmoji
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What goes on a Profile's card. */
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

/** What the listener's own playing says about a song; only ever what's in their history. */
class SongFacts(val plays: Int, val rank: Int?, val first: String?, val holder: String?)

/** What a card can be of. */
sealed interface CardContent {
    class Profile(val data: CardData) : CardContent
    class Song(val track: Track, val facts: SongFacts) : CardContent
}

private fun factsOf(track: Track, history: List<Played>, holder: String?): SongFacts {
    val counts = history.groupingBy { it.track.id }.eachCount()
    val plays = counts[track.id] ?: 0
    val first = history.filter { it.track.id == track.id }.minOfOrNull { it.at }
    return SongFacts(
        plays = plays,
        rank = if (plays > 0) counts.values.count { it > plays } + 1 else null,
        first = first?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM yyyy")) },
        holder = holder?.takeIf { it.isNotBlank() },
    )
}

/** The picture is always this many pixels, whatever phone it's made on: a story's own size. */
private const val CanvasWidth = 1080
private const val CanvasHeight = 1920
private val CanvasDensity = Density(2.625f, 1f)
private val CanvasWidthDp = 411.43.dp
private val CanvasHeightDp = 731.43.dp

@Stable
class CardExport(private val scope: CoroutineScope, private val settings: Settings, private val library: Library) {
    var content by mutableStateOf<CardContent?>(null)
        private set

    /** Silver on black, or black on silver. Each new card takes the other finish from the last. */
    var silver by mutableStateOf(false)

    fun open(card: CardData) = show(CardContent.Profile(card))

    fun openSong(track: Track) {
        scope.launch {
            val history = library.history(StatsWindow).first()
            show(CardContent.Song(track, withContext(Dispatchers.Default) { factsOf(track, history, settings.profileName.value) }))
        }
    }

    private fun show(card: CardContent) {
        silver = settings.nextCardSilver()
        content = card
    }

    fun close() {
        content = null
    }

    internal val launch: CoroutineScope get() = scope
}

private val Rise = FluidSpring(0.6f, 0.86f)
private val Settle = FluidSpring(0.5f, 0.75f)

/** Where the light rests, and so how the card is saved. */
private const val RestX = 0.3f
private const val RestY = 0.26f

private const val PortraitPx = 480
private const val CoverPx = 1080

/**
 * The card, over everything: it rises, the light moves under the finger, and it's made into a picture
 * of a story's size, saved to the phone and handed to Android's share sheet. Nothing leaves the phone
 * unless the listener sends it from there. The finish can be changed before sending it again.
 */
@Composable
fun MetalCardOverlay(export: CardExport, onSaved: (String) -> Unit) {
    val content = export.content ?: return
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val rise = remember(content) { Glide(if (reduce) 1f else 0f) }
    val lightX = remember { Glide(RestX) }
    val lightY = remember { Glide(RestY) }
    var saved by remember(content) { mutableStateOf<Uri?>(null) }
    var busy by remember(content) { mutableStateOf(true) }
    BackHandler { export.close() }

    suspend fun make() {
        busy = true
        val image = layer.toImageBitmap()
        val uri = withContext(Dispatchers.IO) {
            val bitmap = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            saveToPhone(context, bitmap, fileName(content))
            shareFile(context, bitmap).also { bitmap.recycle() }
        }
        saved = uri
        busy = false
        onSaved("Saved to Pictures / AETHER")
        share(context, uri)
    }

    LaunchedEffect(content) { rise.to(1f, Rise) }
    // Its pictures first, so that nothing is missing from what's saved.
    LaunchedEffect(content) {
        withTimeoutOrNull(4_000) {
            when (content) {
                is CardContent.Profile -> content.data.faces.firstOrNull()?.picture?.url(PortraitPx)?.let { Pictures.fetch(context, it, PortraitPx) }
                is CardContent.Song -> Pictures.fetch(context, content.track.artwork.url(CoverPx), CoverPx)
            }
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
                        transformOrigin = TransformOrigin(0.5f, 0f)
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
                    ) { CardCanvas(content, export.silver) { lightX.value to lightY.value } }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassCircle(Glyph.Close, "Close", { export.close() }, size = 54.dp, backdrop = null)
            GlassSegments(listOf("Black", "Silver"), if (export.silver) 1 else 0, { pick ->
                val silver = pick == 1
                if (silver != export.silver) {
                    export.silver = silver
                    saved = null
                }
            })
            GlassButton(if (busy) "Making it" else "Share", onClick = {
                val ready = saved
                when {
                    busy -> Unit
                    ready != null -> share(context, ready)
                    else -> scope.launch { make() }
                }
            })
        }
    }
}

private fun fileName(content: CardContent): String = when (content) {
    is CardContent.Profile -> "AETHER card"
    is CardContent.Song -> "AETHER " + content.track.title.replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().take(40)
}

/** How words are cut into each finish: bright on the black, dark on the silver, each with the edge the metal gives it. */
private class Engraving(val ink: Brush, val quiet: Brush, val edge: Shadow, val rule: Color)

private val OnBlack = Engraving(
    ink = Brush.linearGradient(listOf(Color(0xFFF6F7F9), Color(0xFFC0C4CB), Color(0xFFF6F7F9), Color(0xFFAAAEB6), Color(0xFFF0F1F4))),
    quiet = Brush.linearGradient(listOf(Color(0xFFA6AAB2), Color(0xFF82868E))),
    edge = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1.4f), 0.5f),
    rule = Color.White.copy(alpha = 0.22f),
)

private val OnSilver = Engraving(
    ink = Brush.linearGradient(listOf(Color(0xFF101114), Color(0xFF34363B), Color(0xFF101114), Color(0xFF26282C), Color(0xFF101114))),
    quiet = Brush.linearGradient(listOf(Color(0xFF45484E), Color(0xFF5A5D63))),
    edge = Shadow(Color.White.copy(alpha = 0.6f), Offset(0f, 1.3f), 0.3f),
    rule = Color.Black.copy(alpha = 0.26f),
)

/** What the title of a song says that isn't its name. */
private val TitleNoise = Regex("""\s*[(\[][^)\]]*(official|lyric|video|audio|visuali[sz]er)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

private fun cleanTitle(title: String) = title.replace(TitleNoise, "").trim()

/** Capitals of the grotesk, cut into the metal: the only letters on a card but its logo. */
@Composable
private fun Caps(
    text: String,
    e: Engraving,
    size: Float,
    modifier: Modifier = Modifier,
    spacing: Float = 0.14f,
    weight: FontWeight = FontWeight.W600,
    quiet: Boolean = false,
    align: TextAlign = TextAlign.Start,
    lines: Int = 1,
) {
    BasicText(
        text.withoutEmoji().uppercase(),
        modifier,
        style = TextStyle(
            brush = if (quiet) e.quiet else e.ink,
            fontFamily = Sans,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = (size * 1.28f).sp,
            letterSpacing = spacing.em,
            shadow = e.edge,
            textAlign = align,
            fontFeatureSettings = "tnum",
        ),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Logo(e: Engraving, size: Float, modifier: Modifier = Modifier) {
    BasicText(
        "Aether",
        modifier,
        style = TextStyle(brush = e.ink, fontFamily = Script, fontWeight = FontWeight.W400, fontSize = size.sp, lineHeight = (size * 1.2f).sp, shadow = e.edge),
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun Rule(e: Engraving) {
    Box(Modifier.fillMaxWidth().height(0.75.dp).background(e.rule))
}

private val StageForBlack = Brush.radialGradient(listOf(Color(0xFFE6E8EC), Color(0xFF8E9197)), radius = 1500f)
private val StageForSilver = Brush.radialGradient(listOf(Color(0xFF1B1C1E), Color(0xFF000000)), radius = 1100f)

/** The picture: the card on the stage made for its finish, each the other's opposite. */
@Composable
private fun CardCanvas(content: CardContent, silver: Boolean, light: () -> Pair<Float, Float>) {
    val shape = RoundedCornerShape(26.dp)
    val e = if (silver) OnSilver else OnBlack
    val edge = if (silver) listOf(Color.White.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.18f), Color.White.copy(alpha = 0.5f))
    else listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.2f))
    Box(Modifier.fillMaxSize().background(if (silver) StageForSilver else StageForBlack), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(356.dp, 644.dp)
                .cardShadow(silver)
                .clip(shape)
                .cardMetal(silver, light)
                .border(1.dp, Brush.linearGradient(edge), shape),
        ) {
            when (content) {
                is CardContent.Profile -> ProfileFace(content.data, e)
                is CardContent.Song -> SongFace(content.track, content.facts, e)
            }
        }
    }
}

private fun Modifier.cardShadow(silver: Boolean): Modifier = drawWithCache {
    val radius = 26.dp.toPx()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        if (silver) setShadowLayer(120f, 0f, 0f, 0x38FFFFFF) else setShadowLayer(90f, 0f, 46f, 0xA0000000.toInt())
    }
    onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint) } }
}

@Composable
private fun ProfileFace(d: CardData, e: Engraving) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Logo(e, 40f)
            Spacer(Modifier.weight(1f))
            Caps(d.persona, e, 8.5f, spacing = 0.22f, quiet = true)
        }
        Spacer(Modifier.height(24.dp))
        d.artists.firstOrNull()?.let { first ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Medallion(d.faces.firstOrNull()?.picture ?: Artwork.None, e, 156.dp)
                Spacer(Modifier.height(14.dp))
                Caps("Most played", e, 8.5f, spacing = 0.32f, quiet = true)
                Spacer(Modifier.height(4.dp))
                Caps(first.name, e, 21f, spacing = 0.1f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(16.dp))
        Rule(e)
        Spacer(Modifier.height(10.dp))
        d.artists.drop(1).take(3).forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Caps("0${i + 2}", e, 10f, Modifier.width(32.dp), quiet = true)
                Caps(a.name, e, 11.5f, Modifier.weight(1f), spacing = 0.1f)
                Caps("${a.plays}", e, 11.5f, quiet = true)
            }
        }
        Spacer(Modifier.height(10.dp))
        Rule(e)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Measure(d.hours, "Of music", e, Modifier.weight(1f))
            Measure("${d.plays}", "Plays", e, Modifier.weight(1f))
            Measure("${d.artistCount}", "Artists", e, Modifier.weight(1f))
            Measure("${d.bestStreak}", "Best streak", e, Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                d.song?.let {
                    Caps("On repeat", e, 7.5f, spacing = 0.26f, quiet = true)
                    Spacer(Modifier.height(3.dp))
                    Caps(cleanTitle(it), e, 11f, spacing = 0.08f)
                }
            }
            Spacer(Modifier.width(14.dp))
            d.genre?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Caps("Your sound", e, 7.5f, spacing = 0.26f, quiet = true)
                    Spacer(Modifier.height(3.dp))
                    Caps(it, e, 11f, spacing = 0.08f)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Caps(d.name, e, 15f, Modifier.weight(1f), spacing = 0.16f)
            Spacer(Modifier.width(14.dp))
            d.since?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Caps("Member since", e, 7f, spacing = 0.26f, quiet = true)
                    Spacer(Modifier.height(3.dp))
                    Caps(it, e, 9.5f, spacing = 0.16f)
                }
            }
        }
    }
}

@Composable
private fun Measure(value: String, label: String, e: Engraving, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Caps(value, e, 17f, spacing = 0.03f, align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Caps(label, e, 6.5f, spacing = 0.16f, quiet = true, align = TextAlign.Center)
    }
}

/** A coin's edge round the first artist: two fine rings with a milled band between them. */
@Composable
private fun Medallion(picture: Artwork, e: Engraving, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = this.size.minDimension / 2f - 1.dp.toPx()
            val inner = outer - 7.dp.toPx()
            drawCircle(e.rule, outer, style = Stroke(0.9.dp.toPx()))
            drawCircle(e.rule, inner, style = Stroke(0.6.dp.toPx()))
            for (k in 0 until 96) rotate(k * 3.75f) {
                drawLine(e.rule, Offset(center.x, center.y - outer + 1.5.dp.toPx()), Offset(center.x, center.y - inner - 1.5.dp.toPx()), 0.6.dp.toPx())
            }
        }
        if (!picture.isEmpty) {
            TreatedArtwork(picture, ArtTreatment.Monochrome(contrast = 1.18f, grain = 0f), Modifier.size(size - 20.dp).clip(CircleShape), sizeHintPx = PortraitPx)
        } else {
            Canvas(Modifier.size(size * 0.34f)) {
                val long = this.size.height / 2f
                val wide = long * 0.62f
                val waist = long * 0.11f
                val star = Path().apply {
                    moveTo(center.x, center.y - long)
                    lineTo(center.x + waist, center.y - waist)
                    lineTo(center.x + wide, center.y)
                    lineTo(center.x + waist, center.y + waist)
                    lineTo(center.x, center.y + long)
                    lineTo(center.x - waist, center.y + waist)
                    lineTo(center.x - wide, center.y)
                    lineTo(center.x - waist, center.y - waist)
                    close()
                }
                drawPath(star, e.ink)
            }
        }
    }
}

@Composable
private fun SongFace(track: Track, facts: SongFacts, e: Engraving) {
    val title = cleanTitle(track.title)
    val titleSize = when {
        title.length <= 14 -> 30f
        title.length <= 26 -> 25f
        title.length <= 44 -> 20f
        else -> 17f
    }
    Column(Modifier.fillMaxSize()) {
        // The cover in its own colour, going into the metal, which shows through it as it fades.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.56f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) { TreatedArtwork(track.artwork, ArtTreatment.Cover, Modifier.fillMaxSize(), sizeHintPx = CoverPx) }
        Column(Modifier.fillMaxWidth().weight(1f).padding(start = 26.dp, end = 26.dp, bottom = 24.dp)) {
            Caps(title, e, titleSize, spacing = 0.05f, lines = 3)
            Spacer(Modifier.height(8.dp))
            Caps(track.artistLine, e, 11.5f, spacing = 0.18f, weight = FontWeight.W500, quiet = true)
            Spacer(Modifier.height(16.dp))
            Rule(e)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                track.durationMs?.let { Measure(clock(it), "Length", e, Modifier.weight(1f)) }
                track.year?.let { Measure("$it", "Year", e, Modifier.weight(1f)) }
                if (facts.plays > 0) Measure("${facts.plays}", "Your plays", e, Modifier.weight(1f))
                facts.rank?.let { Measure("N\u00B0 $it", "Your chart", e, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(14.dp))
            Rule(e)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    track.album?.title?.takeIf { it.isNotBlank() }?.let {
                        Caps("From the album", e, 7.5f, spacing = 0.26f, quiet = true)
                        Spacer(Modifier.height(3.dp))
                        Caps(cleanTitle(it), e, 11f, spacing = 0.08f)
                    }
                }
                facts.first?.let {
                    Spacer(Modifier.width(14.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Caps("First played", e, 7.5f, spacing = 0.26f, quiet = true)
                        Spacer(Modifier.height(3.dp))
                        Caps(it, e, 11f, spacing = 0.08f)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Caps(facts.holder.orEmpty(), e, 15f, Modifier.weight(1f), spacing = 0.16f)
                Spacer(Modifier.width(14.dp))
                Logo(e, 34f)
            }
        }
    }
}

/** Into the phone's Pictures, where the gallery finds it. From Android 10 this needs no permission. */
private fun saveToPhone(context: Context, bitmap: Bitmap, name: String) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name ${LocalDate.now()} ${System.currentTimeMillis() % 100000}.png")
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
