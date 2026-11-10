package app.aether.core.lyrics

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HangulTest {
    private fun r(s: String) = Hangul.romanise(s)

    @Test fun `plain syllables`() = assertEquals("saranghae", r("사랑해"))
    @Test fun `a final carries over to a vowel`() {
        assertEquals("meogeo", r("먹어"))
        assertEquals("isseo", r("있어"))
        assertEquals("ilgeo", r("읽어"))
    }
    @Test fun `ㅎ falls silent before a vowel and breathes into what follows`() {
        assertEquals("joa", r("좋아"))
        assertEquals("jota", r("좋다"))
        assertEquals("manta", r("많다"))
        assertEquals("chuka", r("축하"))
    }
    @Test fun `the nasals`() {
        assertEquals("gamsahamnida", r("감사합니다"))
        assertEquals("gungmul", r("국물"))
    }
    @Test fun `ㄹ beside ㄴ and ㄹ`() {
        assertEquals("silla", r("신라"))
        assertEquals("seollal", r("설날"))
        assertEquals("simni", r("심리"))
        assertEquals("jongno", r("종로"))
    }
    @Test fun `ㄷ and ㅌ before 이 soften`() {
        assertEquals("gachi", r("같이"))
        assertEquals("guji", r("굳이"))
    }
    @Test fun `RR writes the plain stops as they're spelt`() = assertEquals("hakgyo", r("학교"))
}

class TtmlTest {
    private val doc = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/></metadata></head>
          <body>
            <div>
              <p begin="10.000" end="12.000" ttm:agent="v1"><span begin="10.000" end="10.400">e</span><span begin="10.400" end="11.000">nough</span> <span begin="11.000" end="12.000">said,</span><span ttm:role="x-bg"><span begin="11.500" end="12.500">(ooh)</span></span></p>
              <p begin="20.000" end="22.000" ttm:agent="v2"><span begin="20.000" end="21.000">我</span><span begin="21.000" end="22.000">爱</span></p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    @Test fun `syllables join into words where the space is left out`() {
        val lines = Ttml.parse(doc).filter { it.text.isNotEmpty() }
        val first = lines[0]
        assertEquals("enough said,", first.text)
        assertEquals(listOf("enough", "said,"), first.words.map { it.text })
        assertEquals(2, first.words[0].syllables.size)
        assertEquals(12_000L, first.words[1].endMs)
    }

    @Test fun `the answering vocal is its own line`() {
        val first = Ttml.parse(doc).first { it.text.isNotEmpty() }
        assertEquals("(ooh)", first.background?.text)
    }

    @Test fun `a duet's second voice is set from the other side`() {
        val lines = Ttml.parse(doc).filter { it.text.isNotEmpty() }
        assertEquals(listOf(false, true), lines.map { it.opposite })
    }

    @Test fun `characters written without spaces stay one word, timed by character`() {
        val line = Ttml.parse(doc).filter { it.text.isNotEmpty() }[1]
        assertEquals("我爱", line.text)
        assertEquals(1, line.words.size)
        val units = WordTiming.units(line, null)
        assertEquals(listOf("我", "爱"), units.map { it.text })
        assertEquals(listOf(20_000L, 21_000L), units.map { it.startMs })
    }

    @Test fun `a long break is marked`() {
        val lines = Ttml.parse(doc)
        assertTrue(lines.any { it.text.isEmpty() && it.startMs in 12_000L..12_600L })
    }
}

class ProviderTextTest {
    @Test fun `karaoke lines read with their syllables`() {
        val lyrics = assertIs<Lyrics.Synced>(ProviderText.parse("[1000,2000]Hel(1000,300)lo (1300,200)world(1500,1500)"))
        val line = lyrics.lines.first { it.text.isNotEmpty() }
        assertEquals("Hello world", line.text)
        assertEquals(listOf("Hello", "world"), line.words.map { it.text })
    }

    @Test fun `lyrics wrapped in a JSON envelope`() {
        val lyrics = assertIs<Lyrics.Synced>(ProviderText.parse("""{"ok":true,"data":{"lrc":"[00:01.00]One\n[00:03.00]Two"}}"""))
        assertEquals(listOf("One", "Two"), lyrics.lines.map { it.text })
    }

    @Test fun `an error envelope is nothing`() = assertEquals(null, ProviderText.parse("""{"error":"Lyrics not found"}"""))

    @Test fun `CJK runs keep the line as written`() {
        val text = Karaoke.parse("[0,3000]我(0,1000)爱(1000,1000)你(2000,1000)").first { it.text.isNotEmpty() }
        assertEquals("我爱你", text.text)
        assertEquals(3, WordTiming.units(text, null).size)
    }
}

class LyricsChainTest {
    private class Fixed(override val id: String, val answer: ProviderAnswer, val waitMs: Long = 0, val words: Boolean = true) : LyricsLibrary(LyricsHttp { 200 to "" }) {
        override val attribution = LyricsAttribution(id, "")
        override val timesWords = words
        override suspend fun find(request: LyricsRequest): Lyrics? {
            delay(waitMs)
            return (answer as? ProviderAnswer.Found)?.lyrics
        }
    }

    private val request = LyricsRequest("k", "Song", "Artist", durationMs = 200_000)
    private val lineTimed = Lyrics.Synced(listOf(LyricsLine(1_000, "a b")))
    private val wordTimed = Lyrics.Synced(listOf(LyricsLine(1_000, "a b", words = listOf(LyricsWord(1_000, "a", 1_500), LyricsWord(1_500, "b", 2_000)))))

    @Test fun `word timing beats a faster answer timed by the line`() = runTest {
        val chain = LyricsChain({ listOf(Fixed("slow", ProviderAnswer.Found(wordTimed), waitMs = 500), Fixed("fast", ProviderAnswer.Found(lineTimed), words = false)) })
        val answer = assertIs<ProviderAnswer.Found>(chain.lookup(request))
        assertEquals("slow", answer.credit?.name)
    }

    @Test fun `among alike answers the first library wins`() = runTest {
        val chain = LyricsChain({ listOf(Fixed("one", ProviderAnswer.Found(wordTimed), waitMs = 300), Fixed("two", ProviderAnswer.Found(wordTimed))) })
        assertEquals("one", assertIs<ProviderAnswer.Found>(chain.lookup(request)).credit?.name)
    }

    @Test fun `words timed far past the song are another edit's`() = runTest {
        val long = Lyrics.Synced(listOf(LyricsLine(400_000, "late")))
        val chain = LyricsChain({ listOf(Fixed("wrong", ProviderAnswer.Found(long)), Fixed("right", ProviderAnswer.Found(lineTimed), words = false)) })
        assertEquals("right", assertIs<ProviderAnswer.Found>(chain.lookup(request)).credit?.name)
    }

    @Test fun `nothing anywhere is not found`() = runTest {
        val chain = LyricsChain({ listOf(Fixed("a", ProviderAnswer.NotFound), Fixed("b", ProviderAnswer.NotFound)) })
        assertEquals(ProviderAnswer.NotFound, chain.lookup(request))
    }
}
