package app.aether.player

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.File
import java.util.concurrent.Executors

/**
 * Where kept songs live: one store on the phone, its index in a database, nothing ever evicted.
 * A song is kept under its own id (`track:<id>`), never under the stream's address, which changes
 * every few hours; so the player finds a kept song whatever its address is today.
 */
@OptIn(UnstableApi::class)
internal object AudioStore {
    @Volatile private var database: StandaloneDatabaseProvider? = null
    @Volatile private var cache: SimpleCache? = null
    @Volatile private var manager: DownloadManager? = null

    private fun database(context: Context) = database ?: synchronized(this) {
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }
    }

    fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(context.applicationContext.filesDir, "kept"), NoOpCacheEvictor(), database(context)).also { cache = it }
    }

    /** The key a song's audio is kept under. */
    fun keyOf(spec: DataSpec): String = spec.uri.trackId()?.let { "track:${it.value}" } ?: spec.key ?: spec.uri.toString()

    /**
     * Streams as the player gets them: resolved from the song's id as they're read. The app's HTTP
     * client is asked for only when a request is made, so this can be built while the app still is.
     */
    fun upstream(context: Context): DataSource.Factory {
        val env = context.applicationContext as PlaybackEnvironment
        val http = OkHttpDataSource.Factory(Call.Factory { request -> env.http.newCall(request) })
        return ResolvingDataSource.Factory(DefaultDataSource.Factory(context.applicationContext, http), Resolver(env))
    }

    /** The one download manager, shared by the screens and the service. Main thread only. */
    fun manager(context: Context): DownloadManager = manager ?: synchronized(this) {
        manager ?: DownloadManager(
            context.applicationContext,
            database(context),
            cache(context),
            CacheDataSource.Factory().setCache(cache(context)).setCacheKeyFactory(::keyOf).setUpstreamDataSourceFactory(upstream(context)),
            Executors.newFixedThreadPool(2),
        ).apply { maxParallelDownloads = 2 }.also { manager = it }
    }
}

/**
 * Songs kept on the phone to play without a connection (PODIUM's offline listening, on Media3's
 * downloads). Each keeps its whole track with it, so what's kept can be listed and played with no
 * network at all. Keeping runs in [KeepService], in the background, with its progress shown.
 */
@OptIn(UnstableApi::class)
class Downloads(private val context: Context, private val scope: CoroutineScope) {
    enum class Status { Queued, Keeping, Kept, Failed }

    /** A song being kept or kept: the track, how it stands, and how far along (0..1). */
    data class Entry(val track: Track, val status: Status, val progress: Float)

    private val manager by lazy { AudioStore.manager(context) }
    private val _entries = MutableStateFlow<Map<MediaId, Entry>>(emptyMap())
    val entries: StateFlow<Map<MediaId, Entry>> = _entries.asStateFlow()
    private var ticker: Job? = null

    /** Starts listening once the app has finished starting (the manager needs the app whole). */
    fun start() {
        Handler(Looper.getMainLooper()).post {
            manager.addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
                override fun onInitialized(downloadManager: DownloadManager) = refresh()
            })
            refresh()
        }
    }

    fun keep(tracks: List<Track>) {
        tracks.filter { it.available && it.id !in _entries.value }.forEach { t ->
            val request = DownloadRequest.Builder(t.id.value, trackUri(t.id)).setCustomCacheKey("track:${t.id.value}").setData(t.toBytes()).build()
            DownloadService.sendAddDownload(context, KeepService::class.java, request, false)
        }
    }

    fun forget(id: MediaId) = DownloadService.sendRemoveDownload(context, KeepService::class.java, id.value, false)

    private fun refresh() {
        val map = LinkedHashMap<MediaId, Entry>()
        var busy = false
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                val track = trackFromBytes(d.request.data) ?: continue
                val status = when (d.state) {
                    Download.STATE_COMPLETED -> Status.Kept
                    Download.STATE_DOWNLOADING -> Status.Keeping.also { busy = true }
                    Download.STATE_FAILED -> Status.Failed
                    Download.STATE_REMOVING -> continue
                    else -> Status.Queued.also { busy = true }
                }
                val progress = if (status == Status.Kept) 1f else (d.percentDownloaded.takeIf { it >= 0f } ?: 0f) / 100f
                map[track.id] = Entry(track, status, progress)
            }
        }
        _entries.value = map
        // The manager says when a song starts and ends, not how far it's got: while any is under way, look again each second.
        if (busy && ticker?.isActive != true) ticker = scope.launch {
            while (isActive) {
                delay(1000)
                val inFlight = manager.currentDownloads
                if (inFlight.isEmpty()) { refresh(); break }
                _entries.value = _entries.value.mapValues { (id, e) ->
                    inFlight.firstOrNull { it.request.id == id.value }?.let { d -> e.copy(status = Status.Keeping, progress = (d.percentDownloaded.takeIf { it >= 0f } ?: 0f) / 100f) } ?: e
                }
            }
        }
    }
}

/** Keeps songs in the background, with their progress in a notification. */
@OptIn(UnstableApi::class)
class KeepService : DownloadService(
    NOTIFICATION,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL,
    R.string.keep_channel,
    R.string.keep_channel_about,
) {
    override fun getDownloadManager(): DownloadManager = AudioStore.manager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, CHANNEL).buildProgressNotification(this, R.drawable.aether_keep, null, null, downloads, notMetRequirements)

    private companion object {
        const val NOTIFICATION = 7101
        const val CHANNEL = "keep"
    }
}
