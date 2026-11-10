package app.aether.sources.youtube

import app.aether.core.model.Outcome
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeStreamResolverTest {
    /** YouTube as a few canned answers: every app offers the same three formats; [dead] urls are refused. */
    private class FakeYouTube(var dead: Set<String> = emptySet()) : Interceptor {
        val asked = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            val (code, body) = when {
                "visitor_id" in url -> 200 to """{"responseContext":{"visitorData":"v"}}"""
                "/player" in url -> {
                    val app = request.header("X-Youtube-Client-Name")
                    asked += "player $app"
                    200 to player(app.orEmpty())
                }
                else -> {
                    asked += "range $url"
                    (if (dead.any { it in url }) 403 else 206) to "ab"
                }
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }

        private fun player(app: String) = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"song"},
             "streamingData":{"adaptiveFormats":[
               ${format(app, 251, "audio/webm; codecs=\\\"opus\\\"", 160_000)},
               ${format(app, 250, "audio/webm; codecs=\\\"opus\\\"", 80_000)},
               ${format(app, 140, "audio/mp4; codecs=\\\"mp4a.40.2\\\"", 130_000)}
             ]}}
        """.trimIndent()

        private fun format(app: String, itag: Int, mime: String, bitrate: Int) =
            """{"itag":$itag,"mimeType":"$mime","bitrate":$bitrate,"contentLength":"1000","audioSampleRate":"48000",
               "url":"https://rr1.googlevideo.com/videoplayback?app=$app&itag=$itag&clen=1000&expire=4102444800"}"""
    }

    private fun resolver(fake: FakeYouTube) = YouTubeStreamResolver(OkHttpClient.Builder().addInterceptor(fake).build())

    private fun Outcome<app.aether.core.model.Stream>.itag(): String = (this as Outcome.Ok).value.cacheKey!!.substringAfterLast(':')

    private fun Outcome<app.aether.core.model.Stream>.app(): String = (this as Outcome.Ok).value.url.substringAfter("app=").substringBefore('&')

    @Test
    fun `the best format of the first app that serves it plays`() = runTest {
        val r = resolver(FakeYouTube()).resolve("song")
        assertEquals("251", r.itag())
        assertEquals("101", r.app())
    }

    @Test
    fun `a stream the player couldn't play gives way to another, not the same again`() = runTest {
        val resolver = resolver(FakeYouTube())
        val first = resolver.resolve("song")
        resolver.forget("song")
        val second = resolver.resolve("song")
        assertEquals("101" to "251", first.app() to first.itag())
        // The same app's next format, rather than the address that just failed.
        assertEquals("101" to "250", second.app() to second.itag())
        resolver.forget("song")
        val third = resolver.resolve("song")
        assertEquals("101" to "140", third.app() to third.itag())
    }

    @Test
    fun `a song every format of one app fails moves to the next app`() = runTest {
        val fake = FakeYouTube(dead = setOf("app=101"))
        val r = resolver(fake).resolve("song")
        assertTrue(r is Outcome.Ok)
        assertEquals("5", r.app())
    }
}
