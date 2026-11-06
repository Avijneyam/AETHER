package app.aether.sources.addon

import app.aether.core.model.ArtistRef
import app.aether.core.model.AlbumRef
import app.aether.core.model.Capability
import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.SearchFilter
import app.aether.core.model.SearchResults
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import app.aether.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * A source the listener added by its address (PODIUM's and LastWave's addon protocol): an addon
 * answers `/manifest.json` (who it is), `/search?q=` (songs, with its own ids) and `/stream/{id}`
 * (where a song's audio is). Its songs play like any other.
 *
 * **Open media only.** A stream the addon marks encrypted, or that needs a licence, is refused: the
 * app never decrypts anything (architecture.md §4–5). AETHER ships no sources.
 */
class AddonSource(val address: String, private val http: OkHttpClient, val manifest: AddonManifest) : MusicSource {
    override val id: String = idFor(address)
    override val name: String = manifest.name
    override val capabilities = buildSet {
        if (manifest.canSearch) add(Capability.SEARCH)
        if (manifest.canStream) add(Capability.STREAM)
    }

    override suspend fun search(query: String, filter: SearchFilter?): Outcome<SearchResults> {
        if (!manifest.canSearch) return Outcome.Err(SourceError.Unsupported)
        val url = endpoint("search")?.newBuilder()?.addQueryParameter("q", query)?.build() ?: return Outcome.Err(SourceError.NotFound("address"))
        return fetch(url.toString()) { bytes ->
            val tracks = AddonProtocol.search(bytes)?.map { t ->
                Track(
                    id = MediaId("$id:${t.id}"),
                    title = t.title,
                    artists = listOfNotNull(t.artist?.let { ArtistRef(it) }),
                    album = t.album?.let { AlbumRef(it) },
                    durationMs = t.durationMs,
                    explicit = t.explicit == true,
                    subtitle = manifest.name,
                )
            } ?: return@fetch null
            SearchResults(tracks, emptyList(), null)
        }
    }

    override suspend fun stream(id: MediaId, efficient: Boolean): Outcome<Stream> {
        if (!manifest.canStream) return Outcome.Err(SourceError.Unsupported)
        val own = id.value.substringAfter(':')
        val url = endpoint("stream")?.newBuilder()?.addPathSegment(own)?.build() ?: return Outcome.Err(SourceError.NotFound("address"))
        val answer = fetch(url.toString()) { AddonProtocol.stream(it) }
        val stream = (answer as? Outcome.Ok)?.value ?: return answer as Outcome.Err
        if (stream.encrypted) return Outcome.Err(SourceError.NotPlayable("This source's stream is encrypted, and AETHER plays open media only."))
        if (!stream.url.startsWith("https://") && !stream.url.startsWith("http://")) return Outcome.Err(SourceError.NotPlayable("The source gave no address to play."))
        return Outcome.Ok(Stream(url = stream.url, headers = stream.headers, mimeType = stream.mimeType, expiresAtMs = stream.expiresAtMillis, cacheKey = id.value))
    }

    private fun endpoint(path: String) = base(address)?.newBuilder()?.addPathSegments(path)?.build()

    private suspend fun <T> fetch(url: String, read: (ByteArray) -> T?): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext Outcome.Err(SourceError.Server(response.code))
                val bytes = response.body?.bytes() ?: return@withContext Outcome.Err(SourceError.Server(null))
                read(bytes)?.let { Outcome.Ok(it) } ?: Outcome.Err(SourceError.Server(null))
            }
        } catch (e: IOException) {
            Outcome.Err(SourceError.Network)
        }
    }

    companion object {
        /** A stable id for an address: `addon-` and a short hash, so its songs' ids survive a restart. */
        fun idFor(address: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(address.trim().trimEnd('/').toByteArray())
            return "addon-" + digest.take(5).joinToString("") { "%02x".format(it) }
        }

        /** The addon's base: the address itself, or the folder of a `.json` address (`…/manifest.json`). */
        fun base(address: String) = address.trim().trimEnd('/').let { a -> if (a.endsWith(".json")) a.substringBeforeLast('/') else a }.toHttpUrlOrNull()

        /** Asks an address for its manifest: the addon it is, or why not. */
        suspend fun connect(address: String, http: OkHttpClient): Outcome<AddonSource> = withContext(Dispatchers.IO) {
            val root = base(address) ?: return@withContext Outcome.Err(SourceError.NotFound("address"))
            if (root.scheme != "https" && root.host != "localhost") return@withContext Outcome.Err(SourceError.NotPlayable("Sources are reached over https only."))
            try {
                http.newCall(Request.Builder().url(root.newBuilder().addPathSegment("manifest.json").build()).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext Outcome.Err(SourceError.Server(response.code))
                    val manifest = response.body?.bytes()?.let { AddonProtocol.manifest(it) } ?: return@withContext Outcome.Err(SourceError.Server(null))
                    Outcome.Ok(AddonSource(address.trim(), http, manifest))
                }
            } catch (e: IOException) {
                Outcome.Err(SourceError.Network)
            }
        }
    }
}
