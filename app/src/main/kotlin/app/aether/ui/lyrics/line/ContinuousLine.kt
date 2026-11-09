// Ported from PODIUM (feature/nowplaying/line, GPL-3.0). Its comments still cite PODIUM's design notes (D-81, LYRICS_ARCHITECTURE.md).
package app.aether.ui.lyrics.line

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.aether.core.designsystem.glass.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lyrics as one line: a white line writes the words by hand on black, flows from each phrase's last
 * letter into the next, and draws instrumental line art between them, a single drawing the display
 * follows. The drawing is a function of the playback position; a seek makes the pen retrace its route
 * to the new place, never a new drawing. It's prepared off the main thread for each song and display
 * shape; each frame only maps the position to a cursor and draws what's prepared. Time comes from a
 * [LyricClock]: the display's own frame times, leaning onto [positionNow], so the pen moves the same
 * distance every frame. PODIUM's swell with the bass isn't here: it needs a meter in the audio's path.
 * Music never depends on this: it reads the position, nothing else.
 */
@Composable
internal fun ContinuousLine(
    positionNow: () -> Long,
    script: LineScript,
    trackKey: String,
    advancing: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReduceMotion.current
    val density = LocalDensity.current.density
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val aspect = if (constraints.maxWidth > 0) constraints.maxHeight.toFloat() / constraints.maxWidth else 1f
        // Rounded so a resize by a pixel or two doesn't rebuild the drawing.
        val shape = (aspect * 50f).toInt() / 50f
        val outlines = remember { OutlineGlyphs() }
        val composition by produceState<LineComposition?>(null, script, trackKey, shape) {
            value = null
            value = withContext(Dispatchers.Default) {
                LineComposition(script, LineComposition.seedOf(trackKey), shape, { cp -> LineHand.glyph(cp) ?: outlines.glyph(cp) }, outlines::word)
            }
        }
        val current = composition ?: return@BoxWithConstraints
        val painter = remember(density) { LinePainter(density) }
        val frame = remember(current) { LineFrame() }
        val playhead = remember(current) { LinePlayhead() }
        val clock = remember(current) { LyricClock() }
        var tick by remember { mutableLongStateOf(0L) }
        val isAdvancing by rememberUpdatedState(advancing)
        val isReduced by rememberUpdatedState(reduced)
        val position by rememberUpdatedState(positionNow)
        LaunchedEffect(current) {
            var first = true
            var prepared = -1
            while (isActive) {
                val nanos = withFrameNanos { it }
                val at = clock.update(nanos, position(), isAdvancing)
                // A seek is what the clock couldn't explain by time passing.
                playhead.update(current.cursorAt(at), nanos / 1_000_000, clock.jumped && !first, isReduced)
                current.frameAt(playhead.shown, isReduced, frame)
                first = false
                tick++
                // Prepare the next sections' routes and their paths before the pen reaches them (once per section).
                if (frame.section != prepared) {
                    prepared = frame.section
                    val next = prepared + 1..minOf(prepared + 2, current.sections.lastIndex)
                    launch(Dispatchers.Default) { for (i in next) painter.prepare(current.geometry(i).path) }
                }
                // Paused and still: look for a seek a few times a second instead of every frame.
                if (!isAdvancing && !playhead.travelling) delay(PAUSED_POLL_MS)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            tick // read in the draw phase: a new frame redraws without recomposing
            drawRect(LineInk.Paper)
            drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, current, frame, reduced) }
        }
    }
}

/**
 * Characters the hand doesn't write (another script, a symbol), traced from the system font's
 * outline so the pen still draws them as line (§12.2). Slanted like the hand, sized to its
 * x-height, cached; thread-safe (the composition is prepared off the main thread).
 */
internal class OutlineGlyphs {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = TEXT_SIZE
    }
    private val path = Path()
    private val cache = HashMap<Int, Glyph?>()
    private val words = HashMap<String, Glyph?>()

    fun glyph(codePoint: Int): Glyph? = synchronized(this) {
        if (cache.containsKey(codePoint)) return cache[codePoint]
        build(codePoint).also { cache[codePoint] = it }
    }

    /**
     * A word with letters the hand doesn't write, traced whole; null for a word it does. Joined
     * scripts only take their right shapes as words: traced one by one, Urdu's letters came out
     * unjoined and backwards, and Hindi's lost their vowel signs' places. Upright, as the script's own
     * forms are, and gone through in reading order.
     */
    fun word(word: String): Glyph? = synchronized(this) {
        if (handWrites(word)) return null
        if (words.containsKey(word)) return words[word]
        buildWord(word).also { words[word] = it }
    }

    private fun handWrites(word: String): Boolean {
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (Character.isLetter(cp) && LineHand.glyph(cp) == null) return false
            i += Character.charCount(cp)
        }
        return true
    }

    private fun buildWord(word: String): Glyph? {
        path.reset()
        paint.getTextPath(word, 0, word.length, 0f, 0f, path)
        // Joined letters overlap: as one outline, the pen doesn't draw the seams between them.
        val whole = Path()
        val traced = contours(if (whole.op(path, Path.Op.UNION)) whole else path, slant = 0f)
        val ordered = if (LinePhraseSetter.isRightToLeft(word)) traced.sortedByDescending { edge(it, right = true) } else traced.sortedBy { edge(it, right = false) }
        return Glyph.traced(ordered, (paint.measureText(word) / 100f).coerceAtLeast(0.2f))
    }

    /** The contour's furthest x on one side (x and y alternate in it). */
    private fun edge(contour: FloatArray, right: Boolean): Float {
        var at = contour[0]
        for (i in contour.indices step 2) at = if (right) maxOf(at, contour[i]) else minOf(at, contour[i])
        return at
    }

    private fun build(codePoint: Int): Glyph? {
        if (Character.isWhitespace(codePoint) || Character.getType(codePoint) == Character.CONTROL.toInt()) return null
        val text = String(Character.toChars(codePoint))
        path.reset()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val advance = paint.measureText(text) / 100f
        return Glyph.traced(contours(path, SLANT), advance.coerceAtLeast(0.2f))
    }

    private fun contours(path: Path, slant: Float): List<FloatArray> {
        val points = path.approximate(0.6f)
        val contours = ArrayList<FloatArray>()
        val current = FloatList(64)
        var lastFraction = -1f
        var i = 0
        while (i + 2 < points.size) {
            val fraction = points[i]
            // Two points with the same fraction: the path moves to a new contour.
            if (fraction == lastFraction && current.size > 0) {
                if (current.size >= 4) contours += current.toArray()
                current.clear()
            }
            val x = points[i + 1]
            val y = points[i + 2]
            current.add((x - y * slant) / 100f, y / 100f)
            lastFraction = fraction
            i += 3
        }
        if (current.size >= 4) contours += current.toArray()
        return contours
    }

    private companion object {
        /** Sized so the system font's x-height meets the hand's (half an em). */
        const val TEXT_SIZE = 92f
        const val SLANT = 0.16f
    }
}

private const val PAUSED_POLL_MS = 250L
