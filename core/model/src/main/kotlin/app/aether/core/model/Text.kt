package app.aether.core.model

/**
 * [this] as AETHER sets it: without emoji. The catalogue's names are written by anyone, and a pair of
 * kissing faces on a playlist's title isn't the page's voice. Only what's shown changes: the name
 * itself, and what's sent to the phone's media controls, stay as they are. A name that's nothing but
 * emoji stays as it is. Music notes aren't emoji here, and neither are the joiners other scripts need.
 */
fun String.withoutEmoji(): String {
    var i = 0
    var found = false
    while (i < length) {
        val c = codePointAt(i)
        if (isEmoji(c)) {
            found = true
            break
        }
        i += Character.charCount(c)
    }
    if (!found) return this

    val out = StringBuilder(length)
    var afterEmoji = false
    i = 0
    while (i < length) {
        val c = codePointAt(i)
        val width = Character.charCount(c)
        val next = if (i + width < length) codePointAt(i + width) else 0
        when {
            isEmoji(c) -> afterEmoji = true
            // The joiner inside a family of emoji goes with them.
            c == ZeroWidthJoiner && (afterEmoji || isEmoji(next)) -> Unit
            else -> {
                out.appendCodePoint(c)
                afterEmoji = false
            }
        }
        i += width
    }
    val cleaned = out.toString().replace(Runs, " ").trim()
    return cleaned.ifEmpty { this }
}

private const val ZeroWidthJoiner = 0x200D
private val Runs = Regex("\\s{2,}")

private fun isEmoji(c: Int): Boolean = when (c) {
    in 0x1F000..0x1FAFF -> true
    in 0x2669..0x266F -> false // music notes, which belong
    in 0x2600..0x27BF -> true
    in 0x2B00..0x2BFF -> true
    0xFE0E, 0xFE0F, 0x20E3 -> true
    in 0xE0020..0xE007F -> true
    else -> false
}
