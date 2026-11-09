package app.aether

import android.content.Context
import android.net.ConnectivityManager
import app.aether.core.data.Library
import app.aether.core.data.Settings
import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.PageToken
import app.aether.core.model.Track
import app.aether.player.Downloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A playlist or album kept on the phone goes on being kept: songs added to it later come down by
 * themselves, on Wi-Fi only (nothing is even looked up on a metered connection). Only what's new is
 * fetched: what it held last time is remembered, so a song the listener took off the phone isn't
 * brought back, and nothing is ever deleted here.
 */
class KeepSync(
    context: Context,
    private val scope: CoroutineScope,
    private val sources: app.aether.core.data.SourceManager,
    private val library: Library,
    private val downloads: Downloads,
    private val settings: Settings,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val known = context.getSharedPreferences("kept_known", Context.MODE_PRIVATE)
    private val oneAtATime = Mutex()

    fun start() {
        scope.launch {
            delay(FirstLookMs)
            while (isActive) delay(if (syncAll()) SoonMs else EveryMs)
        }
        // The listener's own playlists change here on the phone: there's nothing to wait for.
        scope.launch {
            combine(settings.keptCollections, library.playlists) { keys, lists -> lists.filter { local(it.id) in keys }.map { it.id to it.updatedAt } }
                .distinctUntilChanged()
                .drop(1)
                .collect { changed -> changed.forEach { sync(local(it.first)) } }
        }
    }

    /** From now on, songs added to this collection are kept too. [tracks] are the ones already asked for. */
    fun follow(key: String, tracks: List<Track>) {
        settings.setCollectionKept(key, true)
        known.edit().putStringSet(key, ids(tracks)).apply()
        // A long list only shows its first songs: the rest come now, as anything new would.
        scope.launch { sync(key) }
    }

    /** New songs are left alone from now on. What's on the phone stays on the phone. */
    fun stop(key: String) {
        settings.setCollectionKept(key, false)
        known.edit().remove(key).apply()
    }

    /** True when something has to wait for Wi-Fi. */
    private suspend fun syncAll(): Boolean {
        var waiting = false
        settings.keptCollections.value.forEach { if (sync(it)) waiting = true }
        return waiting
    }

    /** True when it has to wait for Wi-Fi. */
    private suspend fun sync(key: String): Boolean = oneAtATime.withLock {
        if (!onWifi()) return@withLock true
        if (key !in settings.keptCollections.value) return@withLock false
        try {
            // Asked and not answered in full: what's known stays as it was, and it's asked again later.
            val current = tracksOf(key) ?: return@withLock false
            val seen = known.getStringSet(key, emptySet()).orEmpty()
            val fresh = current.filter { it.id.value !in seen }
            if (fresh.isNotEmpty()) downloads.keep(fresh)
            known.edit().putStringSet(key, ids(current)).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The service's answers are untrusted input: nothing here may take the app down.
        }
        false
    }

    private fun onWifi(): Boolean = connectivity.activeNetwork != null && !connectivity.isActiveNetworkMetered

    private fun ids(tracks: List<Track>): Set<String> = tracks.mapTo(HashSet()) { it.id.value }

    private suspend fun tracksOf(key: String): List<Track>? {
        val id = key.substringAfter(SEPARATOR)
        return when (key.substringBefore(SEPARATOR)) {
            LOCAL -> id.toLongOrNull()?.let { list -> library.entries(list).first().map { it.track } }
            PLAYLIST -> {
                val media = MediaId(id)
                val source = sources.of(media) ?: return null
                val page = source.playlist(media)
                if (page !is Outcome.Ok) return null
                withRest(source, page.value.tracks, page.value.next)
            }
            ALBUM -> {
                val media = MediaId(id)
                val source = sources.of(media) ?: return null
                val page = source.album(media)
                if (page !is Outcome.Ok) return null
                withRest(source, page.value.tracks, page.value.next)
            }
            else -> null
        }
    }

    private suspend fun withRest(source: MusicSource, first: List<Track>, next: PageToken?): List<Track>? {
        val all = ArrayList(first)
        var token = next
        var pages = 0
        while (token != null && pages++ < MostPages) {
            val more = source.tracksMore(token)
            if (more !is Outcome.Ok) return null
            all += more.value.items
            token = more.value.next
        }
        return all
    }

    companion object {
        private const val SEPARATOR = "|"
        private const val PLAYLIST = "playlist"
        private const val ALBUM = "album"
        private const val LOCAL = "local"

        fun playlist(id: MediaId) = "$PLAYLIST$SEPARATOR$id"
        fun album(id: MediaId) = "$ALBUM$SEPARATOR$id"
        fun local(id: Long) = "$LOCAL$SEPARATOR$id"

        /** Long enough after starting that the app has finished arriving. */
        private const val FirstLookMs = 20_000L

        private const val EveryMs = 6L * 60 * 60 * 1000

        /** How soon to look again when there's no Wi-Fi yet. */
        private const val SoonMs = 30L * 60 * 1000

        /** More than any playlist has: a source that kept answering "more" can't keep this going for ever. */
        private const val MostPages = 200
    }
}
