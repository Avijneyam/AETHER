package app.aether.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.GlassSegments
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.EqKind
import app.aether.core.model.EqMath
import app.aether.core.model.EqProfile
import app.aether.core.model.EqTunings
import app.aether.ui.LocalActions
import app.aether.ui.common.Colophon
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.rememberPageListState
import app.aether.ui.detail.BackLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

private const val BandKey = "band"
private const val BandIndex = 2

/** How many places the curve is known at. */
private const val Points = 96

/** The drawing's top and bottom, either side of nothing changed. */
private const val RangeDb = 12f

private val BandHeight = 186.dp
private val Reshape = FluidSpring(0.55f, 0.82f)

private const val Notice =
    "Keep your headphones on their default sound, and turn off every other equaliser on this phone. Everything here assumes nothing else is shaping the music."

/** The curve as it stands, each point on a spring of its own, so that a change of mind keeps its speed. */
@Stable
private class Drawn {
    val at = FloatArray(Points)
    val speed = FloatArray(Points)
    var frame by mutableIntStateOf(0)
}

/**
 * The equaliser: its curve pinned over the list, and the list of what it can be. Tunings of our own,
 * profiles brought in from elsewhere, and headphones by name, each its model's published measurement
 * corrected to one reference response.
 */
@Composable
fun EqualiserScreen() {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val eq = container.equaliser
    val on by eq.on.collectAsStateWithLifecycle()
    val chosen by eq.chosen.collectAsStateWithLifecycle()
    val imported by eq.imported.collectAsStateWithLifecycle()
    val headphones by eq.headphones.collectAsStateWithLifecycle()
    val brands = remember(headphones) { headphones.groupBy { it.group.orEmpty() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberPageListState()
    val density = LocalDensity.current
    val status = WindowInsets.statusBars.getTop(density)
    val pinAt = status + with(density) { 64.dp.toPx() }

    val importing = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val made = withContext(Dispatchers.IO) { readFile(context, uri)?.let { (name, text) -> eq.import(name, text) } }
            actions.keep.note(if (made != null) "Imported ${made.name}" else "That file isn't an equaliser profile")
        }
    }
    val choose: (EqProfile) -> Unit = { p ->
        eq.choose(p.id)
        if (p.kind == EqKind.Headphones) actions.keep.note("Keep your headphones on their default sound")
    }

    fun bandAt(): Float? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == BandKey }?.offset?.toFloat()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item { BackLine() }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge + 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("Equaliser", Type.name("Equaliser"), align = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Txt(Notice, Type.Aside, color = Ink.Grey, align = TextAlign.Center)
                    Spacer(Modifier.height(22.dp))
                }
            }
            item(key = BandKey) { Spacer(Modifier.height(BandHeight)) }
            item(key = "tunings") { Head("Tunings") }
            items(EqTunings.All, key = { it.id }) { p -> ProfileRow(p, p.id == chosen.id && on) { choose(p) } }
            item(key = "own") {
                Column {
                    Head("Your own", "A ParametricEQ or GraphicEQ file: AutoEq, squig.link, Equalizer APO, Wavelet and others write them.")
                    GlassPill(
                        "Import a profile",
                        icon = Glyph.Plus,
                        onClick = { importing.launch(arrayOf("text/*", "application/*")) },
                        modifier = Modifier.padding(start = Space.edge, end = Space.edge, top = 4.dp, bottom = 14.dp),
                    )
                }
            }
            items(imported, key = { it.id }) { p ->
                ProfileRow(p, p.id == chosen.id && on, onRemove = { eq.remove(p.id) }) { choose(p) }
            }
            item(key = "phones") {
                Head("Headphones", "Choose the pair you're wearing. Each is corrected, from its published measurements, to the same reference response.")
            }
            brands.forEach { (brand, models) ->
                item(key = "brand:$brand") { Txt(brand, Type.Title, Modifier.padding(start = Space.edge, end = Space.edge, top = 24.dp, bottom = 8.dp), color = Ink.Grey) }
                items(models, key = { it.id }) { p -> ProfileRow(p, p.id == chosen.id && on) { choose(p) } }
            }
            item(key = "foot") {
                Txt(
                    "Off, or on Flat, the audio passes through untouched. With a tuning on it's no longer bit-perfect, and Now Playing stops saying so.",
                    Type.Aside,
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge + 12.dp).padding(top = 40.dp),
                    color = Ink.Dim,
                    align = TextAlign.Center,
                )
            }
            item(key = "colophon") { Colophon() }
        }

        // What the band passes under, once it's pinned: the page's top goes dark as it arrives.
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { pinAt.toDp() })
                .graphicsLayer {
                    val at = bandAt()
                    val near = 56.dp.toPx()
                    alpha = if (at == null) (if (list.firstVisibleItemIndex >= BandIndex) 1f else 0f) else ((pinAt + near - at) / near).coerceIn(0f, 1f)
                }
                .background(Ink.Black),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val at = bandAt()
                    alpha = if (at == null && list.firstVisibleItemIndex < BandIndex) 0f else 1f
                    translationY = (at ?: pinAt).coerceAtLeast(pinAt)
                },
        ) {
            Column(Modifier.fillMaxWidth().height(BandHeight).background(Ink.Black).padding(horizontal = Space.edge)) {
                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Txt(listOfNotNull(chosen.group, chosen.name).joinToString(" "), Type.TitleSmall, maxLines = 1)
                        chosen.detail?.let { Txt(it, Type.Meta, color = Ink.Grey, maxLines = 1) }
                    }
                    Spacer(Modifier.width(14.dp))
                    GlassSegments(listOf("Off", "On"), if (on) 1 else 0, { eq.setOn(it == 1) })
                }
                Spacer(Modifier.height(8.dp))
                Curve(chosen, on, Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(6.dp))
            }
            Box(Modifier.fillMaxWidth().height(22.dp).background(Brush.verticalGradient(listOf(Ink.Black, Color.Transparent))))
        }
    }
}

