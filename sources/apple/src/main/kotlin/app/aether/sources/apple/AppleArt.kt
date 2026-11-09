// After BitChord's AppleArtistArt.kt and AppleCatalogue.kt (kushagrasinghx/BitChord, GPL-3.0):
// the same two public requests and the same proof, rewritten for AETHER's model and cache.
package app.aether.sources.apple

import app.aether.core.model.AlbumArt
import app.aether.core.model.AlbumArtProvider
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
 *
 * An album's page carries, for some albums, the cover as a short looping film, on the same terms.
 * There an album is believed when Apple has one of the same title by an artist of the same name, and
 * that also proves who the artist is, so the album can bring their lettering with it.
 */
class AppleArt(private val http: ArtHttp, private val cacheDir: File? = null) : ArtistArtProvider, AlbumArtProvider {

    private class Answer(val art: ArtistArt?)
    private class AlbumAnswer(val art: AlbumArt?)
    private class Genre(val name: String?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val memory = object : LinkedHashMap<String, Answer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>) = size > 256
    }

    private val albums = object : LinkedHashMap<String, AlbumAnswer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AlbumAnswer>) = size > 256
    }

    private val genres = object : LinkedHashMap<String, Genre>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Genre>) = size > 512
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

    /** The genre the iTunes search gives an artist of exactly this name; "Music" says nothing, so it counts for nothing. */
    override suspend fun genreOf(name: String): String? {
        val key = matchKey(name)
        if (key.isEmpty()) return null
        synchronized(genres) { genres[key] }?.let { return it.name }
        return try {
            withContext(Dispatchers.IO) {
                val answer = genreFromDisk(key) ?: lookupGenre(name, key)?.also { genreToDisk(key, it) } ?: return@withContext null
                synchronized(genres) { genres[key] = answer }
                answer.name
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun lookupGenre(name: String, key: String): Genre? {
        val found = http.get("https://itunes.apple.com/search?term=${URLEncoder.encode(name, "UTF-8")}&entity=musicArtist&limit=5") ?: return null
        val results = (json.parseToJsonElement(found).jsonObject["results"] as? JsonArray) ?: return Genre(null)
        val match = results.mapNotNull { it as? JsonObject }.firstOrNull { matchKey(it.str("artistName").orEmpty()) == key }
        return Genre(match?.str("primaryGenreName")?.takeIf { it.isNotBlank() && !it.equals("Music", ignoreCase = true) })
    }

    private fun genreFromDisk(key: String): Genre? {
        val file = fileFor("genre:$key")?.takeIf { it.exists() } ?: return null
        return runCatching {
            val o = json.parseToJsonElement(file.readText()).jsonObject
            val genre = o.str("genre")
            if (System.currentTimeMillis() - (o.long("at") ?: 0L) > (if (genre != null) MonthMs else WeekMs)) null else Genre(genre)
        }.getOrNull()
    }

    private fun genreToDisk(key: String, answer: Genre) {
        val file = fileFor("genre:$key") ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(buildJsonObject { put("at", System.currentTimeMillis()); answer.name?.let { put("genre", it) } }.toString())
        }
    }

    override fun cachedAlbum(id: String): AlbumArt? = synchronized(albums) { albums[id] }?.art

    override suspend fun findAlbum(id: String, title: String, artist: String, artistId: String?): AlbumArt? {
        synchronized(albums) { albums[id] }?.let { return it.art }
        return try {
            withContext(Dispatchers.IO) {
                val answer = albumFromDisk(id) ?: lookupAlbum(title, artist, artistId)?.also { albumToDisk(id, it) } ?: return@withContext null
                synchronized(albums) { albums[id] = answer }
                answer.art
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** The answer, or null when the network gave none (worth asking again, unlike "no art"). */
    private suspend fun lookupAlbum(title: String, artist: String, artistId: String?): AlbumAnswer? {
        val who = matchKey(artist)
        val what = albumKey(title)
        if (who.isEmpty() || what.isEmpty()) return albumAnswer(null, knownArtist(artistId))
        val found = http.get("https://itunes.apple.com/search?term=${URLEncoder.encode("$artist $title", "UTF-8")}&entity=album&limit=10") ?: return null
        val results = (json.parseToJsonElement(found).jsonObject["results"] as? JsonArray) ?: return albumAnswer(null, knownArtist(artistId))
        // Any edition of it will do; the one named exactly as the page's album is looked at first.
        val same = results.mapNotNull { it as? JsonObject }
            .filter { matchKey(it.str("artistName").orEmpty()) == who && albumKey(it.str("collectionName").orEmpty()) == what }
            .sortedBy { matchKey(it.str("collectionName").orEmpty()) != matchKey(title) }
        val first = same.firstOrNull() ?: return albumAnswer(null, knownArtist(artistId))
        var film: String? = null
        for (edition in same.take(FilmEditions)) {
            val page = edition.str("collectionViewUrl")?.substringBefore('?')?.takeIf { it.isNotBlank() } ?: continue
            film = albumFilm(http.get(page) ?: return null)
            if (film != null) break
        }
        return albumAnswer(film, artistOfAlbum(artistId, first))
    }

    private fun albumAnswer(film: String?, artist: ArtistArt?) =
        AlbumAnswer(if (film != null || artist?.logo != null) AlbumArt(film, artist?.logo, artist?.logoAspect ?: 1f) else null)

    /** The film is in the album's own header: nothing else on its page has this key. */
    private fun albumFilm(html: String): String? {
        val header = serverData(html)?.let { firstWith(it, "videoArtwork") } ?: return null
        return ((header["videoArtwork"] as? JsonObject)?.get("dictionary") as? JsonObject)
            ?.get("motionDetailSquare")?.let { it as? JsonObject }
            ?.str("video")?.takeIf { it.startsWith("https://") }
    }

    /** What's kept of the artist with this id here, without asking anyone. */
    private fun knownArtist(artistId: String?): ArtistArt? =
        artistId?.let { id -> (synchronized(memory) { memory[id] } ?: fromDisk(id))?.art }

    /** The album has proved who made it, so their page needs no further proof. */
    private suspend fun artistOfAlbum(artistId: String?, album: JsonObject): ArtistArt? {
        knownArtist(artistId)?.let { return it }
        val page = album.str("artistViewUrl")?.substringBefore('?')?.takeIf { it.isNotBlank() }
            ?: album.long("artistId")?.takeIf { it > 0 }?.let { "https://music.apple.com/us/artist/$it" }
            ?: return null
        val answer = Answer(parse(http.get(page) ?: return null))
        if (artistId != null && answer.art != null) {
            synchronized(memory) { memory[artistId] = answer }
            toDisk(artistId, answer)
        }
        return answer.art
    }

    /** The data Apple's public page is built from. */
    private fun serverData(html: String): JsonElement? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        return json.parseToJsonElement(html.substring(start, end))
    }

    private fun parse(html: String): ArtistArt? {
        val header = serverData(html)?.let { firstWith(it, "artistLogo") } ?: return null

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

    /** The first object anywhere in [node] that has the key [key]: on these pages, the page's header. */
    private fun firstWith(node: JsonElement?, key: String): JsonObject? {
        when (node) {
            is JsonObject -> {
                if (key in node) return node
                for (v in node.values) firstWith(v, key)?.let { return it }
            }
            is JsonArray -> for (v in node) firstWith(v, key)?.let { return it }
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

    private fun albumFromDisk(id: String): AlbumAnswer? {
        val file = fileFor("album:$id")?.takeIf { it.exists() } ?: return null
        return runCatching {
            val o = json.parseToJsonElement(file.readText()).jsonObject
            val age = System.currentTimeMillis() - (o.long("at") ?: 0L)
            val film = o.str("film")
            val logo = o.str("logo")
            val found = film != null || logo != null
            if (age > (if (found) MonthMs else WeekMs)) return@runCatching null
            if (!found) return@runCatching AlbumAnswer(null)
            AlbumAnswer(AlbumArt(film, logo, (o["aspect"] as? JsonPrimitive)?.floatOrNull ?: 1f))
        }.getOrNull()
    }

    private fun albumToDisk(id: String, answer: AlbumAnswer) {
        val file = fileFor("album:$id") ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val art = answer.art
            file.writeText(
                buildJsonObject {
                    put("at", System.currentTimeMillis())
                    art?.film?.let { put("film", it) }
                    art?.logo?.let { put("logo", it) }
                    art?.let { put("aspect", it.logoAspect) }
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

        /** How many editions of an album are looked at for a film: each is a page of half a megabyte. */
        const val FilmEditions = 2

        /** Case, accents and punctuation aren't what makes two names different; a `$` is read as an S. */
        fun matchKey(name: String): String =
            Normalizer.normalize(name.lowercase(Locale.ROOT).replace('$', 's'), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

        /** A title as both services agree on it. Too short a title ("War", "Intro") proves nothing. */
        fun titleKey(title: String): String? =
            albumKey(title).takeIf { it.length >= 4 }

        /** A release's title, whichever edition it is. Short ones count here: the artist has to match as well. */
        fun albumKey(title: String): String =
            matchKey(title.substringBefore(" (").substringBefore(" [").removeSuffix(" - Single").removeSuffix(" - EP"))
    }
}
