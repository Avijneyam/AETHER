package app.aether.ytmusic

import kotlinx.serialization.json.JsonObject
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The parser against real answers, captured as a guest in India on 2026-11-03 (fixtures/, with the
 * visitor id and tracking parameters removed). When the service changes shape, recapture with
 * `./gradlew :ytmusic:liveCheck` first: it says which surface broke.
 */
class ParserFixturesTest {

    private fun fixture(name: String): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json.gz")) { "missing fixture $name" }
        val bytes = GZIPInputStream(stream).use { it.readBytes() }
        return checkNotNull(YouTubeMusicClient.parseJson(bytes)) { "unreadable fixture $name" }
    }

    private val p = YouTubeMusicParser

    @Test
    fun `home has its chips, background, quick picks and a continuation`() {
        val home = p.home(fixture("home"))
        assertEquals(
            listOf("Podcasts", "Romance", "Feel good", "Relax", "Party", "Energize", "Workout", "Commute", "Sad", "Focus", "Sleep"),
            home.chips.map { it.title },
        )
        assertTrue(home.chips.all { it.destination.browseId == YouTubeMusic.HOME && it.destination.params != null })
        assertTrue(home.background.isNotEmpty())
        val quickPicks = home.shelves.first()
        assertEquals("Quick picks", quickPicks.title)
        assertEquals(ShelfStyle.ROWS, quickPicks.style)
        assertTrue(quickPicks.items.size >= 10)
        val first = quickPicks.items.first() as Song
        assertEquals(MediaKind.SONG, first.kind)
        assertTrue(first.artists.isNotEmpty())
        assertNotNull(first.plays)
        // Tapping a quick pick plays its radio, as the page says.
        assertEquals(WatchTarget(first.videoId, "RDAMVM${first.videoId}", "wAEB"), first.watchTarget)
        assertNotNull(home.continuation)
    }

    @Test
    fun `home continues with shelves that carry straplines`() {
        val more = p.home(fixture("home_continuation"))
        assertTrue(more.chips.isEmpty())
        val dancing = more.shelves.first { it.title == "Dancing on your own" }
        assertEquals("DANCE YOUR STRESS AWAY", dancing.strapline)
        assertTrue(dancing.items.all { it is Playlist })
        assertNotNull(more.continuation)
    }

    @Test
    fun `a chip filters home`() {
        val relax = p.home(fixture("home_chip_relax"))
        val first = relax.shelves.first()
        assertEquals("Coffee shop blends", first.title)
        assertEquals("FOR RELAXING", first.strapline)
    }

    @Test
    fun `explore has its four entries and its shelves`() {
        val explore = p.explore(fixture("explore"))
        assertEquals(listOf("New releases", "Charts", "Moods & genres", "Podcasts"), explore.links.map { it.title })
        assertEquals(
            listOf("FEmusic_new_releases", "FEmusic_charts", "FEmusic_moods_and_genres", "FEmusic_non_music_audio"),
            explore.links.map { it.destination.browseId },
        )
        assertTrue(explore.links.all { it.icon != null })
        val titles = explore.shelves.map { it.title }
        assertTrue(titles.containsAll(listOf("New albums & singles", "Moods & genres", "Popular episodes", "Trending", "New music videos")), "$titles")
        assertTrue(explore.shelves.first { it.title == "New albums & singles" }.items.all { it is Album })
        assertTrue(explore.shelves.first { it.title == "Moods & genres" }.items.all { it is Link && it.color != null })
        val episodes = explore.shelves.first { it.title == "Popular episodes" }.items
        assertTrue(episodes.isNotEmpty() && episodes.all { it is Song && it.kind == MediaKind.EPISODE })
        // Videos that open a page of their own (MPED…) still play from their thumbnail.
        val videos = explore.shelves.first { it.title == "New music videos" }
        assertTrue(videos.wide)
        assertTrue(videos.items.size >= 20 && videos.items.all { it is Song })
    }

    @Test
    fun `charts know their country and every country offered`() {
        val india = p.charts(fixture("charts_in"))
        assertEquals(Country("IN", "India"), india.country)
        assertTrue(india.countries.size >= 60, "${india.countries.size} countries")
        assertTrue(Country("ZZ", "Global") in india.countries)
        assertTrue(Country("US", "United States") in india.countries)
        val titles = india.shelves.map { it.title }
        assertEquals(listOf("Video charts", "Languages", "Top artists"), titles)
        assertTrue(india.shelves[0].items.all { it is Playlist })
        val artists = india.shelves.first { it.title == "Top artists" }.items
        assertEquals(40, artists.size)
        assertTrue(artists.all { it is Artist && it.subtitle != null })

        val us = p.charts(fixture("charts_us"))
        assertEquals("US", us.country?.code)
        assertTrue(us.shelves.any { it.title == "Genres" })
        assertTrue(us.shelves.first { it.title == "Weekly top podcast shows" }.items.all { it is Podcast })
    }

    @Test
    fun `moods and genres come in coloured groups`() {
        val groups = p.moods(fixture("moods"))
        assertEquals(listOf("Moods & moments", "Genres"), groups.map { it.title })
        val all = groups.flatMap { it.moods }
        assertTrue(all.size >= 40)
        assertTrue(all.all { it.color != null && it.destination.browseId == "FEmusic_moods_and_genres_category" && it.destination.params != null })
    }

    @Test
    fun `a mood is a page of playlist shelves`() {
        val chill = p.browsePage(fixture("mood_chill"))
        assertEquals("Chill", chill.title)
        assertTrue(chill.shelves.size >= 10)
        assertTrue(chill.shelves.all { s -> s.items.all { it is Playlist } })
    }

    @Test
    fun `new releases and podcasts are pages of shelves`() {
        val releases = p.browsePage(fixture("new_releases"))
        assertEquals("New releases", releases.title)
        assertTrue(releases.shelves.any { s -> s.title == "Albums & singles" && s.items.all { it is Album } })
        val podcasts = p.browsePage(fixture("podcasts"))
        assertTrue(podcasts.shelves.first().items.all { it is Podcast })
        assertTrue(podcasts.shelves.drop(1).flatMap { it.items }.all { it is Song && it.kind == MediaKind.EPISODE })
    }

    @Test
    fun `an album has its tracks, counts and playlist`() {
        val album = assertNotNull(p.collection(fixture("album"), albumPage = true))
        assertEquals("Naadham En Jeevane", album.title)
        assertEquals("Album • 2026", album.subtitle)
        assertEquals("9 songs • 43 minutes", album.secondSubtitle)
        assertEquals(9, album.trackCount)
        assertEquals(9, album.tracks.size)
        assertEquals(2026, album.year)
        val first = album.tracks.first()
        assertEquals("NE9NAors5A4", first.videoId)
        assertEquals(283_000L, first.durationMs)
        assertEquals("15K plays", first.plays)
        assertTrue(album.playlistId!!.startsWith("OLAK5uy_"))
    }

    @Test
    fun `a chart is a playlist of twenty`() {
        val chart = assertNotNull(p.collection(fixture("playlist_chart"), albumPage = false))
        assertEquals("Trending 20 India", chart.title)
        assertEquals(20, chart.tracks.size)
        assertEquals("BPZkZpGMpXA", chart.tracks.first().videoId)
    }

    @Test
    fun `an artist has their audience, description and every shelf`() {
        val artist = assertNotNull(p.artist(fixture("artist")))
        assertEquals("Alka Yagnik", artist.name)
        assertEquals("657M monthly audience", artist.monthlyAudience)
        assertEquals("2.31M", artist.subscribers)
        assertTrue(artist.description!!.startsWith("Alka Yagnik is"))
        val titles = artist.shelves.map { it.title }
        assertTrue(titles.containsAll(listOf("Top songs", "Albums", "Singles & EPs", "Videos", "Featured on", "Fans might also like")), "$titles")
        assertEquals(5, artist.songs.size)
        assertTrue(artist.albums.isNotEmpty() && artist.singles.isNotEmpty())
        assertTrue(artist.related.isNotEmpty())
        assertNotNull(artist.radio)
    }

    @Test
    fun `search has its filters and a mixed list`() {
        val search = p.search(fixture("search"))
        val labels = search.filters.map { it.label }
        assertTrue(labels.containsAll(listOf("Songs", "Videos", "Albums", "Artists", "Community playlists", "Featured playlists", "Episodes", "Podcasts")), "$labels")
        assertTrue(search.items.size >= 10)
        assertTrue(search.items.any { it is Song } && search.items.any { it is Artist } && search.items.any { it is Album })
    }

    @Test
    fun `suggestions have queries and items`() {
        val s = p.suggestions(fixture("suggestions"))
        assertTrue("arijit singh" in s.queries)
        assertTrue(s.items.isNotEmpty())
    }

    @Test
    fun `artwork is asked bigger where the host resizes`() {
        val small = listOf(Thumbnail("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", 120, 120))
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", small.urlFor(544))
        val avatar = listOf(Thumbnail("https://yt3.ggpht.com/xyz=s88-c-k-c0x00ffffff-no-rj", 88, 88))
        assertEquals("https://yt3.ggpht.com/xyz=s600-c-k-c0x00ffffff-no-rj", avatar.urlFor(600))
        val still = listOf(Thumbnail("https://i.ytimg.com/vi/id/hqdefault.jpg", 480, 360))
        assertEquals("https://i.ytimg.com/vi/id/hqdefault.jpg", still.urlFor(1000))
    }
}
