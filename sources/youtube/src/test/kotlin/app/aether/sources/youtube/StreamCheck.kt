package app.aether.sources.youtube

import app.aether.core.model.Outcome
import app.aether.ytmusic.YouTubeMusic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.system.exitProcess

/**
 * Resolves the first quick picks on Home live, as the player will, and says what each stream is
 * (`./gradlew :sources:youtube:streamCheck`). A song that doesn't resolve fails the run.
 */
fun main() = runBlocking {
    val source = YouTubeSource(YouTubeMusic(language = { "en" }), YouTubeStreamResolver(OkHttpClient()))
    val home = (source.home() as? Outcome.Ok)?.value ?: run { println("✗ home didn't load"); exitProcess(1) }
    val tracks = home.sections.flatMap { s -> s.items.filterIsInstance<app.aether.core.model.Track>() }.take(4)
    var failed = 0
    for (track in tracks) {
        val started = System.currentTimeMillis()
        when (val r = source.stream(track.id)) {
            is Outcome.Ok -> println("✓ ${track.title}: ${r.value.mimeType} ${r.value.codec} ${r.value.bitrateKbps} kbps, ${r.value.sampleRateHz} Hz, in ${System.currentTimeMillis() - started} ms")
            is Outcome.Err -> { failed++; println("✗ ${track.title}: ${r.error}") }
        }
    }
    if (failed > 0) exitProcess(1)
}
