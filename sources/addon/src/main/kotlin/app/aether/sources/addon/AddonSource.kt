package app.aether.sources.addon

import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.Capability
import app.aether.core.model.CopySource
import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.SearchFilter
import app.aether.core.model.SearchResults
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import app.aether.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A source the listener added by its address, speaking the addon protocol BitChord, LastWave and
 * PODIUM share: an addon answers `/manifest.json` (who it is), `/search?q=` (songs, with its own
 * ids) and `/stream/{id}` (where a song's audio is). Its songs play like any other, and for a song
 * from YouTube Music it's asked for an exact copy first ([exactCopy], architecture.md §4), which plays
 * in its place: lossless, when the addon has it.
 *
 * - Every request carries the addon's declared settings at the listener's values or their defaults,
 *   and `quality` at the best the addon lists ([AddonManifest.query]), as BitChord's host sends them:
 *   asked for nothing, an addon answers at its own default, which is often not its best.
 * - A row may carry its own stream (`streamURL`); a stream may be a file, or a DASH or HLS manifest.
 * - An addon that fails or asks for a pause rests a while ([ready]), so a song doesn't wait on it.
 *
 * **Open media only.** A stream the addon marks encrypted, or a manifest that names a protection
 * scheme or a key, is refused: the app never decrypts anything (architecture.md §4–5). An addon that
 * answers only BitChord's own app is refused when it's added. AETHER ships no sources.
 */
class AddonSource(
    val address: String,
    private val http: OkHttpClient,
    val manifest: AddonManifest,
    /** The listener's choices for this addon's settings. */
    private val choices: () -> Map<String, String> = { emptyMap() },
    private val now: () -> Long = System::currentTimeMillis,
) : MusicSource, CopySource {
    override val id: String = idFor(address)
    override val name: String = manifest.name
    override val capabilities = buildSet {
        if (manifest.canSearch) add(Capability.SEARCH)
        if (manifest.canStream) add(Capability.STREAM)
    }

    /** Until when it rests after failing (milliseconds, wall clock). */
    @Volatile private var restUntil = 0L
    @Volatile private var failures = 0

    /** Streams already asked for, by the addon's own id and settings: one song played twice asks once. */
    private val streams = ConcurrentHashMap<String, Pair<Stream, Long>>()

    override val ready: Boolean get() = now() >= restUntil && manifest.canSearch && manifest.canStream

    /** Why it's resting, for Settings: null when it isn't. */
    val trouble: String? get() = if (now() < restUntil) lastTrouble else null
    @Volatile private var lastTrouble: String? = null

    override suspend fun search(query: String, filter: SearchFilter?): Outcome<SearchResults> {
        if (!manifest.canSearch) return Outcome.Err(SourceError.Unsupported)
        return when (val r = searchRows(query)) {
            is Outcome.Ok -> Outcome.Ok(SearchResults(r.value.map(::trackOf), emptyList(), null))
            is Outcome.Err -> r
        }
    }

    override suspend fun stream(id: MediaId, efficient: Boolean): Outcome<Stream> {
        if (!manifest.canStream) return Outcome.Err(SourceError.Unsupported)
        val own = id.value.substringAfter(':')
        return streamOf(own, null, id.value)
    }

    override suspend fun exactCopy(track: Track): Stream? {
        if (!ready) return null
        val seen = LinkedHashMap<String, AddonTrack>()
        for (query in ExactCopy.queries(track)) {
            when (val r = searchRows(query)) {
                is Outcome.Ok -> r.value.forEach { seen.putIfAbsent(it.id, it) }
                is Outcome.Err -> return null
            }
            // A row that's the song settles it; otherwise the plainer query may find it.
            ExactCopy.best(track, seen.values.toList())?.let { row ->
                return (streamOf(row.id, row.streamUrl, "$id:${row.id}") as? Outcome.Ok)?.value?.copy(servedBy = name)
            }
        }
        return null
    }

    private suspend fun searchRows(query: String): Outcome<List<AddonTrack>> {
        val url = endpoint(listOf("search"))?.newBuilder()?.addQueryParameter("q", query)?.build() ?: return Outcome.Err(SourceError.NotFound("address"))
        return fetch(url) { AddonProtocol.search(it) }
    }

    /** The stream of the addon's own song [own], checked to be open media; [given] is the row's own address, if it had one. */
    private suspend fun streamOf(own: String, given: String?, cacheKey: String): Outcome<Stream> {
        val key = "$own|${settingsKey()}"
        streams[key]?.let { (stream, at) -> if (now() - at < STREAM_TTL_MS && (stream.expiresAtMs ?: Long.MAX_VALUE) > now() + 60_000) return Outcome.Ok(stream) }
        val answer: AddonStream = if (given != null) {
            AddonStream(given, null, null, null, false, emptyMap(), null)
        } else {
            val url = endpoint(listOf("stream", own)) ?: return Outcome.Err(SourceError.NotFound("address"))
            when (val r = fetch(url) { AddonProtocol.stream(it) }) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        }
        if (answer.error != null || answer.url.isBlank()) return Outcome.Err(SourceError.NotPlayable(answer.error?.take(160) ?: "no stream"))
        if (answer.encrypted) return Outcome.Err(SourceError.NotPlayable("This source's stream is encrypted, and AETHER plays open media only."))
        if (!answer.url.startsWith("https://") && !answer.url.startsWith("http://")) return Outcome.Err(SourceError.NotPlayable("The source gave no address to play."))
        val manifest = when (answer.manifest) {
            "dash" -> Stream.Manifest.DASH
            "hls" -> Stream.Manifest.HLS
            else -> if (answer.url.substringBefore('?').endsWith(".mpd", true)) Stream.Manifest.DASH
            else if (answer.url.substringBefore('?').endsWith(".m3u8", true)) Stream.Manifest.HLS
            else Stream.Manifest.NONE
        }
        if (manifest != Stream.Manifest.NONE && protectedManifest(answer.url, answer.headers, manifest)) {
            return Outcome.Err(SourceError.NotPlayable("This source's stream is protected, and AETHER plays open media only."))
        }
        val stream = Stream(
            url = answer.url,
            headers = answer.headers,
            mimeType = answer.mimeType,
            codec = answer.codec,
            bitrateKbps = answer.bitrateKbps,
            sampleRateHz = answer.sampleRateHz,
            bitDepth = answer.bitDepth,
            expiresAtMs = answer.expiresAtMillis,
            cacheKey = cacheKey,
            manifest = manifest,
        )
        streams[key] = stream to now()
        return Outcome.Ok(stream)
    }

    /**
     * Whether a manifest names a protection scheme (DASH's `ContentProtection`) or a key (HLS's
     * `EXT-X-KEY` or `EXT-X-SESSION-KEY` with any method but NONE): such a stream needs a licence or
     * a key, and AETHER plays open media only. One that can't be read is taken as refused.
     */
    private suspend fun protectedManifest(url: String, headers: Map<String, String>, kind: Stream.Manifest): Boolean {
        val text = text(url, headers) ?: return true
        return when (kind) {
            Stream.Manifest.DASH -> "ContentProtection" in text || "cenc:" in text
            Stream.Manifest.HLS -> {
                if (keyed(text)) return true
                // A master playlist names its media playlists; the keys are in those.
                val media = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
                val next = url.toHttpUrlOrNull()?.resolve(media)?.toString() ?: return true
                val inner = text(next, headers) ?: return true
                keyed(inner)
            }
            Stream.Manifest.NONE -> false
        }
    }

    private fun keyed(m3u8: String): Boolean = m3u8.lineSequence().any { line ->
        val l = line.trim()
        (l.startsWith("#EXT-X-KEY") || l.startsWith("#EXT-X-SESSION-KEY")) && !l.contains("METHOD=NONE")
    }

    private suspend fun text(url: String, headers: Map<String, String>): String? = runCatching {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(request).await().use { r -> if (r.isSuccessful) r.body?.source()?.let { s -> s.request(MAX_MANIFEST_BYTES); s.buffer.clone().readUtf8() } else null }
    }.getOrNull()

    private fun trackOf(t: AddonTrack) = Track(
        id = MediaId("$id:${t.id}"),
        title = t.title,
        artists = listOfNotNull(t.artist?.let { ArtistRef(it) }),
        album = t.album?.let { AlbumRef(it) },
        durationMs = t.durationMs,
        explicit = t.explicit == true,
        subtitle = manifest.name,
    )

    private fun settingsKey(): String = manifest.query(choices()).toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    /** `{base}/{segments…}?{settings}`, every part encoded; the address's own query (a token) kept. */
    private fun endpoint(segments: List<String>): HttpUrl? {
        val base = base(address) ?: return null
        val b = base.newBuilder()
        segments.forEach(b::addPathSegment)
        manifest.query(choices()).forEach { (k, v) -> if (base.queryParameter(k) == null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun <T> fetch(url: HttpUrl, read: (ByteArray) -> T?): Outcome<T> {
        if (now() < restUntil) return Outcome.Err(SourceError.RateLimited(restUntil - now()))
        return try {
            val outcome = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).await().use { response ->
                    when {
                        response.code == 401 && response.header("WWW-Authenticate")?.trimStart()?.startsWith("BitChord", true) == true -> {
                            rest(REST_LONG_MS, "It answers only the BitChord app.")
                            Outcome.Err(SourceError.AuthRequired)
                        }
                        response.code == 429 -> {
                            val after = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                            rest((after ?: REST_MS).coerceIn(5_000L, REST_LONG_MS), "It asked to be asked less often.")
                            Outcome.Err(SourceError.RateLimited(after))
                        }
                        // On `/stream`, a 404 is the protocol's "not here": a miss, not a fault.
                        response.code == 404 -> Outcome.Err(SourceError.NotFound("song"))
                        !response.isSuccessful -> {
                            if (response.code >= 500) failed("It answered with an error (${response.code}).")
                            Outcome.Err(SourceError.Server(response.code))
                        }
                        else -> {
                            val bytes = response.body?.bytes()
                            val value = bytes?.let(read)
                            if (value != null) { failures = 0; Outcome.Ok(value) } else Outcome.Err(SourceError.Server(null))
                        }
                    }
                }
            }
            outcome ?: run { failed("It didn't answer in time."); Outcome.Err(SourceError.Network) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            failed("It couldn't be reached.")
            Outcome.Err(SourceError.Network)
        }
    }

    /** A failure: after two in a row it rests, longer each time, so songs don't wait on an addon that's down. */
    private fun failed(why: String) {
        failures++
        if (failures >= 2) rest(minOf(REST_MS shl minOf(failures - 2, 4), REST_LONG_MS), why)
    }

    private fun rest(ms: Long, why: String) {
        restUntil = now() + ms
        lastTrouble = why
    }

    companion object {
        /** A stable id for an address: `addon-` and a short hash, so its songs' ids survive a restart. */
        fun idFor(address: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(address.trim().trimEnd('/').toByteArray())
            return "addon-" + digest.take(5).joinToString("") { "%02x".format(it) }
        }

        /** The addon's base: the address itself, or the folder of a `.json` address (`…/manifest.json`), its query kept. */
        fun base(address: String): HttpUrl? {
            val url = address.trim().toHttpUrlOrNull() ?: return null
            val b = url.newBuilder()
            var size = url.pathSize
            // Trailing slashes off, then a final `….json`: what's left is the addon's folder.
            while (size > 1 && url.pathSegments[size - 1].isEmpty()) b.removePathSegment(--size)
            if (size > 0 && url.pathSegments[size - 1].endsWith(".json", ignoreCase = true)) b.removePathSegment(size - 1)
            return b.fragment(null).build()
        }

        /** Asks an address for its manifest: the addon it is, or why not. */
        suspend fun connect(address: String, http: OkHttpClient, choices: () -> Map<String, String> = { emptyMap() }): Outcome<AddonSource> {
            val root = base(address) ?: return Outcome.Err(SourceError.NotFound("address"))
            if (root.scheme != "https" && root.host != "localhost") return Outcome.Err(SourceError.NotPlayable("Sources are reached over https only."))
            val manifestUrl = root.newBuilder().addPathSegment("manifest.json").build()
            return try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    http.newCall(Request.Builder().url(manifestUrl).header("Accept", "application/json").build()).await().use { response ->
                        if (!response.isSuccessful) return@use Outcome.Err(SourceError.Server(response.code))
                        val manifest = response.body?.bytes()?.let { AddonProtocol.manifest(it) } ?: return@use Outcome.Err(SourceError.Server(null))
                        when {
                            manifest.onlyForBitChord -> Outcome.Err(SourceError.NotPlayable("${manifest.name} answers only the BitChord app, which proves who it is with a key Android vouches for. AETHER can't, and doesn't pretend to."))
                            !manifest.canStream -> Outcome.Err(SourceError.NotPlayable("${manifest.name} doesn't offer streams, so it can't play songs."))
                            !manifest.canSearch -> Outcome.Err(SourceError.NotPlayable("${manifest.name} can't search, so AETHER can't find songs on it."))
                            else -> Outcome.Ok(AddonSource(address.trim(), http, manifest, choices))
                        }
                    }
                } ?: Outcome.Err(SourceError.Network)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Outcome.Err(SourceError.Network)
            }
        }

        /** How long one request may take: a song waits on the addon at most this long. */
        const val REQUEST_TIMEOUT_MS = 8_000L

        /** How long an address the addon gave is used again without asking (when it doesn't say). */
        const val STREAM_TTL_MS = 10 * 60_000L

        const val REST_MS = 30_000L
        const val REST_LONG_MS = 15 * 60_000L
        const val MAX_MANIFEST_BYTES = 512L * 1024
    }
}

/** An OkHttp call that a coroutine's cancellation really stops (a blocking `execute` can't be). */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
