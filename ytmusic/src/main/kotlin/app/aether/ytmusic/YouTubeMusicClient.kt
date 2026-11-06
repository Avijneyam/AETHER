package app.aether.ytmusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * The YouTube Music web catalogue (from PODIUM): the JSON endpoints the music.youtube.com page itself
 * uses — browse, search, suggestions, the up-next list, likes and the account menu. It never asks
 * for streams; playback is a separate concern (docs/architecture.md).
 *
 * The client identifies as the music web client (`WEB_REMIX`), at the version the page itself
 * reports (read from the page at most every few hours), with the page's guest visitor id.
 *
 * **Region.** Home and Explore follow where the listener actually is (their network), whatever
 * [region] says — measured 2026-11-03 (docs/ytmusic-verification.md). [region] still goes with every
 * request as the page sends it; Charts are the one page that takes a country of the listener's
 * choosing (`formData`).
 */
internal class YouTubeMusicClient(
    private val transport: HttpTransport,
    private val userAgent: () -> String,
    private val session: () -> WebSession?,
    private val language: () -> String,
    private val region: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /** The channel being acted as (its page id), or null for the account's own. */
    private val onBehalfOf: () -> String? = { null },
) {
    private data class PageConfig(val clientVersion: String, val visitorData: String?, val loadedAt: Long)

    private val configLock = Mutex()
    @Volatile private var config: PageConfig? = null

    // --- Endpoints -----------------------------------------------------------------------------------

    suspend fun browse(
        browseId: String,
        params: String? = null,
        continuation: Continuation? = null,
        formData: JsonObject? = null,
    ): YtResult<JsonObject> =
        call("browse", continuation) {
            if (continuation == null) {
                put("browseId", browseId)
                params?.let { put("params", it) }
                formData?.let { put("formData", it) }
            }
        }

    suspend fun search(query: String, params: String? = null, continuation: Continuation? = null): YtResult<JsonObject> =
        call("search", continuation) {
            if (continuation == null) {
                put("query", query)
                params?.let { put("params", it) }
            }
        }

    suspend fun searchSuggestions(input: String): YtResult<JsonObject> =
        call("music/get_search_suggestions", null) { put("input", input) }

    /** The watch-next list: a song's details, the queue (or radio) that follows it, its lyrics and related tabs. */
    suspend fun next(videoId: String?, playlistId: String?, params: String? = null, continuation: Continuation? = null): YtResult<JsonObject> =
        call("next", continuation) {
            if (continuation == null) {
                videoId?.let { put("videoId", it) }
                playlistId?.let { put("playlistId", it) }
                params?.let { put("params", it) }
                put("isAudioOnly", true)
                put("enablePersistentPlaylistPanel", true)
            }
        }

    suspend fun like(videoId: String, liked: Boolean): YtResult<JsonObject> =
        call(if (liked) "like/like" else "like/removelike", null) {
            putJsonObject("target") { put("videoId", videoId) }
        }

    suspend fun accountMenu(): YtResult<JsonObject> = call("account/account_menu", null) {}

    // --- Requests ------------------------------------------------------------------------------------

    private suspend fun call(endpoint: String, continuation: Continuation?, fields: JsonObjectBuilder.() -> Unit): YtResult<JsonObject> {
        val first = attempt(endpoint, continuation, fields)
        // A 400 usually means the page has moved on to a newer client version: learn it, try once more.
        if (first is YtResult.Failure && first.error == STALE_CLIENT) {
            config = null
            return attempt(endpoint, continuation, fields).let {
                if (it is YtResult.Failure && it.error == STALE_CLIENT) YtResult.Failure(YtError.Server(400)) else it
            }
        }
        return first
    }

    private suspend fun attempt(endpoint: String, continuation: Continuation?, fields: JsonObjectBuilder.() -> Unit): YtResult<JsonObject> {
        val cfg = pageConfig()
        val signedIn = session()
        val page = if (signedIn != null) onBehalfOf() else null
        val hl = language()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", cfg.clientVersion)
                    put("hl", hl)
                    put("gl", region())
                    cfg.visitorData?.let { put("visitorData", it) }
                }
                putJsonObject("user") { page?.let { put("onBehalfOfUser", it) } }
            }
            if (continuation?.inBody == true) put("continuation", continuation.token)
            fields()
        }
        val url = buildString {
            append(API).append(endpoint).append("?prettyPrint=false")
            if (continuation != null && !continuation.inBody) {
                val t = URLEncoder.encode(continuation.token, "UTF-8")
                append("&ctoken=").append(t).append("&continuation=").append(t).append("&type=next")
            }
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            put("Accept-Language", hl)
            put("User-Agent", userAgent())
            put("Origin", ORIGIN)
            put("X-Origin", ORIGIN)
            put("Referer", "$ORIGIN/")
            put("X-Youtube-Client-Name", CLIENT_ID)
            put("X-Youtube-Client-Version", cfg.clientVersion)
            cfg.visitorData?.let { put("X-Goog-Visitor-Id", it) }
            if (signedIn != null) {
                put("Cookie", signedIn.cookieHeader)
                signedIn.authorization(now() / 1000, ORIGIN)?.let { put("Authorization", it) }
                put("X-Goog-AuthUser", "0")
            }
        }
        val response = try {
            transport.send("POST", url, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return YtResult.Failure(if (e is UnknownHostException || e.message == "UnknownHostException") YtError.Offline else YtError.Network)
        }
        return interpret(response, signedIn != null)
    }

    private fun interpret(response: HttpResponse, signedIn: Boolean): YtResult<JsonObject> {
        when (response.code) {
            in 200..299 -> Unit
            400 -> return YtResult.Failure(STALE_CLIENT)
            401 -> return YtResult.Failure(if (signedIn) YtError.AuthExpired else YtError.AuthRequired)
            403 -> return YtResult.Failure(if (signedIn) YtError.AuthExpired else YtError.Server(403))
            404 -> return YtResult.Failure(YtError.NotFound("page"))
            429 -> return YtResult.Failure(YtError.RateLimited(response.retryAfterSeconds?.times(1000)))
            else -> return YtResult.Failure(YtError.Server(response.code))
        }
        val json = parseJson(response.body) ?: return YtResult.Failure(YtError.Server(null))
        // An answer that says it isn't signed in, to a signed-in request: the session has ended.
        if (signedIn && loggedInFlag(json) == "0") return YtResult.Failure(YtError.AuthExpired)
        return YtResult.Success(json)
    }

    private fun loggedInFlag(json: JsonObject): String? =
        json.at("responseContext", "serviceTrackingParams").arr
            .flatMap { it.at("params").arr }
            .firstOrNull { it.at("key").str == "logged_in" }
            ?.at("value").str

    // --- The page's own configuration ------------------------------------------------------------------

    private suspend fun pageConfig(): PageConfig {
        config?.takeIf { now() - it.loadedAt < CONFIG_TTL_MS }?.let { return it }
        return configLock.withLock {
            config?.takeIf { now() - it.loadedAt < CONFIG_TTL_MS }?.let { return@withLock it }
            val loaded = loadPageConfig() ?: PageConfig(FALLBACK_CLIENT_VERSION, null, now() - CONFIG_TTL_MS + CONFIG_RETRY_MS)
            config = loaded
            loaded
        }
    }

    private suspend fun loadPageConfig(): PageConfig? {
        var page = fetchPage(cookie = null) ?: return null
        // Without cookies the page can answer with a redirect instead (in some regions, to a cookie
        // consent page). A session already carries the choices made on the sign-in page, so ask
        // once more with it rather than falling back to a client version that will age.
        if (page.code !in 200..299) {
            val signedIn = session() ?: return null
            page = fetchPage(cookie = signedIn.cookieHeader) ?: return null
        }
        if (page.code !in 200..299) return null
        return PageConfigParser.parse(page.body.toString(Charsets.UTF_8))?.let { (version, visitor) -> PageConfig(version, visitor, now()) }
    }

    private suspend fun fetchPage(cookie: String?): HttpResponse? = try {
        val headers = buildMap {
            put("User-Agent", userAgent())
            put("Accept-Language", language())
            cookie?.let { put("Cookie", it) }
        }
        transport.send("GET", "$ORIGIN/", headers, null)
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        private const val API = "$ORIGIN/youtubei/v1/"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_ID = "67"

        /** Used only until the page says otherwise (and when it can't be read). Seen live 2026-11-03. */
        const val FALLBACK_CLIENT_VERSION = "1.20261031.09.00"
        private const val CONFIG_TTL_MS = 6 * 60 * 60 * 1000L
        private const val CONFIG_RETRY_MS = 5 * 60 * 1000L

        private val STALE_CLIENT = YtError.Server(-400)

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseJson(bytes: ByteArray): JsonObject? =
            runCatching { lenient.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }
}

/** Reads the client version and visitor id the music page was served with. */
internal object PageConfigParser {
    private val version = Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([0-9][0-9.]{4,40})\"")
    // The guest visitor id grew past 400 characters in 2026 (a new protobuf field); allow plenty.
    private val visitor = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([A-Za-z0-9%_=\\-]{8,2000})\"")

    fun parse(html: String): Pair<String, String?>? {
        val v = version.find(html)?.groupValues?.get(1) ?: return null
        return v to visitor.find(html)?.groupValues?.get(1)
    }
}
