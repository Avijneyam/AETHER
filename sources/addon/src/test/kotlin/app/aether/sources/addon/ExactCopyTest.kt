package app.aether.sources.addon

import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactCopyTest {
    private fun song(title: String, artist: String = "The Weeknd", album: String? = "Starboy", ms: Long? = 252_000, explicit: Boolean = false) =
        Track(MediaId("ytm:abc"), title, artist.split(", ").map { ArtistRef(it) }, album?.let { AlbumRef(it) }, ms, explicit = explicit)

    private fun row(title: String, artist: String? = "The Weeknd", album: String? = "Starboy", ms: Long? = 252_500, explicit: Boolean? = null, quality: String? = null, immersive: Boolean = false) =
        AddonTrack("t", title, artist, album, ms, null, explicit, quality = quality, immersive = immersive)

    @Test fun `the same song is a copy`() = assertTrue(ExactCopy.matches(song("Starboy"), row("Starboy")))

    @Test fun `packaging doesn't make another song`() = assertTrue(ExactCopy.matches(song("Starboy (Official Audio)"), row("Starboy")))

    @Test fun `a live take is never the studio song, either way`() {
        assertFalse(ExactCopy.matches(song("Starboy"), row("Starboy (Live)")))
        assertFalse(ExactCopy.matches(song("Starboy (Live)"), row("Starboy")))
    }

    @Test fun `a remix is never the original`() = assertFalse(ExactCopy.matches(song("Starboy"), row("Starboy (Kygo Remix)")))

    @Test fun `two remixes by different hands differ`() =
        assertFalse(ExactCopy.matches(song("Starboy (Kygo Remix)"), row("Starboy (Tiësto Remix)")))

    @Test fun `another artist's song of the same name is not it`() = assertFalse(ExactCopy.matches(song("Starboy"), row("Starboy", artist = "Someone Else")))

    @Test fun `a shared artist is enough`() = assertTrue(ExactCopy.matches(song("Starboy", "The Weeknd, Daft Punk"), row("Starboy", artist = "The Weeknd")))

    @Test fun `explicit and clean are different editions`() {
        assertFalse(ExactCopy.matches(song("Starboy", explicit = true), row("Starboy", explicit = false)))
        assertFalse(ExactCopy.matches(song("Starboy", explicit = false), row("Starboy", explicit = true)))
        assertTrue(ExactCopy.matches(song("Starboy", explicit = true), row("Starboy", explicit = true)))
        assertTrue(ExactCopy.matches(song("Starboy", explicit = true), row("Starboy", explicit = null)))
    }

    @Test fun `the lengths must be known and close`() {
        assertFalse(ExactCopy.matches(song("Starboy"), row("Starboy", ms = 262_000)))
        assertFalse(ExactCopy.matches(song("Starboy", ms = null), row("Starboy")))
        assertFalse(ExactCopy.matches(song("Starboy"), row("Starboy", ms = null)))
        assertTrue(ExactCopy.matches(song("Starboy"), row("Starboy", ms = 254_000)))
    }

    @Test fun `an immersive mix is never the stereo song`() = assertFalse(ExactCopy.matches(song("Starboy"), row("Starboy", immersive = true)))

    @Test fun `a featured artist the song doesn't have makes another version`() =
        assertFalse(ExactCopy.matches(song("Starboy"), row("Starboy (feat. Somebody)")))

    @Test fun `a featured artist the song credits is the song`() =
        assertTrue(ExactCopy.matches(song("Starboy", "The Weeknd, Daft Punk"), row("Starboy (feat. Daft Punk)")))

    @Test fun `a remaster on one side is taken only from the same album`() {
        assertTrue(ExactCopy.matches(song("Starboy"), row("Starboy (2016 Remaster)")))
        assertFalse(ExactCopy.matches(song("Starboy"), row("Starboy (2016 Remaster)", album = "Greatest Hits")))
    }

    @Test fun `the same album first, then the best copy`() {
        val other = row("Starboy", album = "Hits", quality = "HI_RES_LOSSLESS").copy(id = "a")
        val same = row("Starboy", quality = "LOSSLESS").copy(id = "b")
        val better = row("Starboy", quality = "HI_RES_LOSSLESS").copy(id = "c")
        assertEquals("c", ExactCopy.best(song("Starboy"), listOf(other, same, better))?.id)
        assertEquals("b", ExactCopy.best(song("Starboy"), listOf(other, same))?.id)
        assertNull(ExactCopy.best(song("Starboy"), listOf(row("Starboy (Live)"))))
    }

    @Test fun `searches carry the version and the first artist`() {
        assertEquals(listOf("Starboy The Weeknd", "Starboy"), ExactCopy.queries(song("Starboy")))
        assertEquals("Starboy live The Weeknd", ExactCopy.queries(song("Starboy (Live)")).first())
    }
}
