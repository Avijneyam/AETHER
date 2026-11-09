package app.aether

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import app.aether.core.data.DatabaseQueueStore
import app.aether.core.data.Library
import app.aether.core.data.Settings
import app.aether.core.data.SourceManager
import app.aether.core.data.db.AetherDatabase
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.PlayRecorder
import app.aether.core.model.QueueStore
import app.aether.core.model.Stream
import app.aether.player.PlaybackConnection
import app.aether.core.data.EqualiserStore
import app.aether.core.model.EqProfile
import app.aether.player.PlaybackEnvironment
import kotlinx.coroutines.flow.StateFlow
import app.aether.sources.youtube.YouTubeSource
import app.aether.sources.youtube.YouTubeStreamResolver
import app.aether.ui.common.Catalog
import app.aether.ytmusic.YouTubeMusic
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.Locale
import java.util.concurrent.TimeUnit
import app.aether.core.lyrics.FileLyricsCache
import app.aether.core.lyrics.LrclibProvider
import app.aether.core.lyrics.LyricsHttp
import app.aether.core.lyrics.LyricsRepository
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import app.aether.player.Downloads
import app.aether.sources.local.LocalSource
import app.aether.core.data.Account
import app.aether.ytmusic.WebSession
import app.aether.sources.addon.AddonSource
import app.aether.core.model.AlbumArtProvider
import app.aether.core.model.ArtistArtProvider
import app.aether.sources.apple.AppleArt
import app.aether.sources.apple.ArtHttp
import kotlinx.coroutines.launch

/** Everything the app is made of, built once. Screens reach it through `LocalContainer`. */
class Container(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val settings = Settings(app)
    val equaliser = EqualiserStore(app, scope)
    val database = AetherDatabase.open(app)
    val library = Library(database)
    val queueStore = DatabaseQueueStore(database)

    /** The listener's YouTube Music session, if they've signed in (sealed with the Keystore). */
    val account = Account(app)

    private val language = { Locale.getDefault().language.ifBlank { "en" } }
    private val region = { Locale.getDefault().country.takeIf { it.length == 2 } ?: "US" }
    val youtube = YouTubeSource(
        YouTubeMusic(language = language, region = region, session = { account.cookies.value?.let(WebSession::parse) }),
        YouTubeStreamResolver(http, region),
    )
    val local = LocalSource(app)
    val sources = SourceManager(listOf(youtube, local), primaryId = youtube.id)
    val catalog = Catalog()
    val playback = PlaybackConnection(app, scope)

    /** Songs kept on the phone to play offline. */
    val downloads = Downloads(app, scope).also { it.start() }

    /** Kept playlists and albums go on being kept: what's added to them later comes down on Wi-Fi. */
    val keepSync = KeepSync(app, scope, sources, library, downloads, settings).also { it.start() }

    init {
        // The listener's own sources, reconnected by their addresses.
        scope.launch {
            settings.addons.value.forEach { address ->
                (AddonSource.connect(address, http) as? Outcome.Ok)?.value?.let(sources::register)
            }
        }
    }

    private val appleArt = AppleArt(
        ArtHttp { url ->
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).header("User-Agent", "AETHER/0.1 (Android music player)").build()).execute().use {
                    if (it.isSuccessful) it.body?.string() else null
                }
            }
        },
        File(app.cacheDir, "artist-art"),
    )

    /** An artist's portrait, lettering and film, kept on the phone; asked only with the listener's consent (the page checks). */
    val artistArt: ArtistArtProvider = appleArt

    /** An album's film and the lettering of who made it, on the same terms. */
    val albumArt: AlbumArtProvider = appleArt

    /** Timed lyrics from LRCLIB (PODIUM's lyrics layer), cached on the phone, asked only with the listener's consent. */
    val lyrics = LyricsRepository(
        provider = LrclibProvider(
            LyricsHttp { url ->
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).header("User-Agent", "AETHER/0.1 (Android music player)").build()).execute().use { it.code to (it.body?.string() ?: "") }
                }
            },
        ),
        cache = FileLyricsCache(File(app.cacheDir, "lyrics")),
        consent = { settings.timedLyrics.value },
    )
}

class AetherApp : Application(), PlaybackEnvironment, SingletonImageLoader.Factory {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }

    // --- The engine's needs ---

    override suspend fun stream(id: MediaId): Outcome<Stream> = container.sources.stream(id, container.settings.efficient.value)
    override fun invalidate(id: MediaId) = container.sources.invalidate(id)
    override val queueStore: QueueStore get() = container.queueStore
    override val recorder: PlayRecorder get() = container.library
    override val http: OkHttpClient get() = container.http
    override val equaliser: StateFlow<EqProfile?> get() = container.equaliser.shaping

    override fun sessionActivity(): PendingIntent? = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // --- Artwork ---

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
            add(AudioArtFetcher.Factory(context))
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.33).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(320)
        .build()
}
