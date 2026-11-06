package app.aether.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** How the music leaves the phone: the device it plays to, and whether the path is verified bit-perfect. */
data class Route(val device: String?, val bitPerfect: Boolean)

/**
 * Bit-perfect to a USB DAC, by Android 14's own route (LastWave's `UsbBitPerfectOutput` idea, on the
 * platform's API): with a USB DAC attached that says it can take the song's decoded format
 * untouched, the mixer is asked to pass it through bit for bit.
 *
 * Only where the format is certain: a streamed song (lossy) decodes to 16-bit at its own rate, and
 * that's what's asked for. A mismatched bit-perfect request can stop playback outright, so nothing
 * is asked for formats it can't be sure of (local hi-res files go out as float through the mixer).
 * "Bit-perfect" is reported only when the phone confirms both that the mixer took the request and
 * that the DAC is where media is playing.
 */
@OptIn(UnstableApi::class)
@RequiresApi(34)
internal class BitPerfect(context: Context, private val player: ExoPlayer, private val report: (Route) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var applied: AudioDeviceInfo? = null

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reconsider()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reconsider()
    }

    fun start() {
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        reconsider()
    }

    fun stop() {
        audio.unregisterAudioDeviceCallback(devices)
        clear()
    }

    /** Looks again: a DAC came or went, or the song (and so its format) changed. */
    fun reconsider() {
        val dac = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val format = player.audioFormat
        if (dac == null || format == null) {
            clear()
            report(Route(dac?.productName?.toString(), false))
            return
        }
        val lossy = format.sampleMimeType !in Lossless
        val rate = format.sampleRate
        val wanted = if (lossy && rate > 0) {
            audio.getSupportedMixerAttributes(dac).firstOrNull { m ->
                m.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    m.format.sampleRate == rate &&
                    m.format.encoding == AudioFormat.ENCODING_PCM_16BIT &&
                    m.format.channelCount == format.channelCount.coerceAtLeast(1)
            }
        } else null
        if (wanted == null) {
            clear()
            report(Route(dac.productName?.toString(), false))
            return
        }
        if (audio.setPreferredMixerAttributes(media, dac, wanted)) applied = dac
        report(Route(dac.productName?.toString(), verified(dac)))
    }

    /** The mixer holds the request, and media is actually playing to that DAC. */
    private fun verified(dac: AudioDeviceInfo): Boolean =
        audio.getPreferredMixerAttributes(media, dac)?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            audio.getAudioDevicesForAttributes(media).any { it.id == dac.id }

    private fun clear() {
        applied?.let { runCatching { audio.clearPreferredMixerAttributes(media, it) } }
        applied = null
    }

    private companion object {
        val Lossless = setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV)
    }
}
