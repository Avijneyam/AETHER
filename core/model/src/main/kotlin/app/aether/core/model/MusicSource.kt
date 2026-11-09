package app.aether.core.model

/** What a source call answers: a value, or why there is none. Sources never throw for the service's sake. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: SourceError) : Outcome<Nothing>
}

sealed interface SourceError {
    data object Offline : SourceError
    data object Network : SourceError
    data class Server(val code: Int?) : SourceError
    data class RateLimited(val retryAfterMs: Long?) : SourceError
    data object AuthRequired : SourceError
    data object AuthExpired : SourceError
    data class NotFound(val what: String) : SourceError

    /** Known, but not playable here (region, age, gone from every stream). */
    data class NotPlayable(val reason: String?) : SourceError

    /** This source doesn't offer this. */
    data object Unsupported : SourceError
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok)?.value

/** What a source can do. Screens ask before they offer. */
enum class Capability { HOME, EXPLORE, CHARTS, MOODS, SEARCH, ALBUMS, ARTISTS, PLAYLISTS, RADIO, LYRICS, STREAM }

/** The audio a source hands the player for one track. */
data class Stream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    /** Only when the source knows it; never a guess. */
    val bitDepth: Int? = null,
    val contentLength: Long? = null,
    /** When the address stops working (epoch ms), if it says. */
    val expiresAtMs: Long? = null,
    /** Stable across address changes: the cache keys on it, never on the url. */
    val cacheKey: String,
)

/**
 * A music provider. Each one is isolated in its own `sources:*` module and turns its own answers
 * into `core:model` types. Every call has a default that says it isn't offered, so a source
 * implements only what it really does, and [capabilities] says which.
 */
interface MusicSource {
    /** The prefix of every id it makes (`ytm`, `local`). */
    val id: String
    val name: String
    val capabilities: Set<Capability>

    suspend fun home(): Outcome<Feed> = unsupported()
    suspend fun homeMore(token: PageToken): Outcome<Feed> = unsupported()
    suspend fun filter(filter: FeedFilter): Outcome<Feed> = unsupported()
    suspend fun explore(): Outcome<Explore> = unsupported()
    suspend fun charts(countryCode: String?): Outcome<Charts> = unsupported()
    suspend fun moods(): Outcome<List<MoodGroup>> = unsupported()
    suspend fun browse(destination: Destination): Outcome<Feed> = unsupported()
    suspend fun browseMore(token: PageToken): Outcome<Feed> = unsupported()

    /** Who's signed in here, when someone is. */
    suspend fun account(): Outcome<ListenerAccount> = unsupported()

    suspend fun album(id: MediaId): Outcome<AlbumPage> = unsupported()
    suspend fun artist(id: MediaId): Outcome<ArtistPage> = unsupported()
    suspend fun playlist(id: MediaId): Outcome<PlaylistPage> = unsupported()
    suspend fun tracksMore(token: PageToken): Outcome<Page<Track>> = unsupported()

    suspend fun search(query: String, filter: SearchFilter? = null): Outcome<SearchResults> = unsupported()
    suspend fun searchMore(query: String, token: PageToken): Outcome<Page<MediaEntity>> = unsupported()
    suspend fun suggestions(input: String): Outcome<Suggestions> = unsupported()

    /** What follows [track]: its radio, or its playlist from where it sits. */
    suspend fun upNext(track: Track): Outcome<UpNext> = unsupported()
    suspend fun lyrics(destination: Destination): Outcome<Lyrics?> = unsupported()
    suspend fun related(destination: Destination): Outcome<List<FeedSection>> = unsupported()

    /** The audio for [id], at the best quality this source has unless [efficient]. */
    suspend fun stream(id: MediaId, efficient: Boolean = false): Outcome<Stream> = unsupported()

    /** Forget a stream the player was refused, so the next ask resolves afresh. */
    fun invalidate(id: MediaId) = Unit
}

private fun unsupported(): Outcome<Nothing> = Outcome.Err(SourceError.Unsupported)
