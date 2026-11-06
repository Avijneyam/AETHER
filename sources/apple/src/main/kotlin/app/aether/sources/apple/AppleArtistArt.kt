// After BitChord's AppleArtistArt.kt and AppleCatalogue.kt (kushagrasinghx/BitChord, GPL-3.0):
// the same two public requests and the same proof, rewritten for AETHER's model and cache.
package app.aether.sources.apple

import app.aether.core.model.ArtistArt
import app.aether.core.model.ArtistArtProvider
import app.aether.core.model.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** A GET: the body, or null for anything but a success. Failing the network here is never an error. */
fun interface ArtHttp {
    suspend fun get(url: String): String?
}

/**
 * What Apple Music's artist page shows at its top, found from nothing but an artist's name: the
 * iTunes search API turns the name into an Apple artist, and that artist's public page carries a
 * portrait, for some artists their name lettered as a transparent picture, and for some a short
 * looping film (video only, unencrypted HLS).
 *
 * That page's data isn't an API Apple publishes, so every step treats a surprise as "no art", and
 * the source's own picture is always underneath. A name is not an identity (Apple has a Krsna who
 * isn't YouTube's KR$NA), so an Apple artist is only believed when their catalogue shares a song or
 * release title with the page asking. Answers are kept, misses included, so a page asks once.
 */
class AppleArtistArt(private val http: ArtHttp, private val cacheDir: File? = null) : ArtistArtProvider {

