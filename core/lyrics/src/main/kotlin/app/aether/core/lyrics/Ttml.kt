// After BitChord's TtmlLyrics, LyricAlignments and LyricGaps (GPL-3.0), for AETHER's lyrics.
package app.aether.core.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Apple Music's word-timed lyrics (TTML), as the libraries that carry it serve it: a `<p>` for each
 * sung line, a `<span>` for each syllable with its own `begin` and `end`. Syllables of one word are
 * spans with no whitespace between them ("e" + "nough"), so whitespace, not the span, divides words.
 * A span with the role `x-bg` is the answering vocal, kept apart as the line's background; one with
 * `x-translation` or `x-roman` is another rendering of the same words, and left out. Who sings each
 * line (`ttm:agent`) sets a duet's two voices on either side.
 *
 * Never throws: what can't be read is an empty list. Nothing the document names is fetched.
 */
object Ttml {
    private val SKIPPED_ROLES = setOf("x-translation", "x-roman")
    private const val BACKGROUND_ROLE = "x-bg"

    fun parse(ttml: String): List<LyricsLine> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // From a third party: refuse anything the document asks to fetch. Each feature on its own,
            // as a parser lacking one throws rather than declines (Android's does for the first).
            harden("http://apache.org/xml/features/disallow-doctype-decl")
            harden("http://xml.org/sax/features/external-general-entities", false)
            harden("http://xml.org/sax/features/external-parameter-entities", false)
            harden(XMLConstants.FEATURE_SECURE_PROCESSING)
            runCatching { isExpandEntityReferences = false }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        val paragraphs = document.getElementsByTagName("p")
        val sung = ArrayList<Pair<LyricsLine, String?>>(paragraphs.length)
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val line = lineFrom(p) ?: continue
            sung += line to p.qualified("ttm:agent").takeIf { it.isNotEmpty() }
        }
        sung.sortBy { it.first.startMs }
        val sides = Voices.sides(sung.map { it.second }, agentTypes(document))
        sung.mapIndexed { i, (line, _) -> line.copy(opposite = sides[i]) }.withGaps()
    }.getOrDefault(emptyList())

    private fun DocumentBuilderFactory.harden(feature: String, value: Boolean = true) {
        runCatching { setFeature(feature, value) }
    }

    private fun agentTypes(document: org.w3c.dom.Document): Map<String, String> {
        val agents = document.getElementsByTagName("ttm:agent").takeIf { it.length > 0 } ?: document.getElementsByTagName("agent")
        val types = HashMap<String, String>()
        for (i in 0 until agents.length) {
            val agent = agents.item(i) as? Element ?: continue
            val id = agent.qualified("xml:id")
            val type = agent.getAttribute("type")
            if (id.isNotEmpty() && type.isNotEmpty()) types[id] = type
        }
        return types
    }

    /**
     * A prefixed attribute, read the same on both parsers this runs on: the JVM's keeps `ttm:agent`
     * whole, Android's files it under `agent`.
     */
    private fun Element.qualified(name: String): String {
        getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
        val local = name.substringAfter(':')
        val found = attributes ?: return ""
        for (i in 0 until found.length) {
            val a = found.item(i) ?: continue
            if (a.nodeName == name || a.nodeName == local || a.localName == local) return a.nodeValue.orEmpty()
        }
        return ""
    }

    private fun lineFrom(p: Element): LyricsLine? {
        val pieces = ArrayList<Run>()
        val backing = ArrayList<Run>()
        collect(p, pieces, backing)
        val words = Runs.words(pieces, spacingIsExplicit = true)
        val background = Runs.words(backing, spacingIsExplicit = true).takeIf { it.isNotEmpty() }?.let {
            LyricsLine(it.first().startMs, it.joinToString(" ") { w -> w.text }, endMs = it.last().endMs, words = it)
        }
        if (words.isEmpty()) {
            // Timed by the line only: a stamp and bare text.
            val text = p.textContent?.trim()?.replace(Regex("""\s+"""), " ").orEmpty()
            val begin = time(p.getAttribute("begin")) ?: return null
            if (text.isEmpty()) return null
            return LyricsLine(begin, text, endMs = time(p.getAttribute("end"))?.takeIf { it > begin })
        }
        // The paragraph's own stamp, a hair before a soft first syllable, is when the line appears.
        val begin = time(p.getAttribute("begin")) ?: words.first().startMs
        return LyricsLine(
            startMs = minOf(begin, words.first().startMs),
            text = words.joinToString(" ") { it.text },
            endMs = words.last().endMs,
            words = words,
            background = background,
        )
    }

    /** A paragraph flattened to timed leaves and the text between them; a backing span's leaves go to [backing]. */
    private fun collect(node: Node, out: MutableList<Run>, backing: MutableList<Run>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            when (val child = children.item(i)) {
                is Element -> {
                    val role = child.qualified("ttm:role")
                    if (role in SKIPPED_ROLES) continue
                    val sink = if (role == BACKGROUND_ROLE) backing else out
                    val begin = time(child.getAttribute("begin"))
                    val end = time(child.getAttribute("end"))
                    if (begin != null && end != null && !hasTimedChild(child)) sink += Run(begin, end, child.textContent.orEmpty())
                    else collect(child, sink, backing)
                }
                else -> if (child.nodeType == Node.TEXT_NODE) {
                    val text = child.textContent.orEmpty()
                    if (text.isNotEmpty()) out += Run(-1, -1, text)
                }
            }
        }
    }

    private fun hasTimedChild(e: Element): Boolean {
        val children = e.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i) as? Element ?: continue
            if (c.getAttribute("begin").isNotEmpty() || hasTimedChild(c)) return true
        }
        return false
    }

    /** TTML clock values: `27.395`, `1:05.20`, `1:02:03.4`, `12.5s`, `250ms`; in milliseconds. */
    fun time(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("ms")) return raw.dropLast(2).toDoubleOrNull()?.toLong()
        val parts = raw.removeSuffix("s").split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> parts[0].toDoubleOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
            3 -> parts[0].toDoubleOrNull()?.let { h -> parts[1].toDoubleOrNull()?.let { m -> parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it } } }
            else -> null
        } ?: return null
        return (seconds * 1000).toLong()
    }
}

