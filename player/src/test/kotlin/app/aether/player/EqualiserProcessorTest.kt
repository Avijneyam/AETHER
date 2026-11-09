package app.aether.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import app.aether.core.model.EqFilter
import app.aether.core.model.EqKind
import app.aether.core.model.EqProfile
import app.aether.core.model.EqShape
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The audio itself, through the real processor: a sine in, what comes out measured. */
@OptIn(UnstableApi::class)
class EqualiserProcessorTest {
    private val rate = 48_000

    private fun peak(hz: Float, db: Float) = EqProfile("t", "t", EqKind.Tuning, listOf(EqFilter(EqShape.Peak, hz, db, 1.41f)))

    private fun processor(encoding: Int): EqualiserProcessor = EqualiserProcessor().apply {
        configure(AudioProcessor.AudioFormat(rate, 1, encoding))
        flush()
    }

    private fun sine(hz: Double, amplitude: Float, frames: Int) = FloatArray(frames) { amplitude * sin(2 * PI * hz * it / rate).toFloat() }

    private fun run(p: EqualiserProcessor, samples: FloatArray): FloatArray {
        val input = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { input.putFloat(it) }
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(out.remaining()).also { out.get(it) }
    }

    /** The loudest of the last half: long after the filter has settled. */
    private fun level(samples: FloatArray) = samples.drop(samples.size / 2).maxOf { abs(it) }

    @Test
    fun withNothingChosenTheBytesAreUntouched() {
        val p = processor(C.ENCODING_PCM_16BIT)
        val bytes = ByteArray(4_000) { (it * 37 % 251).toByte() }
        val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        input.flip()
        p.queueInput(input)
        val out = p.output
        assertContentEquals(bytes, ByteArray(out.remaining()).also { out.get(it) })
    }

    @Test
    fun aPeakShapesTheFrequenciesItIsFor() {
        val p = processor(C.ENCODING_PCM_FLOAT)
        p.update(peak(1_000f, 6f))
        // A swap crossfades in: let it finish.
        run(p, sine(1_000.0, 0.5f, 4_800))
        // Everything comes down 6 dB for headroom, and 1 kHz goes back up by the filter's 6.
        assertEquals(0.5f, level(run(p, sine(1_000.0, 0.5f, 24_000))), 0.02f)
        assertEquals(0.25f, level(run(p, sine(100.0, 0.5f, 48_000))), 0.02f)
    }

    @Test
    fun aBoostNeverClips() {
        val p = processor(C.ENCODING_PCM_FLOAT)
        p.update(peak(1_000f, 12f))
        run(p, sine(1_000.0, 1f, 4_800))
        assertTrue(level(run(p, sine(1_000.0, 1f, 24_000))) <= 1.001f)
    }

    @Test
    fun turnedOffItGoesBackToTheFile() {
        val p = processor(C.ENCODING_PCM_FLOAT)
        p.update(peak(1_000f, 6f))
        run(p, sine(1_000.0, 0.5f, 4_800))
        p.update(null)
        run(p, sine(1_000.0, 0.5f, 4_800))
        val clean = sine(440.0, 0.5f, 4_800)
        assertContentEquals(clean, run(p, clean))
    }
}
