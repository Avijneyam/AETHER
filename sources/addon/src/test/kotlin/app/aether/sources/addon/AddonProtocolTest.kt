package app.aether.sources.addon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The protocol as BitChord's addons write it, read leniently. */
class AddonProtocolTest {
    private fun manifest(json: String) = assertNotNull(AddonProtocol.manifest(json.toByteArray()))
    private fun stream(json: String) = assertNotNull(AddonProtocol.stream(json.toByteArray()))

    @Test fun `quality goes at the best the addon lists, unless the listener chose another`() {
        val m = manifest("""{"id":"x","name":"X","resources":["search","stream"],"settings":[{"key":"quality","default":"HIGH","options":[{"value":"HI_RES","label":"Hi-Res"},{"value":"LOSSLESS"},{"value":"HIGH"}]},{"key":"region","default":"US"}]}""")
        assertEquals(mapOf("quality" to "HI_RES", "region" to "US"), m.query(emptyMap()))
        assertEquals("LOSSLESS", m.query(mapOf("quality" to "LOSSLESS"))["quality"])
        assertEquals("HI_RES", m.query(mapOf("quality" to "SOMETHING_ELSE"))["quality"])
    }

    @Test fun `an addon that declares no quality is asked for lossless`() =
        assertEquals("LOSSLESS", manifest("""{"id":"x","name":"X"}""").query(emptyMap())["quality"])

    @Test fun `an addon only for BitChord's own app is known`() =
        assertTrue(manifest("""{"id":"x","name":"X","bitchordAuth":1}""").onlyForBitChord)

    @Test fun `a manifest that declares nothing searches and streams`() {
        val m = manifest("""{"id":"x","name":"X","resources":[]}""")
        assertTrue(m.canSearch && m.canStream)
    }

    @Test fun `rows carry their own stream, quality and mix`() {
        val rows = assertNotNull(AddonProtocol.search("""{"tracks":[{"id":"1","title":"Song","artist":"A","duration":240.0,"audioQuality":"LOSSLESS","audioModes":["STEREO"],"streamURL":"https://cdn/x.flac"},{"id":"2","title":"Song","artist":"A","duration":240,"audioQuality":"LOW","audioModes":["DOLBY_ATMOS"]}]}""".toByteArray()))
        assertEquals(240_000L, rows[0].durationMs)
        assertEquals("https://cdn/x.flac", rows[0].streamUrl)
        assertFalse(rows[0].immersive)
        assertTrue(rows[1].immersive)
    }

    @Test fun `dash is read from format when the addon says it there`() {
        val s = stream("""{"url":"https://im-fa.manifest.tidal.com/1/manifests/abc","format":"dash","quality":"Tidal · Hi-Res FLAC (DASH)","streamQuality":"[Tidal] HI_RES_LOSSLESS 24-bit 96kHz"}""")
        assertEquals("dash", s.manifest)
        assertEquals(96_000, s.sampleRateHz)
        assertEquals(24, s.bitDepth)
    }

    @Test fun `a codec in format is a plain file`() = assertNull(stream("""{"url":"https://cdn/x","format":"flac"}""").manifest)

    @Test fun `encrypted is false, or a scheme`() {
        assertFalse(stream("""{"url":"https://cdn/x","encrypted":false}""").encrypted)
        assertFalse(stream("""{"url":"https://cdn/x","encrypted":"none"}""").encrypted)
        assertTrue(stream("""{"url":"https://cdn/x","encrypted":"widevine"}""").encrypted)
        assertTrue(stream("""{"url":"https://cdn/x","encrypted":true}""").encrypted)
    }

    @Test fun `an answer with no address but an error is a miss that says why`() {
        val s = stream("""{"error":"No playable audio for this track"}""")
        assertEquals("", s.url)
        assertEquals("No playable audio for this track", s.error)
    }
}
