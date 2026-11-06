package app.aether.sources.youtube

import app.aether.core.model.AlbumPage
import app.aether.core.model.ArtistPage
import app.aether.core.model.Capability
import app.aether.core.model.Charts
import app.aether.core.model.Destination
import app.aether.core.model.Explore
import app.aether.core.model.Feed
import app.aether.core.model.FeedFilter
import app.aether.core.model.FeedSection
import app.aether.core.model.Lyrics
import app.aether.core.model.MediaEntity
import app.aether.core.model.MediaId
import app.aether.core.model.MoodGroup
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.Page
import app.aether.core.model.PageToken
import app.aether.core.model.PlaylistPage
import app.aether.core.model.SearchFilter
import app.aether.core.model.SearchResults
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import app.aether.core.model.Suggestions
import app.aether.core.model.Track
import app.aether.core.model.UpNext
import app.aether.ytmusic.Chip
import app.aether.ytmusic.WatchTarget
import app.aether.ytmusic.YouTubeMusic

/** YouTube Music as a [MusicSource]: its whole catalogue, and its audio through [streams]. */
class YouTubeSource(
    private val yt: YouTubeMusic,
    private val streams: YouTubeStreamResolver,
) : MusicSource {

    override val id = SOURCE
    override val name = "YouTube Music"
    override val capabilities = setOf(
        Capability.HOME, Capability.EXPLORE, Capability.CHARTS, Capability.MOODS, Capability.SEARCH, Capability.ALBUMS,
        Capability.ARTISTS, Capability.PLAYLISTS, Capability.RADIO, Capability.LYRICS, Capability.STREAM,
    )

    override suspend fun home(): Outcome<Feed> = yt.home().toOutcome { it.toFeed() }

    override suspend fun homeMore(token: PageToken): Outcome<Feed> = yt.homeMore(token.toContinuation()).toOutcome { it.toFeed() }

    override suspend fun filter(filter: FeedFilter): Outcome<Feed> =
        yt.home(Chip(filter.title, filter.destination.toYt())).toOutcome { it.toFeed() }

    override suspend fun explore(): Outcome<Explore> = yt.explore().toOutcome { it.toExplore() }

    override suspend fun charts(countryCode: String?): Outcome<Charts> = yt.charts(countryCode).toOutcome { it.toCharts() }

    override suspend fun moods(): Outcome<List<MoodGroup>> = yt.moods().toOutcome { groups -> groups.map { it.toMoodGroup() } }

    override suspend fun browse(destination: Destination): Outcome<Feed> = when (destination.key) {
        // Explore's entries that are pages of their own kind.
        YouTubeMusic.CHARTS -> charts(null).let { r -> if (r is Outcome.Ok) Outcome.Ok(Feed(r.value.sections, title = "Charts")) else r as Outcome.Err }
        else -> yt.browse(destination.toYt()).toOutcome { it.toFeed() }
    }

    override suspend fun browseMore(token: PageToken): Outcome<Feed> = yt.browseMore(token.toContinuation()).toOutcome { it.toFeed() }

    override suspend fun album(id: MediaId): Outcome<AlbumPage> = yt.album(id.key).toOutcome { it.toAlbumPage(id.key) }

    override suspend fun artist(id: MediaId): Outcome<ArtistPage> = yt.artist(id.key).toOutcome { it.toArtistPage(id.key) }

    override suspend fun playlist(id: MediaId): Outcome<PlaylistPage> = yt.playlist(id.key).toOutcome { it.toPlaylistPage(id.key) }

    override suspend fun tracksMore(token: PageToken): Outcome<Page<Track>> =
        yt.tracksMore(token.toContinuation()).toOutcome { p -> Page(p.items.map { it.toTrack() }, p.continuation?.toToken()) }

    override suspend fun search(query: String, filter: SearchFilter?): Outcome<SearchResults> =
        yt.search(query, filter?.let { app.aether.ytmusic.SearchFilter(it.label, it.key) }).toOutcome { it.toResults() }

    override suspend fun searchMore(query: String, token: PageToken): Outcome<Page<MediaEntity>> =
        yt.searchMore(query, token.toContinuation()).toOutcome { p -> Page(p.items.map { it.toEntity() }, p.continuation?.toToken()) }

    override suspend fun suggestions(input: String): Outcome<Suggestions> = yt.suggestions(input).toOutcome { it.toSuggestions() }

    override suspend fun upNext(track: Track): Outcome<UpNext> {
        if (track.id.source != SOURCE) return Outcome.Err(SourceError.Unsupported)
        val ctx = track.context
        val target = if (ctx?.key != null) WatchTarget(track.id.key, ctx.key, ctx.params) else WatchTarget.radio(track.id.key)
        return yt.upNext(target).toOutcome { it.toUpNext() }
    }

    override suspend fun lyrics(destination: Destination): Outcome<Lyrics?> = yt.lyrics(destination.toYt()).toOutcome { it?.toLyrics() }

    override suspend fun related(destination: Destination): Outcome<List<FeedSection>> =
        yt.related(destination.toYt()).toOutcome { shelves -> shelves.map { it.toSection() } }

    override suspend fun stream(id: MediaId, efficient: Boolean): Outcome<Stream> = streams.resolve(id.key, efficient)

    override fun invalidate(id: MediaId) = streams.forget(id.key)
}
