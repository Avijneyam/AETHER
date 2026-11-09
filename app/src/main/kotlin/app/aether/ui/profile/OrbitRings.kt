// After Orbit Ring Stats by ham7a311 (21st.dev, MIT), rebuilt for Compose and for a finger.
package app.aether.ui.profile

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.SweepGradient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.aether.core.designsystem.R
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Serif
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One gauge: [value] out of a hundred, and the words that go round it. */
class Orbit(val label: String, val value: Int, val note: String?, val orbit: String)

private const val FastDegrees = 7f
private const val SlowDegrees = 0.8f

private val NumberStyle = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 40.sp, lineHeight = 46.sp, fontFeatureSettings = "lnum, tnum", textAlign = TextAlign.Center)
private val UnitStyle = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 20.sp, lineHeight = 26.sp, fontFeatureSettings = "lnum")

/**
 * Ring gauges, two to a row. When they come into view each arc sweeps to its value and the number
 * rolls in, digit by digit, like a counter. The metric's words orbit the ring; held under a finger
 * they slow almost to a stop, the centre floods with white and the number inverts exactly as far as
 * the flood has reached.
 */
@Composable
fun OrbitRings(rings: List<Orbit>, onScreen: () -> Boolean, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    var on by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) {
        snapshotFlow { onScreen() }.first { it }
        on = true
    }
    val angles = remember(rings.size) { FloatArray(rings.size) { it * 47f } }
    val speeds = remember(rings.size) { FloatArray(rings.size) { FastDegrees } }
    val tick = remember { mutableLongStateOf(0L) }
    var hot by remember { mutableIntStateOf(-1) }
    val held by remember { derivedStateOf { hot } }
    // One clock for every ring, each easing toward its own speed, and resting while it can't be seen.
    if (!reduce) LaunchedEffect(rings.size) {
        while (true) {
            snapshotFlow { onScreen() }.first { it }
            var last = -1L
            while (onScreen()) {
                val now = withFrameNanos { it }
                val dt = if (last < 0L) 0f else min(0.05f, (now - last) / 1e9f)
                last = now
                for (i in rings.indices) {
                    val want = if (held == i) SlowDegrees else FastDegrees
                    speeds[i] += (want - speeds[i]) * min(1f, dt * 4f)
                    angles[i] = (angles[i] + speeds[i] * dt) % 360f
                }
                tick.longValue++
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = Space.edge), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        rings.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEachIndexed { column, ring ->
                    val index = row * 2 + column
                    Ring(
                        ring,
                        on = on,
                        stagger = index,
                        angle = { tick.longValue; angles[index] },
                        onHold = { down -> hot = if (down) index else -1 },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Ring(ring: Orbit, on: Boolean, stagger: Int, angle: () -> Float, onHold: (Boolean) -> Unit, modifier: Modifier) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        if (on) {
            delay(stagger * 110L)
            started = true
        }
    }
    val fraction = (ring.value / 100f).coerceIn(0f, 1f)
    val sweep by animateFloatAsState(if (started) fraction else 0f, Motion.fluid(1.1f, 0.92f, 0.001f), label = "arc")
    var pressed by remember { mutableStateOf(false) }
    val flood by animateFloatAsState(if (pressed) 1f else 0f, Motion.fluid(0.55f, 1f, 0.002f), label = "flood")
    val floods by remember { derivedStateOf { flood > 0.002f } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        onHold(true)
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.id == down.id && it.pressed })
                        pressed = false
                        onHold(false)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            OrbitWords(ring.orbit, angle)
            Arc({ sweep }, angle)
            Figure(ring.value, started, Ink.White)
            if (floods) Box(
                Modifier.fillMaxSize().drawWithContent {
                    val radius = size.minDimension * FaceRadius * flood
                    val circle = Path().apply { addOval(Rect(center, radius)) }
                    clipPath(circle) {
                        drawRect(Ink.White)
                        this@drawWithContent.drawContent()
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Figure(ring.value, started, Ink.Black) }
        }
        Spacer(Modifier.height(10.dp))
        Txt(ring.label, Type.TitleSmall, align = TextAlign.Center, maxLines = 1)
        if (ring.note != null) Txt(ring.note, Type.Meta, color = Ink.Grey, align = TextAlign.Center, maxLines = 2)
    }
}

/** What's inside the ring, as a share of its size. */
private const val FaceRadius = 0.35f
private const val OrbitRadius = 0.465f
private const val ArcRadius = 0.40f

/** The words going round: drawn once along a circle, and only ever turned. */
@Composable
private fun OrbitWords(words: String, angle: () -> Float) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val typeface = remember { ResourcesCompat.getFont(context, R.font.montserrat) }
    Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle() }) {
        val radius = size.minDimension * OrbitRadius
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 8.2f * density
            color = Ink.Grey.toArgb()
        }
        val lap = (2 * Math.PI * radius).toFloat()
        val once = "${words.uppercase()}  ·  "
        val times = (lap / paint.measureText(once)).roundToInt().coerceAtLeast(1)
        val line = once.repeat(times)
        // Spread to go exactly once round, with no gap where it meets itself.
        paint.letterSpacing = (lap - paint.measureText(line)) / line.length / paint.textSize
        val path = AndroidPath().apply { addCircle(center.x, center.y, radius, AndroidPath.Direction.CW) }
        drawIntoCanvas { it.nativeCanvas.drawTextOnPath(line, path, 0f, 0f, paint) }
    }
}

