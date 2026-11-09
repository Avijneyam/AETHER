package app.aether.core.data

import android.content.Context
import android.content.SharedPreferences
import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sources AETHER knows, routed by id: `ytm:…` goes to YouTube Music, `local:…` to the phone.
 * [primary] fills Home, Explore and search. Screens never hold a source themselves.
 */
class SourceManager(sources: List<MusicSource>, primaryId: String) {
    private val byId = java.util.concurrent.ConcurrentHashMap(sources.associateBy { it.id })
    val primary: MusicSource = byId.getValue(primaryId)
    private val _all = MutableStateFlow(sources)

    /** Every source, the listener's added ones included, as they come and go. */
    val added: StateFlow<List<MusicSource>> = _all.asStateFlow()
    val all: List<MusicSource> get() = _all.value

    /** A source the listener added (by its address) joins the others. */
    fun register(source: MusicSource) {
        byId[source.id] = source
        _all.value = _all.value.filter { it.id != source.id } + source
    }

    fun unregister(id: String) {
        byId.remove(id)
        _all.value = _all.value.filter { it.id != id }
    }

    fun of(id: MediaId): MusicSource? = byId[id.source]
    fun of(sourceId: String): MusicSource? = byId[sourceId]

    suspend fun stream(id: MediaId, efficient: Boolean): Outcome<Stream> =
        of(id)?.stream(id, efficient) ?: Outcome.Err(SourceError.NotFound("source"))

    fun invalidate(id: MediaId) = of(id)?.invalidate(id) ?: Unit
}

/** The listener's choices, kept on the phone. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("aether", Context.MODE_PRIVATE)

    private fun flag(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _onboarded = flag(ONBOARDED, false)
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    private val _haptics = flag(HAPTICS, true)
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _reduceMotion = flag(REDUCE_MOTION, false)
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    /** Lower-bitrate streams, for metered networks. */
    private val _efficient = flag(EFFICIENT, false)
    val efficient: StateFlow<Boolean> = _efficient.asStateFlow()

    /**
     * Timed lyrics are asked of LRCLIB (which is sent a song's title, artist and length, nothing
     * else). On unless the listener turns it off, on the welcome or in Settings.
     */
    private val _timedLyrics = flag(TIMED_LYRICS, true)
    val timedLyrics: StateFlow<Boolean> = _timedLyrics.asStateFlow()

    private val _artistArt = flag(ARTIST_ART, true)

    /** An artist's portrait, lettered name and film, asked of Apple Music by the artist's name. */
    val artistArt: StateFlow<Boolean> = _artistArt.asStateFlow()

    private val _addons = MutableStateFlow(prefs.getStringSet(ADDONS, emptySet()).orEmpty().toList().sorted())

    /** The addresses of the sources the listener has added. */
    val addons: StateFlow<List<String>> = _addons.asStateFlow()

    fun addAddon(address: String) = setAddons(_addons.value + address.trim())
    fun removeAddon(address: String) = setAddons(_addons.value - address)

    private fun setAddons(list: List<String>) {
        val distinct = list.distinct().sorted()
        prefs.edit().putStringSet(ADDONS, distinct.toSet()).apply()
        _addons.value = distinct
    }

    /** PODIUM's lighter glass instead of the full liquid glass. On unless the listener asks for the full effect. */
    private val _simpleGlass = flag(SIMPLE_GLASS, true)
    val simpleGlass: StateFlow<Boolean> = _simpleGlass.asStateFlow()

    // The cycle used to open on black and white, with the original second; the rest kept their places.
    private val _artwork = MutableStateFlow(prefs.getInt(ARTWORK_CYCLE, prefs.getInt(ARTWORK, 0).let { if (it <= 1) 0 else it }))
    /** The treatment Now Playing opens with (an index into the cycle). */
    val artwork: StateFlow<Int> = _artwork.asStateFlow()

    private val _lyricsStyle = MutableStateFlow(prefs.getInt(LYRICS_STYLE, 0))
    /** How timed lyrics are drawn: 0 as constellations, 1 as one continuous line. */
    val lyricsStyle: StateFlow<Int> = _lyricsStyle.asStateFlow()

    private val _keptCollections = MutableStateFlow(prefs.getStringSet(KEPT_COLLECTIONS, emptySet()).orEmpty().toSet())
    /** The playlists and albums whose new songs are kept as well. */
    val keptCollections: StateFlow<Set<String>> = _keptCollections.asStateFlow()

    private val _profileName = MutableStateFlow(prefs.getString(PROFILE_NAME, null))
    /** The name the listener gave their Profile, if they did. */
    val profileName: StateFlow<String?> = _profileName.asStateFlow()

    private val _chartsCountry = MutableStateFlow(prefs.getString(CHARTS_COUNTRY, null))
    val chartsCountry: StateFlow<String?> = _chartsCountry.asStateFlow()

    /** Permissions the listener has already answered: never asked again. */
    fun asked(permission: String): Boolean = prefs.getBoolean("asked:$permission", false)
    fun markAsked(permission: String) = prefs.edit().putBoolean("asked:$permission", true).apply()

    fun setOnboarded() = set(ONBOARDED, true, _onboarded)
    fun setHaptics(on: Boolean) = set(HAPTICS, on, _haptics)
    fun setReduceMotion(on: Boolean) = set(REDUCE_MOTION, on, _reduceMotion)
    fun setEfficient(on: Boolean) = set(EFFICIENT, on, _efficient)
    fun setSimpleGlass(on: Boolean) = set(SIMPLE_GLASS, on, _simpleGlass)
    fun setTimedLyrics(on: Boolean) = set(TIMED_LYRICS, on, _timedLyrics)
    fun setArtistArt(on: Boolean) = set(ARTIST_ART, on, _artistArt)
    fun setArtwork(index: Int) { prefs.edit().putInt(ARTWORK_CYCLE, index).apply(); _artwork.value = index }
    fun setLyricsStyle(index: Int) { prefs.edit().putInt(LYRICS_STYLE, index).apply(); _lyricsStyle.value = index }
    fun setCollectionKept(key: String, on: Boolean) {
        val next = if (on) _keptCollections.value + key else _keptCollections.value - key
        prefs.edit().putStringSet(KEPT_COLLECTIONS, next).apply()
        _keptCollections.value = next
    }
    fun setProfileName(name: String) {
        val trimmed = name.trim().ifEmpty { null }
        prefs.edit().putString(PROFILE_NAME, trimmed).apply()
        _profileName.value = trimmed
    }
    fun setChartsCountry(code: String?) { prefs.edit().putString(CHARTS_COUNTRY, code).apply(); _chartsCountry.value = code }

    private fun set(key: String, value: Boolean, state: MutableStateFlow<Boolean>) {
        prefs.edit().putBoolean(key, value).apply()
        state.value = value
    }

    private companion object {
        const val ONBOARDED = "onboarded"
        const val HAPTICS = "haptics"
        const val REDUCE_MOTION = "reduce_motion"
        const val EFFICIENT = "efficient"
        const val SIMPLE_GLASS = "simple_glass"
        const val TIMED_LYRICS = "timed_lyrics"
        const val ARTIST_ART = "artist_art"
        const val ADDONS = "addons"
        const val ARTWORK = "artwork_treatment"
        const val ARTWORK_CYCLE = "artwork_cycle"
        const val LYRICS_STYLE = "lyrics_style"
        const val PROFILE_NAME = "profile_name"
        const val KEPT_COLLECTIONS = "kept_collections"
        const val CHARTS_COUNTRY = "charts_country"
    }
}
