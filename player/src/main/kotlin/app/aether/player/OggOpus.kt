// After LastWave's WebmOpusRemuxer (GPL-3.0), with tags of its own, for AETHER's kept songs.
package app.aether.player

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * YouTube's Opus moved from its WebM into an Ogg Opus file, packet for packet: nothing is decoded or
 * encoded again, so the kept file is the very audio that streamed (LastWave's remuxer). Its tags carry
 * the song (title, artist, album, year, its number), its cover, and its words, so the phone's file
 * manager, and any player, shows it as itself.
 */
internal object OggOpus {
    /** What goes in the file's tags. */
    class Tags(
        val title: String,
        val artist: String?,
        val album: String?,
        val year: Int?,
        val number: Int?,
        val lyrics: String?,
        val cover: ByteArray?,
        /** The song as AETHER knows it, so a reinstalled AETHER can find its kept songs again. */
        val aether: String? = null,
    )

    /** [webm] remuxed into [out] as Ogg Opus; false when it isn't Opus in WebM, or can't be read. */
    fun remux(webm: File, out: OutputStream, tags: Tags): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(webm.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).equals("audio/opus", ignoreCase = true)
            } ?: return false
            val format = extractor.getTrackFormat(track)
            val head = format.bytes("csd-0")?.takeIf { it.size >= 19 && String(it, 0, 8, StandardCharsets.US_ASCII) == "OpusHead" } ?: return false
            val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0) else 0L
            extractor.selectTrack(track)
            val writer = Pages(out, serial = (webm.length() xor System.nanoTime()).toInt().let { if (it == 0) 1 else it })
            writer.packet(head, granule = 0, first = true)
            writer.packet(tagsPacket(tags), granule = 0)
            var buffer = ByteBuffer.allocateDirect(format.int(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceIn(4 * 1024, MAX_PACKET) ?: 64 * 1024)
            var samples = 0L
            var count = 0
            var pending: ByteArray? = null
            var pendingSamples = 0
            while (true) {
                val size = extractor.sampleSize
                if (size < 0) break
                if (size > MAX_PACKET) return false
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                if (read > 0) {
                    val packet = ByteArray(read).also { buffer.get(it, 0, read) }
                    val n = duration(packet) ?: return false
                    // Each packet is written once the next is known, so the last can close the stream.
                    pending?.let { writer.audio(it, samples) }
                    samples += n
                    pending = packet
                    pendingSamples = n
                    count++
                }
                if (!extractor.advance()) break
            }
            val last = pending ?: return false
            var end = samples
            if (durationUs > 0) {
                val declared = preSkip + (durationUs * 48_000L + 500_000L) / 1_000_000L
                if (declared in (samples - pendingSamples)..samples) end = declared
            }
            writer.audio(last, end, last = true)
            writer.flush()
            return count > 0
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** An Ogg Opus stream of [packets] after [head] and [tags], written whole: what [remux] writes, as it writes it. */
    internal fun write(out: OutputStream, head: ByteArray, tags: Tags, packets: List<ByteArray>) {
        val writer = Pages(out, serial = 0x4145_5448)
        writer.packet(head, granule = 0, first = true)
        writer.packet(tagsPacket(tags), granule = 0)
        var samples = 0L
        packets.forEachIndexed { i, p ->
            samples += duration(p) ?: 960
            writer.audio(p, samples, last = i == packets.lastIndex)
        }
    }

    /** OpusTags: a vendor, then Vorbis comments, the cover as a FLAC picture block in base64. */
    private fun tagsPacket(t: Tags): ByteArray {
        val comments = buildList {
            add("TITLE=${t.title}")
            t.artist?.takeIf { it.isNotBlank() }?.let { add("ARTIST=$it") }
            t.album?.takeIf { it.isNotBlank() }?.let { add("ALBUM=$it") }
            t.year?.let { add("DATE=$it") }
            t.number?.let { add("TRACKNUMBER=$it") }
            t.lyrics?.takeIf { it.isNotBlank() }?.let { add("LYRICS=$it") }
            t.aether?.let { add("AETHER_TRACK=$it") }
            t.cover?.let { image -> mimeOf(image)?.let { add("METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(picture(image, it))) } }
        }
        return ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray(StandardCharsets.US_ASCII))
            val vendor = "AETHER".toByteArray(StandardCharsets.UTF_8)
            le32(vendor.size)
            write(vendor)
            le32(comments.size)
            comments.forEach { c ->
                val bytes = c.toByteArray(StandardCharsets.UTF_8)
                le32(bytes.size)
                write(bytes)
            }
        }.toByteArray()
    }

    /** What a picture is, by its first bytes; null for anything a player wouldn't show. */
    private fun mimeOf(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
        b.size > 12 && String(b, 0, 4, StandardCharsets.US_ASCII) == "RIFF" && String(b, 8, 4, StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    /** A FLAC PICTURE block: the front cover, its type, and the picture. */
    private fun picture(image: ByteArray, mime: String): ByteArray = ByteArrayOutputStream().apply {
        be32(3) // front cover
        val m = mime.toByteArray(StandardCharsets.US_ASCII)
        be32(m.size); write(m)
        be32(0) // no description
        be32(0); be32(0); be32(0); be32(0) // width, height, depth, colours: unknown, as allowed
        be32(image.size); write(image)
    }.toByteArray()

    /** An Opus packet's length in samples at 48 kHz, from its table of contents (RFC 6716). */
    internal fun duration(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val config = toc ushr 3
        val perFrame = when {
            config < 12 -> when (config and 3) { 0 -> 480; 1 -> 960; 2 -> 1920; else -> 2880 }
            config < 16 -> if (config and 1 == 0) 480 else 960
            else -> 120 shl (config and 3)
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size >= 2) packet[1].toInt() and 0x3F else return null
        }
        if (frames !in 1..48) return null
        return (perFrame * frames).takeIf { it in 120..5760 }
    }

    /** Ogg pages: header packets on pages of their own, audio packed into pages of about 60 KB; a packet larger than a page continues on the next. */
    private class Pages(private val out: OutputStream, private val serial: Int) {
        private var sequence = 0
        private val lacing = ByteArrayOutputStream()
        private val body = ByteArrayOutputStream()
        private var granule = 0L

        fun packet(p: ByteArray, granule: Long, first: Boolean = false) {
            // A header packet always ends its page; one longer than a page goes on over the next, and
            // one that fills its last page exactly is closed by a page holding a single empty lace.
            var at = 0
            var continued = false
            var firstPage = first
            while (true) {
                val (take, segments) = segmentsFor(p.size - at, 255)
                val ends = segments.last().toInt() and 0xFF < 255
                page(
                    type = (if (continued) 1 else 0) or (if (firstPage) 2 else 0),
                    granule = if (ends) granule else -1,
                    lacing = segments,
                    data = p.copyOfRange(at, at + take),
                )
                at += take
                continued = true
                firstPage = false
                if (ends) break
            }
        }

        fun audio(p: ByteArray, endGranule: Long, last: Boolean = false) {
            val laces = laceOf(p.size)
            if (lacing.size() + laces.size > 255 || body.size() + p.size > TARGET_PAGE) flush(false)
            lacing.write(laces)
            body.write(p)
            granule = endGranule
            if (last) flush(true)
        }

        fun flush(end: Boolean = false) {
            if (lacing.size() == 0) return
            page(type = if (end) 4 else 0, granule = granule, lacing = lacing.toByteArray(), data = body.toByteArray())
            lacing.reset()
            body.reset()
        }

        /** How much of the [left] bytes fit on one page, and the lacing for them (a packet ending here ends with a lace under 255). */
        private fun segmentsFor(left: Int, maxSegments: Int): Pair<Int, ByteArray> {
            val full = left / 255
            return if (full < maxSegments) left to laceOf(left) else (maxSegments * 255) to ByteArray(maxSegments) { 255.toByte() }
        }

        private fun laceOf(size: Int): ByteArray = ByteArray(size / 255 + 1) { i -> if (i < size / 255) 255.toByte() else (size % 255).toByte() }

        private fun page(type: Int, granule: Long, lacing: ByteArray, data: ByteArray) {
            val page = ByteArrayOutputStream(27 + lacing.size + data.size)
            page.write("OggS".toByteArray(StandardCharsets.US_ASCII))
            page.write(0)
            page.write(type)
            for (i in 0 until 8) page.write(((granule ushr (8 * i)) and 0xFF).toInt())
            for (v in intArrayOf(serial, sequence++, 0)) for (i in 0 until 4) page.write((v ushr (8 * i)) and 0xFF)
            page.write(lacing.size)
            page.write(lacing)
            page.write(data)
            val bytes = page.toByteArray()
            val crc = crc(bytes)
            for (i in 0 until 4) bytes[22 + i] = (crc ushr (8 * i)).toByte()
            out.write(bytes)
        }
    }

    private val CRC = IntArray(256) { index ->
        var r = index shl 24
        repeat(8) { r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun crc(bytes: ByteArray): Int {
        var c = 0
        for (b in bytes) c = (c shl 8) xor CRC[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun ByteArrayOutputStream.le32(v: Int) { for (i in 0 until 4) write((v ushr (8 * i)) and 0xFF) }
    private fun ByteArrayOutputStream.be32(v: Int) { for (i in 3 downTo 0) write((v ushr (8 * i)) and 0xFF) }
    private fun MediaFormat.bytes(key: String): ByteArray? = runCatching { getByteBuffer(key)?.duplicate()?.let { b -> b.rewind(); ByteArray(b.remaining()).also { b.get(it) } } }.getOrNull()
    private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private const val MAX_PACKET = 2 * 1024 * 1024
    private const val TARGET_PAGE = 60 * 1024
}