/** One run of text as a provider wrote it, its spacing untouched; a start below zero is text with no time of its own. */
internal class Run(val startMs: Long, val endMs: Long, val raw: String) {
    val timed: Boolean get() = startMs >= 0
}

internal object Runs {
    /**
     * Runs glued into words, each keeping its runs as syllables. Every format that times syllables
     * says which belong together by leaving the space out: Apple's `<span>e</span><span>nough</span>`,
     * QQ's `e(31839,157)nough(31996,533)`. Untimed text is punctuation hanging off a word (or space
     * between words). A word is what spaces divide, as everywhere in AETHER's lyrics: so a phrase in
     * a script written without spaces (Han, kana, Thai) is one word, its characters its syllables,
     * and its line reads as it was written. A writer who puts no space anywhere in a Latin line isn't
     * saying where words end: there each run is a word, unless [spacingIsExplicit].
     */
    fun words(runs: List<Run>, spacingIsExplicit: Boolean = false): List<LyricsWord> {
        val glue = spacingIsExplicit || runs.any { r -> r.raw.any(Char::isWhitespace) } || runs.any { r -> r.raw.any(Scripts::unspaced) }
        val words = ArrayList<LyricsWord>()
        val current = StringBuilder()
        // Untimed text that opens the next word ("(" before "ooh"): held until that word begins.
        val prefix = StringBuilder()
        val parts = ArrayList<LyricsSyllable>()
        var start = 0L
        var end = 0L
        var timed = false
        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty() && timed) words += LyricsWord(start, text, maxOf(start, end), normalized(parts, text.length))
            current.setLength(0)
            parts.clear()
            timed = false
        }
        for (run in runs) {
            if (!run.timed) {
                val raw = run.raw
                if (raw.isBlank()) { flush(); continue }
                val leads = raw.first().isWhitespace()
                val trails = raw.last().isWhitespace()
                if (leads) flush()
                // Punctuation hangs off the word before it, or opens the one after; alone, it's dropped.
                if (!leads && timed) current.append(raw.trim()) else if (!trails) prefix.append(raw.trim())
                if (trails) flush()
                continue
            }
            val content = run.raw.trim()
            if (content.isEmpty()) { flush(); continue }
            val joins = glue && current.isNotEmpty() && !run.raw.first().isWhitespace()
            if (!joins) flush()
            if (current.isEmpty()) {
                start = run.startMs
                end = run.startMs
                current.append(prefix)
                prefix.setLength(0)
            }
            val from = current.length
            current.append(content)
            val runEnd = maxOf(run.startMs, run.endMs)
            parts += LyricsSyllable(run.startMs, runEnd, from, current.length)
            end = maxOf(end, runEnd)
            timed = true
            if (!glue || run.raw.last().isWhitespace()) flush()
        }
        flush()
        return words
    }

    /** A word's syllables made to cover it end to end, in order; empty when there's only one. */
    fun normalized(parts: List<LyricsSyllable>, length: Int): List<LyricsSyllable> {
        val usable = parts.filter { it.endChar > it.startChar && it.startChar < length }.sortedBy { it.startChar }
        if (usable.size < 2) return emptyList()
        return usable.mapIndexed { i, p ->
            LyricsSyllable(p.startMs, maxOf(p.startMs, p.endMs), if (i == 0) 0 else p.startChar, usable.getOrNull(i + 1)?.startChar ?: length)
        }
    }
}