/** The track, the arc whose light slowly turns, and a bright head where it has got to. */
@Composable
private fun Arc(sweep: () -> Float, angle: () -> Float) {
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * ArcRadius
            val line = 3.2.dp.toPx()
            val box = Offset(center.x - radius, center.y - radius)
            val whole = Size(radius * 2f, radius * 2f)
            val turning = SweepGradient(center.x, center.y, intArrayOf(Color.White.toArgb(), Color(0xFF6E6E6E).toArgb(), Color.White.toArgb()), null)
            val brush = ShaderBrush(turning)
            val turn = Matrix()
            onDrawBehind {
                drawCircle(Ink.White.copy(alpha = 0.1f), radius, center, style = Stroke(line))
                val s = sweep()
                if (s > 0.001f) {
                    turn.setRotate(angle() * 4f, center.x, center.y)
                    turning.setLocalMatrix(turn)
                    drawArc(brush, -90f, 360f * s, useCenter = false, topLeft = box, size = whole, style = Stroke(line, cap = StrokeCap.Round))
                    val head = Math.toRadians((360.0 * s) - 90.0)
                    drawCircle(Ink.White, line * 1.15f, Offset(center.x + radius * cos(head).toFloat(), center.y + radius * sin(head).toFloat()))
                }
            }
        },
    )
}

/** The number, each digit a reel rolling to its place, the percent sign beside it. */
@Composable
private fun Figure(value: Int, started: Boolean, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val digits = value.toString()
        digits.forEachIndexed { i, c -> Reel(c.digitToInt(), started, digits.length - i, color) }
        Txt("%", UnitStyle.copy(letterSpacing = 0.em), color = color.copy(alpha = 0.7f), modifier = Modifier.padding(start = 2.dp, top = 8.dp))
    }
}

@Composable
private fun Reel(digit: Int, started: Boolean, place: Int, color: Color) {
    val at by animateFloatAsState(if (started) digit.toFloat() else 0f, Motion.fluid(0.8f + place * 0.16f, 0.9f, 0.002f), label = "reel")
    var line by remember { mutableIntStateOf(0) }
    val tall = with(LocalDensity.current) { NumberStyle.lineHeight.toDp() }
    Box(Modifier.height(tall).clipToBounds()) {
        // Its own height, not the window's: the ten digits stand one above the other past the edge.
        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { line = it.height / 10 }.graphicsLayer { translationY = -at * line }) {
            for (n in 0..9) Txt(n.toString(), NumberStyle, color = color)
        }
    }
}
