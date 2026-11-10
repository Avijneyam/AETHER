package app.aether.ui.library

import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.MediaId
import app.aether.core.model.PhoneSong
import app.aether.core.model.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineAlbumsTest {
    private fun song(id: String, title: String, artist: String, album: String?, number: Int? = null) =
        Track(MediaId.of("local", id), title, listOf(ArtistRef(artist)), album?.let { AlbumRef(it) }, trackNumber = number)

    private fun phone(track: Track, albumKey: String?, albumArtist: String? = null, disc: Int = 0, added: Long = 0) =
        PhoneSong(track, "Music/", albumKey, albumArtist, disc, added)

    @Test
    fun `the phone's songs are its own albums, disc by disc and track by track`() {
        val songs = listOf(
            phone(song("3", "Encore", "Band", "Live", 1), "7", disc = 2, added = 10),
            phone(song("1", "Opener", "Band", "Live", 1), "7", disc = 1, added = 10),
            phone(song("2", "Second", "Band", "Live", 2), "7", disc = 1, added = 12),
        )
        val album = phoneAlbums(songs).single()
        assertEquals("Live", album.title)
        assertEquals("Band", album.artist)
        assertEquals(listOf("Opener", "Second", "Encore"), album.tracks.map { it.title })
    }

    @Test
    fun `an album of many voices with no album artist is by various artists, and names no one to look up`() {
        val songs = listOf(
            phone(song("1", "A", "One", "Mix"), "9"),
            phone(song("2", "B", "Two", "Mix"), "9"),
        )
        val album = phoneAlbums(songs).single()
        assertEquals("Various artists", album.artist)
        assertTrue(album.asAlbum().artists.isEmpty())
        assertTrue(album.various)
    }

    @Test
    fun `the album the file names its artist for wins, and the newest albums come first`() {
        val songs = listOf(
            phone(song("1", "A", "Guest", "Old"), "1", albumArtist = "Singer", added = 5),
            phone(song("2", "B", "Other", "New"), "2", added = 50),
        )
        val albums = phoneAlbums(songs)
        assertEquals(listOf("New", "Old"), albums.map { it.title })
        assertEquals("Singer", albums[1].artist)
    }

    @Test
    fun `kept songs are grouped by their album, a song from none stands alone, the last kept first`() {
        val album = AlbumRef("Record", MediaId.of("youtube", "MPREb_1"))
        val kept = listOf(
            Track(MediaId.of("youtube", "a"), "Two", listOf(ArtistRef("Artist")), album, trackNumber = 2),
            Track(MediaId.of("youtube", "v"), "A video", listOf(ArtistRef("Someone"))),
            Track(MediaId.of("youtube", "b"), "One", listOf(ArtistRef("Artist")), album, trackNumber = 1),
        )
        val albums = keptAlbums(kept)
        assertEquals(listOf("Record", "A video"), albums.map { it.title })
        assertEquals(listOf("One", "Two"), albums[0].tracks.map { it.title })
        assertEquals("Artist", albums[0].artist)
    }
}