/** Writing systems that need telling apart. */
object Scripts {
    /** Written without spaces between words: Han, kana, CJK punctuation and full-width forms, Thai. */
    fun unspaced(c: Char): Boolean =
        c in '㐀'..'鿿' || c in '぀'..'ヿ' || c in '　'..'〿' || c in '＀'..'￯' ||
            c in '豈'..'﫿' || c in '฀'..'๿'

    /** Han characters (Chinese, and Japanese kanji). */
    fun han(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿'

    /** Hiragana and katakana. */
    fun kana(c: Char): Boolean = c in '぀'..'ヿ' || c in 'ㇰ'..'ㇿ'

    /** Hangul syllables and letters. */
    fun hangul(c: Char): Boolean = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
}

/**
 * Which side each line of a duet is sung from, from who sings it: the side changes every time the
 * voice does (so three singers read as a conversation), a line sung by everyone together stays on the
 * first side, and a song that came out almost all on the second side is turned round.
 */
internal object Voices {
    fun sides(singers: List<String?>, types: Map<String, String>): List<Boolean> {
        var left = true
        var last: String? = null
        var right = 0
        var placed = 0
        val sides = singers.map { singer ->
            if (singer.isNullOrEmpty()) return@map false
            val type = types[singer] ?: when (singer) {
                "v1000" -> "group"
                "v2000" -> "other"
                else -> "person"
            }
            placed++
            if (type == "group") return@map false
            when {
                last == null -> left = type != "other"
                singer != last -> left = !left
            }
            last = singer
            if (!left) right++
            !left
        }
        if (placed == 0 || right.toFloat() / placed < 0.85f) return sides
        return sides.map { !it }
    }
}

/** Shorter breaks aren't worth a pause in the words. */
internal const val MIN_GAP_MS = 4_000L

/**
 * The instrumental stretches marked with empty lines, as LRC marks them with a bare stamp: only after
 * a line whose own times say when its singing stopped, so a break shows when the voice stops, never
 * between every line of a source that times lines only.
 */
internal fun List<LyricsLine>.withGaps(): List<LyricsLine> {
    if (isEmpty()) return this
    val out = ArrayList<LyricsLine>(size + 4)
    if (first().startMs >= MIN_GAP_MS) out += LyricsLine(0L, "")
    forEachIndexed { i, line ->
        out += line
        val next = getOrNull(i + 1) ?: return@forEachIndexed
        val sungUntil = maxOf(line.sungUntilMs ?: return@forEachIndexed, line.background?.sungUntilMs ?: 0L)
        if (next.startMs - sungUntil >= MIN_GAP_MS && sungUntil > line.startMs) out += LyricsLine(sungUntil, "")
    }
    return out
}