@Composable
private fun Head(title: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge).padding(top = 36.dp, bottom = 12.dp)) {
        Txt(title, Type.Display)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Txt(detail, Type.Meta, color = Ink.Grey)
        }
    }
}

@Composable
private fun ProfileRow(profile: EqProfile, chosen: Boolean, onRemove: (() -> Unit)? = null, onChoose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                selected = chosen
            }
            .quietClick(onChoose)
            .drawBehind {
                val inset = Space.edge.toPx()
                drawLine(Ink.White.copy(alpha = 0.16f), Offset(inset, 0f), Offset(size.width - inset, 0f), strokeWidth = 0.5.dp.toPx())
            }
            .heightIn(min = 62.dp)
            .padding(horizontal = Space.edge, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(profile.name, Type.TitleSmall, maxLines = 1)
            profile.detail?.let { Txt(it, Type.Meta, color = Ink.Grey, maxLines = 1) }
        }
        if (onRemove != null) IconAction(Glyph.Trash, "Remove ${profile.name}", onRemove, size = 15.dp, tint = Ink.Grey)
        if (chosen) {
            Spacer(Modifier.width(10.dp))
            Icon(Glyph.Check, size = 16.dp, color = Ink.White)
        }
    }
}

/** What the sound is being asked to do, on a logarithmic width: the same sums the filters run on. */
@Composable
private fun Curve(profile: EqProfile, on: Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val drawn = remember { Drawn() }
    val target = remember(profile.id, on) { if (on) EqMath.curve(profile.filters, Points) else FloatArray(Points) }
    LaunchedEffect(target) {
        if (reduce) {
            target.copyInto(drawn.at)
            drawn.speed.fill(0f)
            drawn.frame++
            return@LaunchedEffect
        }
        var last = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (last < 0L) {
                last = now
                continue
            }
            var left = ((now - last) / 1e9f).coerceAtMost(1f / 30f)
            last = now
            while (left > 0f) {
                val h = min(left, 1f / 240f)
                for (i in 0 until Points) {
                    drawn.speed[i] += (-Reshape.stiffness * (drawn.at[i] - target[i]) - Reshape.friction * drawn.speed[i]) * h
                    drawn.at[i] += drawn.speed[i] * h
                }
                left -= h
            }
            drawn.frame++
            if ((0 until Points).all { abs(drawn.at[it] - target[it]) < 0.01f && abs(drawn.speed[it]) < 0.05f }) {
                target.copyInto(drawn.at)
                drawn.speed.fill(0f)
                drawn.frame++
                break
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val label = remember { Type.Label.copy(fontSize = 8.sp, color = Ink.Dim) }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") drawn.frame
        val foot = 14.dp.toPx()
        val h = size.height - foot
        val mid = h / 2f
        val perDb = mid / RangeDb
        val hair = 0.5.dp.toPx()
        drawLine(Ink.White.copy(alpha = 0.3f), Offset(0f, mid), Offset(size.width, mid), hair)
        for (db in listOf(-6f, 6f)) drawLine(Ink.White.copy(alpha = 0.09f), Offset(0f, mid - db * perDb), Offset(size.width, mid - db * perDb), hair)
        for ((hz, name) in Marks) {
            val x = EqMath.across(hz) * size.width
            drawLine(Ink.White.copy(alpha = 0.09f), Offset(x, 0f), Offset(x, h), hair)
            val text = measurer.measure(name, label)
            drawText(text, topLeft = Offset(x - text.size.width / 2f, size.height - text.size.height))
        }
        val line = Path()
        val fill = Path().apply { moveTo(0f, mid) }
        for (i in 0 until Points) {
            val x = i / (Points - 1f) * size.width
            val y = mid - drawn.at[i].coerceIn(-RangeDb, RangeDb) * perDb
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(size.width, mid)
        fill.close()
        drawPath(fill, Brush.verticalGradient(0f to Ink.White.copy(alpha = 0.2f), 0.5f to Ink.White.copy(alpha = 0.03f), 1f to Ink.White.copy(alpha = 0.2f), startY = 0f, endY = h))
        drawPath(line, if (on) Ink.White else Ink.Dim, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private val Marks = listOf(100f to "100", 1_000f to "1K", 10_000f to "10K")

/** The file's name without the words these files carry, and its text, up to a size no profile reaches. */
private fun readFile(context: Context, uri: Uri): Pair<String, String>? = runCatching {
    val resolver = context.contentResolver
    val shown = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    val name = shown.orEmpty().substringBeforeLast('.').replace(Regex("""(?i)\s*(parametric|graphic|fixedband)\s*eq$"""), "").trim().take(60)
    val bytes = ByteArray(512 * 1024)
    var length = 0
    resolver.openInputStream(uri)?.use { input ->
        while (length < bytes.size) {
            val read = input.read(bytes, length, bytes.size - length)
            if (read < 0) break
            length += read
        }
    } ?: return null
    name to String(bytes, 0, length)
}.getOrNull()
