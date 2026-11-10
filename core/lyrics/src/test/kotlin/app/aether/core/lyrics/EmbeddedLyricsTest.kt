package app.aether.core.lyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EmbeddedLyricsTest {
    private val lrc = "[00:01.00]First line\n[00:04.50]Second line"

    private fun le32(out: ByteArrayOutputStream, v: Int) { for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF) }
    private fun be32(out: ByteArrayOutputStream, v: Int) { for (i in 3 downTo 0) out.write((v ushr (8 * i)) and 0xFF) }

    private fun vorbis(comments: List<String>) = ByteArrayOutputStream().apply {
        val vendor = "test".toByteArray()
        le32(this, vendor.size); write(vendor)
        le32(this, comments.size)
        comments.forEach { val b = it.toByteArray(); le32(this, b.size); write(b) }
    }.toByteArray()

    @Test fun `FLAC carries its words in a Vorbis comment`() {
        val block = vorbis(listOf("TITLE=Song", "LYRICS=$lrc"))
        val file = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            // STREAMINFO (34 bytes), then the comment, last.
            write(byteArrayOf(0, 0, 0, 34)); write(ByteArray(34))
            write(byteArrayOf(0x84.toByte(), (block.size shr 16).toByte(), (block.size shr 8).toByte(), block.size.toByte())); write(block)
        }.toByteArray()
        val lyrics = assertIs<Lyrics.Synced>(EmbeddedLyrics.read(ByteArrayInputStream(file)))
        assertEquals(listOf("First line", "Second line"), lyrics.lines.map { it.text })
    }

    @Test fun `MP3 carries words in USLT, and timed words in SYLT`() {
        fun frame(id: String, body: ByteArray) = ByteArrayOutputStream().apply {
            write(id.toByteArray()); be32(this, body.size); write(byteArrayOf(0, 0)); write(body)
        }.toByteArray()
        val uslt = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "Plain words\nand more".toByteArray()
        val sylt = ByteArrayOutputStream().apply {
            write(3); write("eng".toByteArray()); write(2); write(1); write(0)
            write("Timed one".toByteArray()); write(0); be32(this, 1_000)
            write("Timed two".toByteArray()); write(0); be32(this, 3_000)
        }.toByteArray()
        fun tag(vararg frames: ByteArray): ByteArray {
            val body = frames.fold(ByteArray(0)) { a, b -> a + b }
            val size = body.size
            val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
                ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte())
            return header + body
        }
        assertIs<Lyrics.Plain>(EmbeddedLyrics.read(ByteArrayInputStream(tag(frame("USLT", uslt)))))
        val timed = assertIs<Lyrics.Synced>(EmbeddedLyrics.read(ByteArrayInputStream(tag(frame("USLT", uslt), frame("SYLT", sylt)))))
        assertEquals(listOf(1_000L, 3_000L), timed.lines.map { it.startMs })
    }

    @Test fun `M4A carries words in its lyric atom`() {
        fun atom(type: String, body: ByteArray) = ByteArrayOutputStream().apply { be32(this, body.size + 8); write(type.toByteArray(Charsets.ISO_8859_1)); write(body) }.toByteArray()
        val data = atom("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + lrc.toByteArray())
        val ilst = atom("ilst", atom("©lyr", data))
        val meta = atom("meta", byteArrayOf(0, 0, 0, 0) + ilst)
        val file = atom("ftyp", "M4A isom".toByteArray()) + atom("moov", atom("udta", meta))
        assertIs<Lyrics.Synced>(EmbeddedLyrics.read(ByteArrayInputStream(file)))
    }

    @Test fun `a file without words is nothing`() = assertNull(EmbeddedLyrics.read(ByteArrayInputStream("not music at all".toByteArray())))

    @Test fun `words timed by the word come back timed by the word`() {
        val line = LyricsLine(1_000, "Hello world", words = listOf(LyricsWord(1_000, "Hello"), LyricsWord(1_600, "world")))
        val written = LrcWriter.write(Lyrics.Synced(listOf(line)))!!
        val back = assertIs<Lyrics.Synced>(EmbeddedLyrics.lyrics(written)).lines.single()
        assertEquals("Hello world", back.text)
        assertEquals(listOf(1_000L, 1_600L), back.words.map { it.startMs })
    }
}
