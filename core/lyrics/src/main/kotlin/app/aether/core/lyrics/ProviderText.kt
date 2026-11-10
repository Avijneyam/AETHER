// After BitChord's ProviderLyrics and KaraokeLrc (GPL-3.0), for AETHER's lyrics.
package app.aether.core.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whatever a lyrics library answers, read into lyrics: Apple's TTML, QQ's and NetEase's karaoke lines,
 * enhanced LRC, LRC, or plain words, as they come or wrapped in a JSON envelope or two. Untrusted
 * input: nothing here throws, and what can't be read is null.
 */
object ProviderText {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): Lyrics? {
        val content = unescapeTtml(unwrap(raw) ?: return null)
        return when {
            content.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) || content.contains("http://www.w3.org/ns/ttml", ignoreCase = true) ->
                Ttml.parse(content).takeIf { lines -> lines.any { it.text.isNotBlank() } }?.let { Lyrics.Synced(it) }
            Karaoke.looksLike(content) -> Karaoke.parse(content).takeIf { lines -> lines.any { it.text.isNotBlank() } }?.let { Lyrics.Synced(it) }
            content.trimStart().startsWith("<") -> null
            else -> LrcParser.synced(content) ?: plain(content)
        }
    }

    private fun plain(content: String): Lyrics? {
        if (content.contains(Regex("""(?i)\b(?:lyrics? (?:not found|unavailable)|error)\b"""))) return null
        return LrcParser.plain(content.lineSequence().filterNot { it.trim().matches(Regex("""\[[A-Za-z]+:.*]""")) }.joinToString("\n"))
    }

    /** The lyric text out of the envelopes some libraries wrap it in. */
    fun unwrap(raw: String): String? {
        var value = raw.replace("﻿", "").trim()
        if (value.startsWith("```")) {
            value = value.lineSequence().drop(1).toList().let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }.joinToString("\n").trim()
        }
        if (value.isBlank()) return null
        val element = runCatching { json.parseToJsonElement(value) }.getOrNull() ?: return value
        return extract(element)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extract(e: JsonElement): String? = when (e) {
        JsonNull -> null
        is JsonPrimitive -> if (e.isString) {
            val text = e.content.trim()
            val nested = runCatching { json.parseToJsonElement(text) }.getOrNull()
            if (nested != null && nested !is JsonPrimitive) extract(nested) else text
        } else null
        is JsonArray -> e.mapNotNull(::extract).joinToString("\n").takeIf { it.isNotBlank() }
        is JsonObject -> {
            if (e["isError"]?.toString() == "true" || e["ok"]?.toString() == "false" ||
                e["error"]?.let { it !is JsonNull && it.toString() !in setOf("false", "\"\"", "null") } == true
            ) null
            else CONTENT_KEYS.asSequence().mapNotNull { e[it]?.let(::extract) }.firstOrNull()
                ?: (e["metadata"] as? JsonObject)?.let(::extract)
        }
    }

    private fun unescapeTtml(value: String): String = if (value.contains("&lt;tt", ignoreCase = true)) {
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
    } else value

    private val CONTENT_KEYS = listOf("ttml", "ttmlContent", "lyrics", "lrc", "content", "text", "plainLyrics", "syncedLyrics", "line", "lines", "lyric", "data", "result", "response")
}

/** QQ's QRC and NetEase's YRC: lines and syllables as millisecond ranges, `[start,length]` and `(start,length)`. */
internal object Karaoke {
    private val LINE = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val PREFIX_WORD = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
    private val SUFFIX_WORD = Regex("""([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)""")
    private val WORD_TIME = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    private val CONTENT = Regex("""LyricContent\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    fun looksLike(raw: String): Boolean = content(raw).lineSequence().any { line ->
        LINE.matchEntire(line.trim())?.groupValues?.get(3)?.let { PREFIX_WORD.containsMatchIn(it) || SUFFIX_WORD.containsMatchIn(it) } == true
    }

    fun parse(raw: String): List<LyricsLine> = content(raw).lineSequence().mapNotNull { source ->
        val m = LINE.matchEntire(source.trim()) ?: return@mapNotNull null
        val lineStart = m.groupValues[1].toLong()
        val lineLength = m.groupValues[2].toLong()
        val body = m.groupValues[3]
        val prefixed = Runs.words(PREFIX_WORD.findAll(body).map { w -> run(w.groupValues[3], w.groupValues[1], w.groupValues[2]) }.toList())
        val suffixed = Runs.words(SUFFIX_WORD.findAll(body).map { w -> run(w.groupValues[1], w.groupValues[2], w.groupValues[3]) }.toList())
        val words = if (prefixed.sumOf { it.text.length } >= suffixed.sumOf { it.text.length }) prefixed else suffixed
        if (words.isEmpty()) return@mapNotNull null
        LyricsLine(
            startMs = minOf(lineStart, words.first().startMs),
            text = words.joinToString(" ") { it.text },
            endMs = (lineStart + lineLength).takeIf { lineLength > 0 },
            words = words,
        )
    }.filter { it.text.isNotEmpty() }.sortedBy { it.startMs }.toList().withGaps()

    private fun run(text: String, start: String, length: String): Run {
        val s = start.toLong()
        return Run(s, s + length.toLong(), decode(text))
    }

    private fun decode(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun content(raw: String): String = CONTENT.find(raw)?.groupValues?.get(1)?.let(::decode) ?: raw
}
