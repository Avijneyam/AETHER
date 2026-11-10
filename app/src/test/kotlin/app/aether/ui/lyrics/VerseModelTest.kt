package app.aether.ui.lyrics

import app.aether.core.lyrics.LyricsLine
import app.aether.core.lyrics.LyricsSyllable
import app.aether.core.lyrics.LyricsWord
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerseModelTest {
    private fun word(start: Long, text: String, end: Long? = null, syllables: List<LyricsSyllable> = emptyList()) = LyricsWord(start, text, end, syllables)

    @Test
    fun `Chinese sung a character at a time keeps its own text, with no spaces put in`() {
        val line = LyricsLine(1_000, "我爱你", words = listOf(word(1_000, "我", 1_300), word(1_300, "爱", 1_600), word(1_600, "你", 2_000)))
        val v = VLine.of(listOf(line)).single()
        assertEquals("我爱你", v.text)
        assertEquals(listOf(0 until 1, 1 until 2, 2 until 3), v.wordSpans)
        assertTrue(v.isWordSynced)
    }

    @Test
    fun `the sweep crosses a word over its own time and the space after it over the pause`() {
        val line = LyricsLine(0, "hold on", words = listOf(word(0, "hold", 400), word(600, "on", 1_000)))
        val v = VLine.of(listOf(line)).single()
        assertEquals(0f, v.revealedChars(0))
        assertEquals(2f, v.revealedChars(200))
        assertEquals(4.5f, v.revealedChars(500))
        assertEquals(5f, v.revealedChars(600))
        assertEquals(7f, v.revealedChars(1_000))
    }

    @Test
    fun `a syllable fills its own letters over its own time`() {
        val line = LyricsLine(0, "golden", words = listOf(word(0, "golden", 1_000, listOf(LyricsSyllable(0, 800, 0, 3), LyricsSyllable(800, 1_000, 3, 6)))))
        val v = VLine.of(listOf(line)).single()
        assertEquals(1.5f, v.revealedChars(400))
        assertEquals(4.5f, v.revealedChars(900))
        assertEquals(listOf(0 until 3, 3 until 6), v.sweepSpans)
    }

    @Test
    fun `a long silence after a line that says when it ends is a break, and so is a long intro`() {
        val lines = listOf(
            LyricsLine(5_000, "first", words = listOf(word(5_000, "first", 6_000))),
            LyricsLine(7_000, "second", words = listOf(word(7_000, "second", 8_000))),
            LyricsLine(20_000, "third", words = listOf(word(20_000, "third", 21_000))),
        )
        val rows = VLine.of(lines)
        assertEquals(listOf("", "first", "second", "", "third"), rows.map { it.text })
        assertEquals(0L, rows[0].timeMs)
        // The break starts as the singing stops, not when the next line is due.
        assertEquals(8_000L, rows[3].timeMs)
        assertEquals(listOf(-1, 0, 1, -1, 2), rows.map { it.origin })
    }

    @Test
    fun `a bare stamp is a break only where it lasts`() {
        val lines = listOf(
            LyricsLine(0, "one"),
            LyricsLine(2_000, ""),
            LyricsLine(3_000, "two"),
            LyricsLine(4_000, ""),
            LyricsLine(12_000, "three"),
        )
        val rows = VLine.of(lines)
        assertEquals(listOf("one", "two", "", "three"), rows.map { it.text })
        // Timed by the line alone: nothing says when "one" ends, so no break is made after it.
        assertTrue(rows.none { it.isGap && it.origin == -1 })
    }

    @Test
    fun `the answering vocal loses its brackets and keeps its timing`() {
        val backing = LyricsLine(1_000, "(oh yeah)", words = listOf(word(1_000, "(oh", 1_400, listOf(LyricsSyllable(1_000, 1_400, 1, 3))), word(1_400, "yeah)", 2_000)))
        val line = LyricsLine(0, "say it", words = listOf(word(0, "say", 400), word(400, "it", 900)), background = backing)
        val v = VLine.of(listOf(line)).single()
        val b = v.background!!
        assertEquals("oh yeah", b.text)
        assertEquals(listOf("oh", "yeah"), b.words.map { it.text })
        // "(oh" timed "oh" as chars 1..3; without the bracket that's 0..2.
        assertEquals(0, b.words[0].syllables.single().startChar)
        assertEquals(2, b.words[0].syllables.single().endChar)
        // The row lasts as long as its answering voice.
        assertEquals(2_000L, v.endMs)
    }

    @Test
    fun `Latin letters under a line light as far through as the line has got`() {
        val line = VLine.of(listOf(LyricsLine(0, "사랑해", words = listOf(word(0, "사랑해", 900))))).single()
        val gloss = VLine.gloss("saranghae", line)
        assertEquals(0f, gloss.revealedChars(0))
        assertEquals(3f, gloss.revealedChars(300))
        assertEquals(9f, gloss.revealedChars(900))
        assertTrue(gloss.isWordSynced)
    }

    @Test
    fun `lines sung over each other are both being sung`() {
        val rows = VLine.of(
            listOf(
                LyricsLine(0, "lead", words = listOf(word(0, "lead", 3_000))),
                LyricsLine(1_000, "answer", words = listOf(word(1_000, "answer", 2_000)), opposite = true),
            ),
        )
        assertEquals(listOf(0, 1), activeRows(rows, 1_500))
        assertEquals(listOf(0), activeRows(rows, 500))
        assertEquals(listOf(1), activeRows(rows, 3_500))
    }

    @Test
    fun `only a word held long enough swells, and never one in a script whose letters stand as blocks`() {
        val held = VLine.of(listOf(LyricsLine(0, "oh", words = listOf(word(0, "oh", 2_000))))).single()
        val quick = VLine.of(listOf(LyricsLine(0, "oh", words = listOf(word(0, "oh", 300))))).single()
        val korean = VLine.of(listOf(LyricsLine(0, "사랑", words = listOf(word(0, "사랑", 3_000))))).single()
        assertEquals(1, held.growingWords.size)
        assertTrue(quick.growingWords.isEmpty())
        assertTrue(korean.growingWords.isEmpty())
    }
}
