package app.aether.ui.lyrics

import android.icu.text.Transliterator
import app.aether.core.lyrics.Hangul
import app.aether.core.lyrics.LyricsLine
import app.aether.core.lyrics.Scripts

/**
 * Words in another script given in Latin letters, worked out on the phone: nothing is sent anywhere
 * (BitChord and LastWave ask Google Translate for this; AETHER asks no one).
 *
 * - **Korean**, in the Revised Romanization of Korean as it's said, not letter by letter: a syllable's
 *   final consonant carries over to the next ("먹어" is meogeo), meets the next as speech meets it
 *   ("합니다" is hamnida, "신라" silla, "좋다" jota), and ㄹ is r before a vowel, l at a syllable's end.
 * - **Chinese**, in Hanyu Pinyin with its tones, a syllable to a character (Android's own ICU).
 * - **Japanese** where it's written in kana, in Hepburn. Kanji can't be read on the phone without a
 *   dictionary (their reading depends on the word), so a line with kanji in a Japanese song is left
 *   as it is rather than given another language's readings.
 * - **Everything else** (Cyrillic, Greek, Arabic, Hebrew, the Indian scripts, Thai…) through ICU's
 *   own transliteration.
 *
 * Latin text, numbers and punctuation pass through. A line with nothing to romanise is null.
 */
object Romaniser {
    private val han by lazy { runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull() }
    private val kana by lazy { runCatching { Transliterator.getInstance("Hiragana-Latin; Katakana-Latin") }.getOrNull() }
    private val any by lazy { runCatching { Transliterator.getInstance("Any-Latin") }.getOrNull() }

    /** Whether [text] has letters in a script other than Latin. */
    fun needs(text: String): Boolean = text.any { c -> c.isLetter() && !latin(c) }

    /** Whether a song's words are Japanese: kana anywhere in them. */
    fun japanese(lines: List<String>): Boolean = lines.any { line -> line.any(Scripts::kana) }

    /**
     * [text] in Latin letters, or null when nothing in it needs it (or it can't be, as a Japanese line
     * with kanji). [japanese] says the song is Japanese, so that its kanji aren't read as Chinese.
     */
    fun romanise(text: String, japanese: Boolean = false): String? {
        if (!needs(text)) return null
        if (japanese && text.any(Scripts::han)) return null
        val out = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Scripts.hangul(c) -> {
                    val from = i
                    while (i < text.length && Scripts.hangul(text[i])) i++
                    space(out)
                    out.append(Hangul.romanise(text.substring(from, i)))
                    continue
                }
                Scripts.han(c) && !japanese -> {
                    // A syllable to a character, a space between: "我爱你" is "wǒ ài nǐ".
                    space(out)
                    out.append(han?.transliterate(c.toString()) ?: return null)
                    out.append(' ')
                }
                Scripts.kana(c) -> {
                    val from = i
                    while (i < text.length && (Scripts.kana(text[i]) || text[i] == 'ー')) i++
                    space(out)
                    out.append(kana?.transliterate(text.substring(from, i)) ?: return null)
                    out.append(' ')
                    continue
                }
                c.isLetter() && !latin(c) -> {
                    val from = i
                    while (i < text.length && text[i].isLetter() && !latin(text[i]) && !Scripts.hangul(text[i]) && !Scripts.han(text[i]) && !Scripts.kana(text[i])) i++
                    out.append(any?.transliterate(text.substring(from, i)) ?: return null)
                    continue
                }
                else -> out.append(WIDE[c] ?: c)
            }
            i++
        }
        return out.toString().replace(SPACES, " ").replace(BEFORE_PUNCTUATION, "$1").trim().takeIf { it.isNotEmpty() && it != text }
    }

    /** Each line's words in Latin letters, index for index; null where a line needs none or can't have it. */
    fun lines(lines: List<LyricsLine>): List<String?> {
        val japanese = japanese(lines.map { it.text })
        return lines.map { romanise(it.text, japanese) }
    }

    private fun space(out: StringBuilder) {
        if (out.isNotEmpty() && !out.last().isWhitespace() && out.last() !in "(“\"'¿¡") out.append(' ')
    }

    private fun latin(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    private val SPACES = Regex("""\s+""")
    private val BEFORE_PUNCTUATION = Regex("""\s+([,.!?;:)”"'])""")

    /** CJK punctuation as it's written in Latin text. */
    private val WIDE = mapOf(
        '，' to ',', '。' to '.', '！' to '!', '？' to '?', '、' to ',', '：' to ':', '；' to ';',
        '（' to '(', '）' to ')', '「' to '“', '」' to '”', '『' to '“', '』' to '”', '～' to '~', '…' to '…', '　' to ' ',
    )
}
