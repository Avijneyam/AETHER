// Ported from PODIUM (sources/addon, GPL-3.0), the listener's own app; package renamed for AETHER.
package app.aether.sources.addon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What an addon says about itself: its `manifest.json` (D-91). */
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val resources: Set<String> = setOf(RESOURCE_SEARCH, RESOURCE_STREAM),
    val settings: List<AddonSetting> = emptyList(),
    /** Whether the addon lets its streams be saved for offline listening. AETHER only streams from addons. */
    val allowsDownloads: Boolean = false,
    /**
     * The addon answers only BitChord's own app (`bitchordAuth`), which proves itself with a key that
     * Android vouches for. AETHER can't, and doesn't pretend to: such an addon isn't added.
     */
    val onlyForBitChord: Boolean = false,
) {
    val canSearch: Boolean get() = RESOURCE_SEARCH in resources
    val canStream: Boolean get() = RESOURCE_STREAM in resources

    /** Every setting at the value the listener chose, or its default when that isn't one it takes. */
    fun settle(values: Map<String, String>): Map<String, String> =
        settings.mapNotNull { s -> s.accept(values[s.key])?.let { s.key to it } }.toMap()

    /**
     * What goes with every request (the protocol's settings passthrough, as BitChord's host sends it):
     * each declared setting at the listener's value or its default, and `quality` at the best the
     * addon offers, which its declared choices list first, unless the listener chose another. An addon
     * that declares no quality setting is asked for `LOSSLESS`, as the reference host asks; one that
     * never reads it behaves as before.
     */
    fun query(values: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(settle(values))
        val quality = settings.firstOrNull { it.key == QUALITY }
        out[QUALITY] = when {
            quality == null -> LOSSLESS
            values[QUALITY] != null && quality.options.any { it.value == values[QUALITY] } -> values.getValue(QUALITY)
            else -> quality.options.firstOrNull()?.value ?: quality.default ?: LOSSLESS
        }
        return out
    }

    companion object {
        const val RESOURCE_SEARCH = "search"
        const val RESOURCE_STREAM = "stream"
        const val QUALITY = "quality"
        const val LOSSLESS = "LOSSLESS"
    }
}

/** One setting an addon declares; the listener's value goes with every request as a query parameter. */
data class AddonSetting(
    val key: String,
    val label: String,
    val type: Type,
    val default: String? = null,
    val options: List<Option> = emptyList(),
) {
    enum class Type { SELECT, TOGGLE, TEXT }

    data class Option(val label: String, val value: String)

    /** [value] when this setting takes it, else the default (for a choice, the first option). */
    fun accept(value: String?): String? = when (type) {
        Type.SELECT -> value?.takeIf { v -> options.any { it.value == v } }
            ?: default?.takeIf { d -> options.any { it.value == d } }
            ?: options.firstOrNull()?.value
        Type.TOGGLE -> value?.takeIf { it == "true" || it == "false" } ?: default?.takeIf { it == "true" || it == "false" } ?: "false"
        Type.TEXT -> value?.takeIf { it.length <= MAX_TEXT } ?: default
    }

    /** What the listener sees for [value]: an option's label, On/Off, or the text itself. */
    fun describe(value: String?): String = when (type) {
        Type.SELECT -> options.firstOrNull { it.value == value }?.label ?: value.orEmpty()
        Type.TOGGLE -> if (value == "true") "On" else "Off"
        Type.TEXT -> value.orEmpty()
    }

    companion object {
        const val MAX_TEXT = 256
    }
}

/** A song in an addon's search answer. */
internal data class AddonTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val isrc: String?,
    val explicit: Boolean?,
    /** The row's own stream, when the addon gives one: the `/stream` call is then skipped (BitChord's protocol). */
    val streamUrl: String? = null,
    /** What the row says it is (`LOSSLESS`, `HI_RES_LOSSLESS`, `flac`): a claim, used only to order copies. */
    val quality: String? = null,
    /** An immersive mix (Dolby Atmos, 360) rather than the stereo one: never taken for the stereo song. */
    val immersive: Boolean = false,
)