    private class Answer(val art: ArtistArt?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val memory = object : LinkedHashMap<String, Answer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>) = size > 256
    }

    override fun cached(id: String): ArtistArt? = synchronized(memory) { memory[id] }?.art

    override suspend fun find(id: String, name: String, titles: Collection<String>): ArtistArt? {
        synchronized(memory) { memory[id] }?.let { return it.art }
        return try {
            withContext(Dispatchers.IO) {
                val answer = fromDisk(id) ?: lookup(name, titles)?.also { toDisk(id, it) } ?: return@withContext null
                synchronized(memory) { memory[id] = answer }
                answer.art
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** The answer, or null when the network gave none (worth asking again, unlike "no art"). */
    private suspend fun lookup(name: String, titles: Collection<String>): Answer? {
        val key = matchKey(name)
        val proofs = titles.mapNotNullTo(HashSet(), ::titleKey)
        if (key.isEmpty() || proofs.isEmpty()) return Answer(null)
        val found = http.get("https://itunes.apple.com/search?term=${URLEncoder.encode(name, "UTF-8")}&entity=musicArtist&limit=5") ?: return null
        val results = (json.parseToJsonElement(found).jsonObject["results"] as? JsonArray) ?: return Answer(null)
        var page: String? = null
        for (result in results) {
            val r = result as? JsonObject ?: continue
            if (matchKey(r.str("artistName").orEmpty()) != key) continue
            val artist = r.long("artistId")?.takeIf { it > 0 } ?: continue
            // Every one of that name is checked: the one Apple ranks highest is often the wrong one of two.
            if (!sharesTitles(artist, proofs)) continue
            page = r.str("artistLinkUrl")?.substringBefore('?')?.takeIf { it.isNotBlank() } ?: "https://music.apple.com/us/artist/$artist"
            break
        }
        if (page == null) return Answer(null)
        val html = http.get(page) ?: return null
        return Answer(parse(html))
    }

    private suspend fun sharesTitles(artist: Long, proofs: Set<String>): Boolean {
        val body = http.get("https://itunes.apple.com/lookup?id=$artist&entity=song&limit=200") ?: return false
        val rows = json.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return false
        return rows.any { row ->
            val o = row as? JsonObject ?: return@any false
            titleKey(o.str("trackName").orEmpty()) in proofs || titleKey(o.str("collectionName").orEmpty()) in proofs
        }
    }

    private fun parse(html: String): ArtistArt? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        val header = findHeader(json.parseToJsonElement(html.substring(start, end))) ?: return null

        val hero = ((header["artwork"] ?: header["colorBackdropArtwork"]) as? JsonObject)?.get("dictionary") as? JsonObject ?: return null
        val heroW = hero.int("width")?.takeIf { it > 0 } ?: return null
        val heroH = hero.int("height")?.takeIf { it > 0 } ?: return null
        val width = minOf(PortraitPx, heroW)
        val height = (width.toLong() * heroH / heroW).toInt()
        val portrait = hero.str("url")?.fill(width, height, "jpg") ?: return null

        // Absent for most artists: the key is there and null.
        val lettering = (header["artistLogo"] as? JsonObject)?.get("dictionary") as? JsonObject
        val logoW = lettering?.int("width") ?: 0
        val logoH = lettering?.int("height") ?: 0
        val logo = if (logoW > 0 && logoH > 0) lettering?.str("url")?.fill(LogoPx, (LogoPx.toLong() * logoH / logoW).toInt(), "png") else null

        val film = ((header["videoArtwork"] as? JsonObject)?.get("dictionary") as? JsonObject)
            ?.get("motionArtistSquare1x1")?.let { it as? JsonObject }
            ?.str("video")?.takeIf { it.startsWith("https://") }

        return ArtistArt(
            picture = Artwork.single(portrait, width, height),
            logo = logo,
            logoAspect = if (logo != null) logoW.toFloat() / logoH else 1f,
            film = film,
        )
    }

    /** The first object anywhere in [node] that has an `artistLogo` key: the page's header. */
    private fun findHeader(node: JsonElement?): JsonObject? {
        when (node) {
            is JsonObject -> {
                if ("artistLogo" in node) return node
                for (v in node.values) findHeader(v)?.let { return it }
            }
            is JsonArray -> for (v in node) findHeader(v)?.let { return it }
            else -> Unit
        }
        return null
    }

    // --- Kept on the phone, so an artist seen before has their art from the first frame. ---

    private fun fileFor(id: String): File? {
        val dir = cacheDir ?: return null
        val name = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.json")
    }

    private fun fromDisk(id: String): Answer? {
        val file = fileFor(id)?.takeIf { it.exists() } ?: return null
        return runCatching {
            val o = json.parseToJsonElement(file.readText()).jsonObject
            val age = System.currentTimeMillis() - (o.long("at") ?: 0L)
            val portrait = o.str("portrait")
            // A found answer keeps for a month; "nothing" for a week, as an artist's page can gain art.
            if (age > (if (portrait != null) MonthMs else WeekMs)) return@runCatching null
            if (portrait == null) return@runCatching Answer(null)
            Answer(
                ArtistArt(
                    picture = Artwork.single(portrait, o.int("w") ?: PortraitPx, o.int("h") ?: PortraitPx),
                    logo = o.str("logo"),
                    logoAspect = (o["aspect"] as? JsonPrimitive)?.floatOrNull ?: 1f,
                    film = o.str("film"),
                ),
            )
        }.getOrNull()
    }

    private fun toDisk(id: String, answer: Answer) {
        val file = fileFor(id) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val art = answer.art
            val size = art?.picture?.sizes?.firstOrNull()
            file.writeText(
                buildJsonObject {
                    put("at", System.currentTimeMillis())
                    if (art != null && size != null) {
                        put("portrait", size.url)
                        put("w", size.width)
                        put("h", size.height)
                        art.logo?.let { put("logo", it) }
                        put("aspect", art.logoAspect)
                        art.film?.let { put("film", it) }
                    }
                }.toString(),
            )
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun String.fill(w: Int, h: Int, format: String): String? {
        if (isBlank() || !contains("{w}")) return null
        return replace("{w}", w.toString()).replace("{h}", h.toString()).replace("{c}", "bb").replace("{f}", format)
    }

    private companion object {
        const val PortraitPx = 1400
        const val LogoPx = 1000
        const val WeekMs = 7L * 24 * 60 * 60 * 1000
        const val MonthMs = 30L * 24 * 60 * 60 * 1000

        /** Case, accents and punctuation aren't what makes two names different; a `$` is read as an S. */
        fun matchKey(name: String): String =
            Normalizer.normalize(name.lowercase(Locale.ROOT).replace('$', 's'), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

        /** A title as both services agree on it. Too short a title ("War", "Intro") proves nothing. */
        fun titleKey(title: String): String? =
            matchKey(title.substringBefore(" (").substringBefore(" [").removeSuffix(" - Single").removeSuffix(" - EP")).takeIf { it.length >= 4 }
    }
}
