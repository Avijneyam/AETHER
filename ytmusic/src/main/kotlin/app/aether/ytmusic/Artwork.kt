package app.aether.ytmusic

/**
 * The address to draw these images at [sizePx] wide: the smallest one big enough, else the largest,
 * asked bigger where the image host resizes on request (album and artist art). Video stills
 * (i.ytimg.com) come in fixed sizes and are used as they are.
 */
fun List<Thumbnail>.urlFor(sizePx: Int): String? {
    val pick = firstOrNull { it.width >= sizePx } ?: lastOrNull() ?: return null
    if (pick.width >= sizePx) return pick.url
    val host = pick.url.substringAfter("://").substringBefore('/')
    if (!host.endsWith("googleusercontent.com") && !host.endsWith("ggpht.com")) return pick.url
    SIZED.find(pick.url)?.let { m ->
        val height = if (pick.width > 0 && pick.height > 0) sizePx * pick.height / pick.width else sizePx
        return pick.url.replaceRange(m.range, "=w$sizePx-h$height")
    }
    SQUARE.find(pick.url)?.let { m -> return pick.url.replaceRange(m.range, "=s$sizePx") }
    return pick.url
}

private val SIZED = Regex("=w\\d+-h\\d+")
private val SQUARE = Regex("=s\\d+")
