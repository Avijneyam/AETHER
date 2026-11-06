package app.aether.sources.youtube

import app.aether.core.model.Outcome
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A song's audio, the way PODIUM resolves it (its D-97/D-98 walk): YouTube's own apps are asked in
 * turn for the song, and a stream counts only once both its first and its last bytes have been
 * served — a check of the first bytes alone passes streams that are refused seconds into playback.
 * Measured 2026-11-03: `VISIONOS` plays; `IOS` and `ANDROID_VR` are refused without a PoToken but
 * stay in the walk, after it, for when that changes.
 *
 * Nothing about the listener goes with these requests: a guest visitor per app, no cookies.
 */
class YouTubeStreamResolver(
    private val http: OkHttpClient,
    private val region: () -> String = { "US" },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class App(val name: String, val id: Int, val version: String, val userAgent: String, val device: Map<String, Any>)

    private val visitors = ConcurrentHashMap<String, Pair<String, Long>>()
    private val resolved = ConcurrentHashMap<String, Stream>()

    /** The app that served last goes first next time. */
    @Volatile private var lastServed: String? = null

    suspend fun resolve(videoId: String, efficient: Boolean = false): Outcome<Stream> = withContext(Dispatchers.IO) {
        val key = "$videoId|$efficient"
        resolved[key]?.takeIf { (it.expiresAtMs ?: Long.MAX_VALUE) - now() > EXPIRY_MARGIN_MS }?.let { return@withContext Outcome.Ok(it) }
        val order = APPS.sortedBy { if (it.name == lastServed) 0 else 1 }
        var verdict: SourceError = SourceError.NotPlayable(null)
        for (app in order) {
            val outcome = try {
                tryApp(app, videoId, efficient)
            } catch (e: UnknownHostException) {
                return@withContext Outcome.Err(SourceError.Offline)
            } catch (e: IOException) {
                Outcome.Err(SourceError.Network)
            }
            when (outcome) {
                is Outcome.Ok -> {
                    lastServed = app.name
                    resolved[key] = outcome.value
                    return@withContext outcome
                }
                is Outcome.Err -> {
                    // A song gone or private for one app is gone for all; anything else, the next app may serve.
                    if (outcome.error is SourceError.NotFound) return@withContext outcome
                    if (outcome.error is SourceError.RateLimited) return@withContext outcome
                    verdict = outcome.error
                }
            }
        }
        Outcome.Err(verdict)
    }

    fun forget(videoId: String) {
        resolved.keys.removeAll { it.startsWith("$videoId|") }
    }

    private suspend fun tryApp(app: App, videoId: String, efficient: Boolean): Outcome<Stream> {
        val visitor = visitorFor(app)
        val body = buildJsonObject {
            putJsonObject("context") {
                put("client", clientContext(app, visitor))
                putJsonObject("user") { put("lockedSafetyMode", false) }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") { put("html5Preference", "HTML5_PREF_WANTS") }
            }
        }
        val request = Request.Builder()
            .url("$API/player?prettyPrint=false")
            .headers(appHeaders(app, visitor))
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = http.newCall(request).execute().use { r ->
            if (r.code == 429) return Outcome.Err(SourceError.RateLimited(r.header("Retry-After")?.toLongOrNull()?.times(1000)))
            if (!r.isSuccessful) return Outcome.Err(SourceError.Server(r.code))
            lenient.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
        } ?: return Outcome.Err(SourceError.Server(null))

        val playability = json.obj("playabilityStatus")
        when (playability?.str("status")) {
            "OK" -> Unit
            "ERROR" -> return Outcome.Err(SourceError.NotFound("song"))
            else -> return Outcome.Err(SourceError.NotPlayable(playability?.str("reason")))
        }
        // The song asked for, not a stand-in.
        json.obj("videoDetails")?.str("videoId")?.let { if (it != videoId) return Outcome.Err(SourceError.NotPlayable("another video")) }

        val formats = (json.obj("streamingData")?.get("adaptiveFormats") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("mimeType")?.startsWith("audio/") == true && it.str("url") != null }
            // Dynamic-range-compressed copies are quieter, flatter versions of the same track.
            .filterNot { it.bool("isDrc") == true || it.str("xtags")?.contains("drc", ignoreCase = true) == true }
        if (formats.isEmpty()) return Outcome.Err(SourceError.NotPlayable("no audio"))
        val ranked = if (efficient) {
            formats.sortedBy { it.long("bitrate") ?: Long.MAX_VALUE }.dropWhile { (it.long("bitrate") ?: 0) < 60_000 }.ifEmpty { formats }
        } else {
            // Opus first, then AAC; higher bitrate first within each.
            formats.sortedWith(compareByDescending<JsonObject> { it.str("mimeType")!!.contains("opus") }.thenByDescending { it.long("bitrate") ?: 0 })
        }
        for (format in ranked.take(2)) {
            val url = format.str("url")!!
            val length = format.long("contentLength") ?: url.queryLong("clen")
            if (serves(url, app.userAgent, length)) {
                val mime = format.str("mimeType")!!
                return Outcome.Ok(
                    Stream(
                        url = url,
                        headers = mapOf("User-Agent" to app.userAgent),
                        mimeType = mime.substringBefore(';'),
                        codec = mime.substringAfter("codecs=\"", "").substringBefore('"').ifEmpty { null },
                        bitrateKbps = format.long("bitrate")?.let { (it / 1000).toInt() },
                        sampleRateHz = format.str("audioSampleRate")?.toIntOrNull(),
                        contentLength = length,
                        expiresAtMs = url.queryLong("expire")?.times(1000),
                        cacheKey = "ytm:$videoId:${format.long("itag")}",
                    ),
                )
            }
        }
        return Outcome.Err(SourceError.NotPlayable("refused"))
    }

    /** Both ends of the stream answer: the open-ended request the player sends, and the last two bytes. */
    private suspend fun serves(url: String, userAgent: String, length: Long?): Boolean = coroutineScope {
        val head = async(Dispatchers.IO) { rangeOk(url, userAgent, "0-") }
        val tail = async(Dispatchers.IO) { if (length != null && length > 2) rangeOk(url, userAgent, "${length - 2}-${length - 1}") else true }
        head.await() && tail.await()
    }

    private fun rangeOk(url: String, userAgent: String, range: String): Boolean = try {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=$range").build()
        probe.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return false
            r.body?.source()?.request(2)
            true
        }
    } catch (_: IOException) {
        false
    }

    private fun visitorFor(app: App): String? {
        visitors[app.name]?.takeIf { now() - it.second < VISITOR_TTL_MS }?.let { return it.first }
        val body = buildJsonObject { putJsonObject("context") { put("client", clientContext(app, null)) } }
        val request = Request.Builder()
            .url("$API/visitor_id?prettyPrint=false")
            .headers(appHeaders(app, null))
            .post(body.toString().toRequestBody(JSON))
            .build()
        val visitor = try {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null
                ((lenient.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject)?.obj("responseContext"))?.str("visitorData")
            }
        } catch (_: IOException) {
            null
        } ?: return null
        visitors[app.name] = visitor to now()
        return visitor
    }

    private fun clientContext(app: App, visitor: String?): JsonObject = buildJsonObject {
        put("clientName", app.name)
        put("clientVersion", app.version)
        app.device.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) }
        put("hl", "en")
        put("gl", region().takeIf { it.length == 2 }?.uppercase() ?: "US")
        visitor?.let { put("visitorData", it) }
    }

    private fun appHeaders(app: App, visitor: String?) = okhttp3.Headers.Builder().apply {
        add("Content-Type", "application/json")
        add("User-Agent", app.userAgent)
        add("X-Youtube-Client-Name", app.id.toString())
        add("X-Youtube-Client-Version", app.version)
        visitor?.let { add("X-Goog-Visitor-Id", it) }
    }.build()

    private val probe = http.newBuilder().readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

    private companion object {
        const val API = "https://www.youtube.com/youtubei/v1"
        const val VISITOR_TTL_MS = 6 * 60 * 60 * 1000L
        const val EXPIRY_MARGIN_MS = 60_000L
        val JSON = "application/json".toMediaType()
        val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        // PODIUM's identities (PlayerIdentities.kt), in the order measured to serve.
        val APPS = listOf(
            App(
                "VISIONOS", 101, "1.02",
                "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)",
                mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice14,1", "osName" to "visionOS", "osVersion" to "25.6.0.23O471"),
            ),
            App(
                "IOS", 5, "21.03.2",
                "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)",
                mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.7.2.22H124"),
            ),
            App(
                "ANDROID_VR", 28, "1.65.10",
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
            ),
        )
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
private fun String.queryLong(name: String): Long? =
    substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.toLongOrNull()
