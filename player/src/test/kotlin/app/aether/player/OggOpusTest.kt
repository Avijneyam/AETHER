package app.aether.player

import app.aether.core.lyrics.EmbeddedLyrics
import app.aether.core.lyrics.Lyrics
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OggOpusTest {
    private val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

    private fun stream(cover: ByteArray? = null, packets: Int = 400): ByteArray = ByteArrayOutputStream().also { out ->
        val tags = OggOpus.Tags("Song", "Artist", "Album", 2026, 3, "[00:01.00]Words here\n[00:03.00]And here", cover)
        // A 20 ms frame of CELT at full band (config 31, one frame): 960 samples.
        OggOpus.write(out, head, tags, List(packets) { ByteArray(120) { i -> if (i == 0) 0xF8.toByte() else i.toByte() } })
    }.toByteArray()

    /** Every page well formed: its capture pattern, its sequence, and its checksum. */
    private fun pages(bytes: ByteArray): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>()
        var p = 0
        var seq = 0
        while (p < bytes.size) {
            assertEquals("OggS", String(bytes, p, 4))
            val type = bytes[p + 5].toInt()
            var granule = 0L
            for (i in 0 until 8) granule = granule or ((bytes[p + 6 + i].toLong() and 0xFF) shl (8 * i))
            val sequence = (0 until 4).sumOf { (bytes[p + 18 + it].toInt() and 0xFF) shl (8 * it) }
            assertEquals(seq++, sequence)
            val segments = bytes[p + 26].toInt() and 0xFF
            val size = 27 + segments + (0 until segments).sumOf { bytes[p + 27 + it].toInt() and 0xFF }
            val page = bytes.copyOfRange(p, p + size)
            val stated = (0 until 4).sumOf { (page[22 + it].toInt() and 0xFF) shl (8 * it) }
            for (i in 22..25) page[i] = 0
            assertEquals(stated, crc(page))
            out += type to granule
            p += size
        }
        return out
    }

    private fun crc(bytes: ByteArray): Int {
        val table = IntArray(256) { i -> var r = i shl 24; repeat(8) { r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }; r }
        var c = 0
        for (b in bytes) c = (c shl 8) xor table[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    @Test fun `a kept file is a well formed Ogg stream`() {
        val pages = pages(stream())
        assertEquals(2, pages.first().first) // the first page begins the stream
        assertEquals(4, pages.last().first and 4) // the last ends it
        assertEquals(400L * 960, pages.last().second)
    }

    @Test fun `its words read back as any player reads them`() {
        val lyrics = assertIs<Lyrics.Synced>(EmbeddedLyrics.read(ByteArrayInputStream(stream())))
        assertEquals(listOf("Words here", "And here"), lyrics.lines.map { it.text })
    }

    @Test fun `a cover too large for one page carries its tags over several`() {
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(200_000) { it.toByte() }
        val bytes = stream(cover)
        val pages = pages(bytes)
        assertTrue(pages.count { it.first and 1 == 1 } >= 3)
        assertIs<Lyrics.Synced>(EmbeddedLyrics.read(ByteArrayInputStream(bytes)))
    }
}
