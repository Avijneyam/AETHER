package app.aether.ytmusic

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.util.Locale

/**
 * YouTube Music's catalogue, as the app uses it: every page the official app shows, as typed values.
 * Guest by default; with a [session] it answers as the signed-in account (personal Home, library).
 *
 * Nothing here tracks the listener: no playback statistics, no history pings, no analytics. The only
 * requests are the page loads a screen asks for.
 *
 * @param language the language pages are written in (`hl`): shelf titles, labels, dates.
 * @param region the country sent with each request (`gl`). Home and Explore follow the listener's
 *   real location regardless (verified 2026-11-03); Charts take any country through [charts].
 */
class YouTubeMusic(
    language: () -> String = { Locale.getDefault().language.ifBlank { "en" } },
    region: () -> String = { Locale.getDefault().country.takeIf { it.length == 2 } ?: "US" },
    userAgent: () -> String = { DESKTOP_USER_AGENT },
    session: () -> WebSession? = { null },
    onBehalfOf: () -> String? = { null },
    transport: HttpTransport = UrlConnectionTransport(),
) {
    private val client = YouTubeMusicClient(transport, userAgent, session, language, region, onBehalfOf = onBehalfOf)
    private val parser = YouTubeMusicParser

    // --- Home ------------------------------------------------------------------------------------

    /** Home's first page: its chips, background and first shelves. Continue with [homeMore]. */
    suspend fun home(): YtResult<HomePage> = client.browse(HOME).map(parser::home)

    /** Home filtered by one of its chips ("Relax", "Podcasts"…). Continue with [homeMore]. */
    suspend fun home(chip: Chip): YtResult<HomePage> =
        client.browse(chip.destination.browseId, chip.destination.params).map(parser::home)

    /** More of Home's shelves (no chips, no background). */
    suspend fun homeMore(continuation: Continuation): YtResult<HomePage> =
        client.browse(HOME, continuation = continuation).map(parser::home)

    // --- Explore, charts, moods, new releases, podcasts -------------------------------------------

    suspend fun explore(): YtResult<ExplorePage> = client.browse(EXPLORE).map(parser::explore)

    /**
     * The charts for [countryCode] (ISO 3166 alpha-2, "ZZ" for Global), or for where the listener is
     * when null. The answer lists every country offered.
     */
    suspend fun charts(countryCode: String? = null): YtResult<ChartsPage> {
        val form = countryCode?.let { code -> buildJsonObject { putJsonArray("selectedValues") { add(code) } } }
        return client.browse(CHARTS, formData = form).map(parser::charts)
    }

    /** Moods & genres: groups of tiles; open one with [browse]. */
    suspend fun moods(): YtResult<List<MoodGroup>> = client.browse(MOODS).map(parser::moods)

    suspend fun newReleases(): YtResult<BrowsePage> = browse(Destination(NEW_RELEASES))

    /** Every new album and single (a grid of about 140). */
    suspend fun newAlbums(): YtResult<BrowsePage> = browse(Destination(NEW_ALBUMS))

    /** Every new music video (a grid of about 100). */
    suspend fun newVideos(): YtResult<BrowsePage> = browse(Destination(NEW_VIDEOS))

    suspend fun podcasts(): YtResult<BrowsePage> = browse(Destination(PODCASTS))

    /** Any page of shelves a tile, chip or "More" opens: a mood or genre, a shelf's full list. */
    suspend fun browse(destination: Destination): YtResult<BrowsePage> =
        client.browse(destination.browseId, destination.params).map(parser::browsePage)

    suspend fun browseMore(continuation: Continuation): YtResult<BrowsePage> =
        client.browse("", continuation = continuation).map(parser::browsePage)

    // --- Albums, playlists, artists ------------------------------------------------------------------

    suspend fun album(browseId: String): YtResult<Collection> =
        client.browse(browseId).flatMap { parser.collection(it, albumPage = true).orUnreadable() }

    /** A playlist by its id (without "VL"): charts, mixes, the listener's own. */
    suspend fun playlist(playlistId: String): YtResult<Collection> =
        client.browse("VL${playlistId.removePrefix("VL")}").flatMap { parser.collection(it, albumPage = false).orUnreadable() }

    /** More of an album's or playlist's tracks. */
    suspend fun tracksMore(continuation: Continuation): YtResult<Page<Song>> =
        client.browse("", continuation = continuation).map(parser::songsContinuation)

    suspend fun artist(browseId: String): YtResult<ArtistPage> =
        client.browse(browseId).flatMap { parser.artist(it).orUnreadable() }

    // --- Search ---------------------------------------------------------------------------------------

    /** Everything that matches, or only what a [filter] from an earlier answer keeps (Songs, Albums…). */
    suspend fun search(query: String, filter: SearchFilter? = null): YtResult<SearchPage> =
        client.search(query, filter?.params).map(parser::search)

    suspend fun searchMore(query: String, continuation: Continuation): YtResult<Page<Item>> =
        client.search(query, continuation = continuation).map { parser.searchContinuation(it) }

    suspend fun suggestions(input: String): YtResult<Suggestions> =
        client.searchSuggestions(input).map(parser::suggestions)

    // --- Playing ------------------------------------------------------------------------------------

    /** The queue that follows a song (or a playlist, a radio), and where its lyrics and related music are. */
    suspend fun upNext(target: WatchTarget): YtResult<UpNext> =
        client.next(target.videoId, target.playlistId, target.params).map(parser::upNext)

    suspend fun upNextMore(target: WatchTarget, continuation: Continuation): YtResult<Page<Song>> =
        client.next(target.videoId, target.playlistId, target.params, continuation).map(parser::upNextContinuation)

    /** A song's plain lyrics, from [UpNext.lyrics]; null when the song has none. */
    suspend fun lyrics(destination: Destination): YtResult<Lyrics?> =
        client.browse(destination.browseId, destination.params).map(parser::lyrics)

    /** "You might also like", "Recommended playlists", "Similar artists" for a song, from [UpNext.related]. */
    suspend fun related(destination: Destination): YtResult<List<Shelf>> =
        browse(destination).map { it.shelves }

    // --- The signed-in account -------------------------------------------------------------------------

    suspend fun account(): YtResult<Account> = client.accountMenu().flatMap { parser.account(it).orUnreadable() }

    /** One of the library's tabs: [LIBRARY_PLAYLISTS], [LIBRARY_ALBUMS], [LIBRARY_ARTISTS], [LIBRARY_SONGS]. */
    suspend fun library(browseId: String): YtResult<Page<Item>> = client.browse(browseId).map(parser::library)

    suspend fun libraryMore(continuation: Continuation): YtResult<Page<Item>> =
        client.browse("", continuation = continuation).map(parser::libraryContinuation)

    suspend fun likedSongs(): YtResult<Collection> = playlist(LIKED_PLAYLIST)

    suspend fun history(): YtResult<List<HistoryItem>> = client.browse(HISTORY).map(parser::history)

    suspend fun like(videoId: String, liked: Boolean): YtResult<Unit> = client.like(videoId, liked).map { }

    companion object {
        const val HOME = "FEmusic_home"
        const val EXPLORE = "FEmusic_explore"
        const val CHARTS = "FEmusic_charts"
        const val MOODS = "FEmusic_moods_and_genres"
        const val NEW_RELEASES = "FEmusic_new_releases"
        const val NEW_ALBUMS = "FEmusic_new_releases_albums"
        const val NEW_VIDEOS = "FEmusic_new_releases_videos"
        const val PODCASTS = "FEmusic_podcasts"
        const val HISTORY = "FEmusic_history"
        const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
        const val LIBRARY_ALBUMS = "FEmusic_liked_albums"
        const val LIBRARY_ARTISTS = "FEmusic_library_corpus_track_artists"
        const val LIBRARY_SONGS = "FEmusic_liked_videos"
        const val LIKED_PLAYLIST = "LM"

        /** The desktop browser the music page is asked as (its pages differ by browser). */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}