/** An addon's answer for one song's stream. */
internal data class AddonStream(
    val url: String,
    val format: String?,
    val quality: String?,
    val mimeType: String?,
    val encrypted: Boolean,
    val headers: Map<String, String>,
    val expiresAtMillis: Long?,
    /** What the address is: the audio itself, or a DASH or HLS manifest of it. */
    val manifest: String? = null,
    val codec: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    /** The addon answered, but with no stream and why (`"error": "…"`): a miss, and its reason. */
    val error: String? = null,
)

/**
 * The addon protocol's answers, read leniently (D-91). Addons are written by anyone: every answer
 * is untrusted input whose shape varies. Nothing here throws; what can't be read is null, and only
 * what the protocol needs is kept.
 */
internal object AddonProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(bytes: ByteArray): JsonElement? = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull()

    fun manifest(bytes: ByteArray): AddonManifest? {
        val o = parse(bytes) as? JsonObject ?: return null
        val id = o["id"].text()?.take(MAX_NAME)
        val name = o["name"].text()?.take(MAX_NAME)
        if (id == null && name == null) return null
        val resources = (o["resources"] as? JsonArray)?.mapNotNull { r ->
            (r.text() ?: (r as? JsonObject)?.let { it["name"].text() ?: it["type"].text() })?.lowercase()
        }?.toSet()
        val settings = (o["settings"] as? JsonArray).orEmpty().mapNotNull(::setting).distinctBy { it.key }.take(MAX_SETTINGS)
        return AddonManifest(
            id = id ?: name!!,
            name = name ?: id!!,
            version = o["version"].text()?.take(MAX_NAME),
            description = o["description"].text()?.take(MAX_DESCRIPTION),
            // An addon that declares nothing is taken to do both, as BitChord takes it.
            resources = resources?.takeIf { it.isNotEmpty() } ?: setOf(AddonManifest.RESOURCE_SEARCH, AddonManifest.RESOURCE_STREAM),
            settings = settings,
            allowsDownloads = o["allowDownloads"].flag() == true,
            onlyForBitChord = o["bitchordAuth"].flag() == true,
        )
    }

    /**
     * One declared setting. A setting of a kind AETHER doesn't know, or with no `type` at all (BitChord
     * reads only a setting's default and options, so addons often leave it out), is kept, never
     * dropped (D-98): with options it's a choice, without them text. Either way its default travels
     * with every request, as it does from BitChord. An option with only a value is named from it
     * ("HI_RES" → "Hi res").
     */
    private fun setting(e: JsonElement): AddonSetting? {
        val o = e as? JsonObject ?: return null
        val key = o["key"].text() ?: o["id"].text() ?: o["name"].text() ?: return null
        if (!SETTING_KEY.matches(key)) return null
        val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
            when (opt) {
                is JsonPrimitive -> opt.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { AddonSetting.Option(humanize(it), it) }
                is JsonObject -> {
                    val value = opt["value"].text() ?: return@mapNotNull null
                    AddonSetting.Option(opt["label"].text() ?: opt["name"].text() ?: humanize(value), value)
                }
                else -> null
            }
        }.distinctBy { it.value }
        val type = when (o["type"].text()?.lowercase()) {
            "select", "dropdown", "enum", "choice", "radio" -> if (options.isEmpty()) AddonSetting.Type.TEXT else AddonSetting.Type.SELECT
            "boolean", "bool", "toggle", "switch", "checkbox" -> AddonSetting.Type.TOGGLE
            "text", "string", "input", "password" -> AddonSetting.Type.TEXT
            else -> if (options.isEmpty()) AddonSetting.Type.TEXT else AddonSetting.Type.SELECT
        }
        val label = o["label"].text() ?: o["title"].text() ?: o["name"].text()?.takeIf { it != key } ?: humanize(key)
        return AddonSetting(key, label.take(MAX_NAME), type, o["default"].text()?.take(AddonSetting.MAX_TEXT), if (type == AddonSetting.Type.SELECT) options else emptyList())
    }

    /** A manifest written back in the protocol's own shape (what [manifest] reads). */
    fun write(m: AddonManifest): String = buildJsonObject {
        put("id", m.id)
        put("name", m.name)
        m.version?.let { put("version", it) }
        m.description?.let { put("description", it) }
        putJsonArray("resources") { m.resources.sorted().forEach { add(it) } }
        put("allowDownloads", m.allowsDownloads)
        putJsonArray("settings") {
            m.settings.forEach { s ->
                addJsonObject {
                    put("key", s.key)
                    put("label", s.label)
                    put("type", when (s.type) {
                        AddonSetting.Type.SELECT -> "select"
                        AddonSetting.Type.TOGGLE -> "boolean"
                        AddonSetting.Type.TEXT -> "text"
                    })
                    s.default?.let { put("default", it) }
                    putJsonArray("options") {
                        s.options.forEach { o -> addJsonObject { put("label", o.label); put("value", o.value) } }
                    }
                }
            }
        }
    }.toString()

    /** "audio_quality" → "Audio quality". */
    private fun humanize(key: String): String =
        key.replace('_', ' ').replace('-', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

    fun search(bytes: ByteArray): List<AddonTrack>? {
        val root = parse(bytes) ?: return null
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["tracks"] ?: root["results"] ?: root["items"]) as? JsonArray ?: return null
            else -> return null
        }
        return items.mapNotNull(::track)
    }

    private fun track(e: JsonElement): AddonTrack? {
        val o = e as? JsonObject ?: return null
        val id = o["id"].text()?.takeIf { it.length <= MAX_ID } ?: return null
        val title = (o["title"] ?: o["name"]).text() ?: return null
        val artist = o["artist"].text()
            ?: (o["artist"] as? JsonObject)?.get("name").text()
            ?: (o["artists"] as? JsonArray)?.mapNotNull { it.text() ?: (it as? JsonObject)?.get("name").text() }
                ?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val album = o["album"].text() ?: (o["album"] as? JsonObject)?.let { it["title"].text() ?: it["name"].text() }
        val durationMs = o["durationMs"].number()?.toLong()
            ?: o["duration"].number()?.let { if (it > MAX_SECONDS) it.toLong() else (it * 1000).toLong() }
            ?: o["duration"].text()?.let(::clock)
        val modes = (o["audioModes"] as? JsonArray)?.mapNotNull { it.text() }.orEmpty() + listOfNotNull(o["audioMode"].text())
        val quality = (o["audioQuality"] ?: o["quality"] ?: o["format"]).text()
        return AddonTrack(
            id = id,
            title = title.take(MAX_NAME),
            artist = artist?.take(MAX_NAME),
            album = album?.take(MAX_NAME),
            durationMs = durationMs?.takeIf { it > 0 },
            isrc = o["isrc"].text(),
            explicit = o["explicit"].flag(),
            streamUrl = (o["streamURL"] ?: o["streamUrl"]).text()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            quality = quality?.take(MAX_NAME),
            immersive = o["atmos"].flag() == true || modes.any { IMMERSIVE.containsMatchIn(it) } ||
                quality?.let { IMMERSIVE.containsMatchIn(it) } == true,
        )
    }

    /** "3:21" or "1:02:03" → milliseconds. */
    private fun clock(text: String): Long? {
        val parts = text.split(':').map { it.trim().toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, n -> acc * 60 + n } * 1000
    }

    /**
     * One song's stream, read as BitChord's protocol writes it. The transport is what `manifest` says
     * (`dash`, `hls` or `none`), or `mediaType`, or, as live addons send it, the word `dash` or `hls`
     * in `format`; a codec or container there (`flac`, `mp4`) means a plain file. `encrypted` is
     * `false` or the name of a scheme, and anything but false is refused. An answer with no address
     * and an `error` is a miss that says why.
     */
    fun stream(bytes: ByteArray): AddonStream? {
        val o = parse(bytes) as? JsonObject ?: return null
        val url = (o["url"] ?: o["streamUrl"] ?: o["streamURL"] ?: o["stream"]).text()
        val error = o["error"].text()?.take(MAX_DESCRIPTION)
        if (url == null) return error?.let { AddonStream("", null, null, null, false, emptyMap(), null, error = it) }
        val headers = (o["headers"] as? JsonObject)?.entries
            ?.mapNotNull { (k, v) -> v.text()?.let { k to it } }
            ?.filter { (k, v) -> HEADER_NAME.matches(k) && '\n' !in v && '\r' !in v }
            ?.take(MAX_HEADERS)?.toMap().orEmpty()
        val expires = (o["expiresAt"] ?: o["expires"])?.number()?.toLong()?.let { if (it < 100_000_000_000L) it * 1000 else it }
        val format = o["format"].text()?.lowercase()
        val transport = listOfNotNull(o["manifest"].text(), o["mediaType"].text(), format).firstNotNullOfOrNull { stated ->
            when (stated.lowercase()) {
                "dash", "mpd", "application/dash+xml" -> "dash"
                "hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "hls"
                "none" -> "none"
                else -> null
            }
        }
        val qualityText = listOfNotNull(o["quality"].text(), o["streamQuality"].text(), o["audioQuality"].text(), format).joinToString(" ")
        val rate = o["sampleRate"].number()?.let { if (it < 1000) it * 1000 else it }?.toInt()
            ?: KHZ.find(qualityText)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toInt() }
        val depth = o["bitDepth"].number()?.toInt()?.takeIf { it in 8..32 }
            ?: BITS.find(qualityText)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8..32 }
        return AddonStream(
            url = url,
            format = format,
            quality = o["quality"].text()?.take(MAX_NAME),
            mimeType = (o["mimeType"] ?: o["mime"]).text(),
            encrypted = isEncrypted(o["encrypted"]) || isEncrypted(o["drm"]),
            headers = headers,
            expiresAtMillis = expires,
            manifest = transport,
            codec = (o["codec"] ?: o["fileCodec"]).text()?.lowercase(),
            sampleRateHz = rate?.takeIf { it in 8_000..768_000 },
            bitDepth = depth,
            bitrateKbps = o["bitrate"].number()?.let { if (it > 3_000) it / 1000 else it }?.toInt()?.takeIf { it > 0 },
            error = error,
        )
    }

    /** `false`, `"none"` or nothing: open. `true`, or the name of any scheme: encrypted. */
    private fun isEncrypted(e: JsonElement?): Boolean {
        val p = e as? JsonPrimitive ?: return false
        p.booleanOrNull?.let { return it }
        val text = p.contentOrNull?.trim()?.lowercase() ?: return false
        return text.isNotEmpty() && text != "false" && text != "none" && text != "0" && text != "null"
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.let { p ->
        if (p.isString) p.content.trim().toDoubleOrNull() else p.longOrNull?.toDouble() ?: p.doubleOrNull
    }?.takeIf { it.isFinite() && it >= 0 }

    private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.let { p ->
        p.booleanOrNull ?: p.longOrNull?.let { it != 0L } ?: p.contentOrNull?.lowercase()?.toBooleanStrictOrNull()
    }

    private val IMMERSIVE = Regex("""atmos|dolby|360|eac3[_-]?joc|ec-?3""", RegexOption.IGNORE_CASE)
    private val KHZ = Regex("""([\d.]+)\s*kHz""", RegexOption.IGNORE_CASE)
    private val BITS = Regex("""(\d{1,2})\s*-?\s*bit""", RegexOption.IGNORE_CASE)
    private val SETTING_KEY = Regex("[A-Za-z0-9_.-]{1,64}")
    private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
    private const val MAX_NAME = 300
    private const val MAX_DESCRIPTION = 1_000
    private const val MAX_ID = 1_000
    private const val MAX_SETTINGS = 20
    private const val MAX_HEADERS = 10

    /** A "duration" larger than ten hours of seconds is taken to be milliseconds. */
    private const val MAX_SECONDS = 36_000.0
}
