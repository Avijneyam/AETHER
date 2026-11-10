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
import app.aether.core.model.StreamPurpose
import app.aether.core.model.Track
import app.aether.player.PlaybackConnection
import app.aether.core.data.EqualiserStore
import app.aether.core.model.EqProfile
import app.aether.core.data.PlaylistSnapshots
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
import app.aether.core.lyrics.LyricsChain
import app.aether.core.lyrics.LyricsRequest
import app.aether.core.lyrics.LyricsResult
import app.aether.core.lyrics.LrcWriter
import app.aether.core.lyrics.LyricsProvider
import app.aether.core.lyrics.LrcRed
import app.aether.core.lyrics.BiniLyrics
import app.aether.core.lyrics.BetterLyrics
import app.aether.core.lyrics.LyricsPlus
import app.aether.core.lyrics.Unison
import app.aether.core.lyrics.KuGou
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
import app.aether.update.UpdateCenter

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
    // Kept songs written out to the Music folder are AETHER's own: listed under Kept, not twice.
    val local = LocalSource(app) { uri -> downloads.files.isKept(uri) }
    val sources = SourceManager(listOf(youtube, local), primaryId = youtube.id)
    val catalog = Catalog()

    /** What each catalogue playlist holds, as last seen, for its kept songs to be found offline. */
    val snapshots = PlaylistSnapshots(app)

    /** Whether the phone can reach the internet now. */
    val online = Online(app)
    val playback = PlaybackConnection(app, scope)

    /**
     * Songs kept on the phone to play offline, each a file in the listener's Music folder with its
     * words and cover in its tags (the words only when timed lyrics are on, the cover as the source has it).
     */
    val downloads = Downloads(
        app,
        scope,
        lyricsOf = { t ->
            val answer = lyrics.lyrics(LyricsRequest(t.id.toString(), t.title, t.artistLine, t.album?.title, t.durationMs))
            (answer as? LyricsResult.Found)?.lyrics?.let(LrcWriter::write)
        },
        coverOf = { t ->
            val url = t.artwork.url(1200) ?: return@Downloads null
            withContext(Dispatchers.IO) {
                runCatching { http.newCall(Request.Builder().url(url).header("User-Agent", UserAgent).build()).await().use { r -> if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size < 4 * 1024 * 1024 } else null } }.getOrNull()
            }
        },
    ).also { it.start() }

    /** Lyrics already on the phone: a song's .lrc, the words in its file, a kept song's own. */
    val localLyrics = LocalLyrics(app, local, settings) { id -> downloads.keptFile(id) }

    /** Kept playlists and albums go on being kept: what's added to them later comes down on Wi-Fi. */
    val keepSync = KeepSync(app, scope, sources, library, downloads, settings).also { it.start() }

    /** AETHER's own updates, from its GitHub releases (PODIUM's update check), off the main thread. */
    val updates: UpdateCenter = UpdateCenter.of(app, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    init {
        // The listener's own sources, reconnected by their addresses.
        scope.launch {
            settings.addons.value.forEach { address ->
                (AddonSource.connect(address, http) { settings.addonChoices(address) } as? Outcome.Ok)?.value?.let(sources::register)
            }
        }
    }

    private val appleArt = AppleArt(
        ArtHttp { url ->
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).header("User-Agent", UserAgent).build()).execute().use {
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

    /** Lyrics libraries are asked quickly: words that come after the second chorus are no use to anyone. */
    private val lyricsHttp: LyricsHttp = object : LyricsHttp {
        private val client = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).build()
        override suspend fun get(url: String): Pair<Int, String> = reply(url).let { it.code to it.body }
        override suspend fun reply(url: String): LyricsHttp.Reply {
            val request = Request.Builder().url(url).header("User-Agent", UserAgent).header("Accept", "application/json, text/xml, */*").build()
            return client.newCall(request).await().use { r ->
                LyricsHttp.Reply(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.toLongOrNull())
            }
        }
    }

    private val lrclib = LrclibProvider(lyricsHttp)

    /**
     * The lyrics libraries, as BitChord and LastWave ask them: those that time words first (Apple's
     * timings through lrc.red, BiniLyrics and BetterLyrics, then LyricsPlus and Unison), then those
     * that time lines (LRCLIB, KuGou). All are asked at once and the best answer kept.
     */
    private val libraries: List<LyricsProvider> = listOf(
        LrcRed(lyricsHttp),
        BiniLyrics(lyricsHttp),
        BetterLyrics(lyricsHttp),
        LyricsPlus(lyricsHttp),
        Unison(lyricsHttp),
        lrclib,
        KuGou(lyricsHttp),
    )

    /**
     * Timed lyrics from the libraries above (PODIUM's lyrics layer, BitChord's sources), cached on the
     * phone, asked only with the listener's consent and only of the libraries they leave on.
     */
    val lyrics = LyricsRepository(
        provider = LyricsChain({ libraries.filter { it.id !in settings.lyricsSourcesOff.value } }),
        cache = FileLyricsCache(File(app.cacheDir, "lyrics")),
        consent = { settings.timedLyrics.value },
    )

    /** Every library, in the order it's preferred, for Settings. */
    val lyricsLibraries: List<LyricsProvider> get() = libraries
}

class AetherApp : Application(), PlaybackEnvironment, SingletonImageLoader.Factory {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        CrashNote.keep(this)
        container = Container(this)
        keepCoversOffline()
    }

    /**
     * A kept song plays without a connection, so its cover must show without one too: each kept song's
     * cover, at every size the catalogue gives, is fetched into the image cache once as it's kept.
     */
    private fun keepCoversOffline() {
        val fetched = HashSet<String>()
        container.scope.launch {
            container.downloads.entries.collect { entries ->
                val loader = coil3.SingletonImageLoader.get(this@AetherApp)
                entries.values.filter { it.status == app.aether.player.Downloads.Status.Kept }
                    .flatMap { e -> e.track.artwork.sizes.map { it.url } }
                    .filter { it.startsWith("http") && fetched.add(it) }
                    .forEach { url ->
                        loader.enqueue(
                            coil3.request.ImageRequest.Builder(this@AetherApp).data(url)
                                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build(),
                        )
                    }
            }
        }
    }

    // --- The engine's needs ---

    override suspend fun stream(id: MediaId, purpose: StreamPurpose, track: Track?): Outcome<Stream> =
        container.sources.stream(id, container.settings.efficient.value, purpose, track)
    override fun invalidate(id: MediaId) = container.sources.invalidate(id)
    override fun keptFile(id: MediaId): android.net.Uri? = container.downloads.keptFile(id)
    override fun knownTrack(id: MediaId): Track? = container.downloads.entries.value[id]?.track?.takeIf { it.title.isNotBlank() }
    override val queueStore: QueueStore get() = container.queueStore
    override val recorder: PlayRecorder get() = container.library
    override val http: OkHttpClient get() = container.http
    override val equaliser: StateFlow<EqProfile?> get() = container.equaliser.shaping
    override val crossfadeSeconds: StateFlow<Int> get() = container.settings.crossfade
    override val lateCopies: kotlinx.coroutines.flow.Flow<MediaId> get() = container.sources.lateCopies
    override fun lateCopy(id: MediaId): Stream? = container.sources.lateCopy(id)
    override fun hearingCopy(id: MediaId, hearing: Boolean) = if (hearing) container.sources.hearing(id) else container.sources.notHearing(id)
    override fun adoptCopy(id: MediaId): Boolean = container.sources.adopt(id)
    override fun dropLateCopy(id: MediaId) = container.sources.dropLate(id)

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
