@file:OptIn(UnstableApi::class)

package app.aether.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.aether.core.model.EqMath
import app.aether.core.model.EqProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/** A change of tuning is crossfaded over this long, so that it never clicks. */
private const val SwapSeconds = 0.03

/** The standard renderers with the equaliser in the audio path. */
internal class ShapedRenderers(context: Context, private val equaliser: EqualiserProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(equaliser))
            .build()
}

/**
 * The equaliser, in the decoded audio before it reaches Android's mixer (after PODIUM's
 * `EqualizerProcessor`, GPL-3.0): the same on every phone, local or online.
 *
 * - With nothing to do, the audio passes through untouched, sample for sample.
 * - Everything is lowered first by the curve's highest point, so a boost can't clip.
 * - Any file's filters can be asked for; each is an ordinary stable biquad.
 *
 * 16-bit and float audio; anything else passes by (it isn't asked for).
 */
internal class EqualiserProcessor : BaseAudioProcessor() {
    private class Swap(val rate: Int, val channels: Int, val to: Filters?)

    @Volatile private var profile: EqProfile? = null
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    private val pending = AtomicReference<Swap?>(null)
    private var encoding = C.ENCODING_INVALID
    private var live: Filters? = null
    private var leaving: Filters? = null
    private var fadeLeft = 0
    private var fadeFrames = 1

    /** Whether the samples are being changed. */
    val shaping: Boolean get() = profile != null

    /** From any thread; the next buffer starts going over to [next]. */
    fun update(next: EqProfile?) {
        val audible = next?.takeIf { it.shaping }
        if (audible == profile) return
        profile = audible
        val r = rate
        val ch = channels
        if (r > 0) pending.set(Swap(r, ch, audible?.let { Filters(it, r, ch) }))
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        fadeFrames = (rate * SwapSeconds).toInt().coerceAtLeast(1)
        pending.set(null)
        live = profile?.let { Filters(it, rate, channels) }
        leaving = null
        fadeLeft = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        pending.getAndSet(null)?.let { swap ->
            if (swap.rate == rate && swap.channels == channels) {
                leaving = live
                live = swap.to
                fadeLeft = fadeFrames
            }
        }
        val out = replaceOutputBuffer(bytes)
        val now = live
        val old = leaving
        if (now == null && old == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val short = encoding == C.ENCODING_PCM_16BIT
        val frame = (if (short) 2 else 4) * channels
        while (input.remaining() >= frame) {
            val fading = fadeLeft > 0
            val mix = if (fading) 1f - fadeLeft.toFloat() / fadeFrames else 1f
            for (ch in 0 until channels) {
                val x = if (short) input.getShort() / 32768f else input.getFloat()
                val shaped = now?.process(ch, x) ?: x
                val y = if (fading) {
                    val was = old?.process(ch, x) ?: x
                    was + (shaped - was) * mix
                } else shaped
                if (short) out.putShort((y * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()) else out.putFloat(y.coerceIn(-1f, 1f))
            }
            if (fading && --fadeLeft == 0) leaving = null
        }
        // A partial frame (never sent by the sink, but never lost either).
        while (input.hasRemaining()) out.put(input.get())
        out.flip()
    }

    override fun onFlush() {
        live?.clear()
        leaving = null
        fadeLeft = 0
    }

    override fun onReset() {
        pending.set(null)
        live = null
        leaving = null
        fadeLeft = 0
        rate = 0
        channels = 0
        encoding = C.ENCODING_INVALID
    }

    /** One profile's filters at one sample rate, with each channel's memory (transposed direct form II). */
    private class Filters(profile: EqProfile, rate: Int, channels: Int) {
        private val b0: DoubleArray
        private val b1: DoubleArray
        private val b2: DoubleArray
        private val a1: DoubleArray
        private val a2: DoubleArray
        private val headroom: Float
        private val bands: Int
        private val z1: DoubleArray
        private val z2: DoubleArray

        init {
            val fs = rate.toDouble()
            val audible = profile.filters.filter { it.hz < fs * 0.45 }
            val c = audible.map { EqMath.coefficients(it, fs) }
            bands = c.size
            b0 = DoubleArray(bands) { c[it][0] }
            b1 = DoubleArray(bands) { c[it][1] }
            b2 = DoubleArray(bands) { c[it][2] }
            a1 = DoubleArray(bands) { c[it][3] }
            a2 = DoubleArray(bands) { c[it][4] }
            headroom = 10.0.pow(-EqMath.peakDb(audible, fs).coerceAtLeast(0.0) / 20).toFloat()
            z1 = DoubleArray(bands * channels)
            z2 = DoubleArray(bands * channels)
        }

        fun process(channel: Int, sample: Float): Float {
            var x = (sample * headroom).toDouble()
            val base = channel * bands
            for (b in 0 until bands) {
                val i = base + b
                val y = b0[b] * x + z1[i]
                z1[i] = b1[b] * x - a1[b] * y + z2[i]
                z2[i] = b2[b] * x - a2[b] * y
                x = y
            }
            return x.toFloat()
        }

        fun clear() {
            z1.fill(0.0)
            z2.fill(0.0)
        }
    }
}
