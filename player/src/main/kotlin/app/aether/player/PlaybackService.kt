package app.aether.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.PlayRecorder
import app.aether.core.model.QueueStore
import app.aether.core.model.RepeatMode
import app.aether.core.model.SavedQueue
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import android.os.Build
import android.os.Bundle

/** What the service needs from the app: streams, a place for the queue, a record of plays. */
interface PlaybackEnvironment {
    suspend fun stream(id: MediaId): Outcome<Stream>
    fun invalidate(id: MediaId)
    val queueStore: QueueStore
    val recorder: PlayRecorder
    val http: OkHttpClient
    fun sessionActivity(): PendingIntent?
}

/**
 * The engine. It owns the player, runs in the foreground while music plays, survives the screens
 * going away, and is the only place that touches ExoPlayer: screens send commands through a
 * [PlaybackConnection] and read its state.
 *
 * - Streams resolve when the player first reads an item (`aether://track/<id>`), never earlier, so
 *   an address can't expire waiting in the queue. A stream refused mid-song is resolved afresh
 *   once and picks up where it stopped.
 * - Audio leaves the decoder as float where the device takes it: nothing above 16 bits is cut,
 *   nothing is resampled here.
 * - The queue is written on every change and restored on a cold start, paused where it was.
 * - A song counts as played after 30 s, or half of it if shorter. Nothing else is recorded.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = MainScope()
    private lateinit var env: PlaybackEnvironment
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private var saveJob: Job? = null
    private var retried: String? = null
    private var counted: String? = null

    /** The bit-perfect route watcher (Android 14 and later); typed loosely so older phones never load it. */
    private var bitPerfect: Any? = null

    override fun onCreate() {
        super.onCreate()
        env = application as PlaybackEnvironment
        // Kept songs play from the phone; everything else streams (and is never written to the keep).
        val audio = CacheDataSource.Factory()
            .setCache(AudioStore.cache(this))
            .setCacheKeyFactory(AudioStore::keyOf)
            .setUpstreamDataSourceFactory(AudioStore.upstream(this))
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableAudioFloatOutput(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(audio))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(Watcher())
        session = MediaLibrarySession.Builder(this, player, Callback())
            .apply { env.sessionActivity()?.let { setSessionActivity(it) } }
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            bitPerfect = BitPerfect(this, player) { route ->
                session?.setSessionExtras(Bundle().apply {
                    putString(EXTRA_OUTPUT, route.device)
                    putBoolean(EXTRA_BIT_PERFECT, route.bitPerfect)
                })
            }.also { it.start() }
        }
        scope.launch { restore() }
        scope.launch { countPlays() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the app while music plays keeps it playing; with nothing playing, the service goes.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 34) (bitPerfect as? BitPerfect)?.stop()
        saveNow()
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun restore() {
        if (player.mediaItemCount > 0) return
        val saved = withContext(Dispatchers.IO) { runCatching { env.queueStore.load() }.getOrNull() } ?: return
        if (player.mediaItemCount > 0) return
        player.setMediaItems(saved.tracks.map { it.toMediaItem() }, saved.index, saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = when (saved.repeat) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        player.prepare()
    }

    private fun snapshot(): SavedQueue = SavedQueue(
        tracks = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toTrack() },
        index = player.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = player.currentPosition.coerceAtLeast(0),
        shuffle = player.shuffleModeEnabled,
        repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        },
    )

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            val q = snapshot()
            withContext(Dispatchers.IO + NonCancellable) { runCatching { env.queueStore.save(q) } }
        }
    }

    private fun saveNow() {
        val q = snapshot()
        runBlocking(Dispatchers.IO) { runCatching { env.queueStore.save(q) } }
    }

    /** Every few seconds while playing: the position for a restart, and whether this song now counts as played. */
    private suspend fun countPlays() {
        var ticks = 0
        while (scope.isActive) {
            delay(2_000)
            if (!player.isPlaying) continue
            val item = player.currentMediaItem ?: continue
            val key = "${player.currentMediaItemIndex}|${item.mediaId}"
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
            if (counted != key && player.currentPosition >= minOf(30_000L, duration / 2)) {
                counted = key
                item.toTrack()?.let { t -> scope.launch(Dispatchers.IO) { runCatching { env.recorder.played(t) } } }
            }
            if (++ticks % 5 == 0) saveSoon()
        }
    }

    private inner class Watcher : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = saveSoon()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retried = null
            saveSoon()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // A new song may be a new format: the route is looked at again.
            if (Build.VERSION.SDK_INT >= 34) (bitPerfect as? BitPerfect)?.reconsider()
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveSoon()
        override fun onRepeatModeChanged(repeatMode: Int) = saveSoon()
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveSoon() }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            val refused = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode in setOf(403, 410)
            // A stream that stopped being served: resolve it afresh once, from where it stopped.
            if (refused && retried != item.mediaId) {
                retried = item.mediaId
                env.invalidate(MediaId(item.mediaId))
                val at = player.currentPosition
                player.seekTo(player.currentMediaItemIndex, at)
                player.prepare()
                player.play()
            }
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.playable() }.toMutableList())

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future {
                val saved = withContext(Dispatchers.IO) { runCatching { env.queueStore.load() }.getOrNull() }
                    ?: throw UnsupportedOperationException("Nothing to resume")
                MediaSession.MediaItemsWithStartPosition(saved.tracks.map { it.toMediaItem() }, saved.index, saved.positionMs)
            }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder().setMediaId("root").setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setTitle("AETHER").build(),
                    ).build(),
                    params,
                ),
            )

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf((0 until player.mediaItemCount).map { player.getMediaItemAt(it) }), params))
    }
}

/**
 * Turns `aether://track/<id>` into the source's stream as the player reads it: address, the
 * headers the source requires, and a cache key that survives the address changing.
 */
@OptIn(UnstableApi::class)
internal class Resolver(private val env: PlaybackEnvironment) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = dataSpec.uri.trackId() ?: return dataSpec
        val outcome = runBlocking { env.stream(id) }
        val stream = when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw IOException(describe(outcome.error))
        }
        return dataSpec.buildUpon()
            .setUri(stream.url)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + stream.headers)
            .setKey(stream.cacheKey)
            .build()
    }

    private fun describe(error: SourceError): String = when (error) {
        SourceError.Offline -> "You're offline"
        SourceError.Network -> "The connection dropped"
        is SourceError.RateLimited -> "The music service is busy"
        is SourceError.NotFound -> "This song is no longer available"
        is SourceError.NotPlayable -> "This song can't play right now"
        else -> "This song couldn't be loaded"
    }
}

internal const val EXTRA_OUTPUT = "aether.output"
internal const val EXTRA_BIT_PERFECT = "aether.bitPerfect"
