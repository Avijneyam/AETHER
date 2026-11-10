package app.aether.sources.youtube

import app.aether.core.model.Outcome
import app.aether.ytmusic.YouTubeMusic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.system.exitProcess

/**
 * Resolves songs' streams live, as the player will, and says what each stream is
 * (`./gradlew :sources:youtube:streamCheck`): the first quick picks on Home, or the video ids given
 * (`-Pids=dQw4w9WgXcQ,...`), in the region given (`-Pregion=IN`). With `-Pdiagnose=true` it also asks
 * each of YouTube's apps on its own and says what each answers. A song that doesn't resolve fails the run.
 */
fun main(args: Array<String>) = runBlocking {
    val options = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val region = options["region"]?.ifBlank { null } ?: "US"
    val resolver = YouTubeStreamResolver(OkHttpClient(), region = { region })
    val source = YouTubeSource(YouTubeMusic(language = { "en" }), resolver)
    val ids = options["ids"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val songs: List<Pair<String, String>> = if (ids.isNotEmpty()) {
        ids.map { it to it }
    } else {
        val home = (source.home() as? Outcome.Ok)?.value ?: run { println("✗ home didn't load"); exitProcess(1) }
        home.sections.flatMap { s -> s.items.filterIsInstance<app.aether.core.model.Track>() }
            .take(options["count"]?.toIntOrNull() ?: 4)
            .map { it.id.key to it.title }
    }
    println("Region $region, ${songs.size} songs")
    var failed = 0
    for ((id, title) in songs) {
        val started = System.currentTimeMillis()
        when (val r = resolver.resolve(id)) {
            is Outcome.Ok -> println("✓ $title ($id): ${r.value.mimeType} ${r.value.codec} ${r.value.bitrateKbps} kbps, ${r.value.sampleRateHz} Hz, in ${System.currentTimeMillis() - started} ms")
            is Outcome.Err -> { failed++; println("✗ $title ($id): ${r.error}") }
        }
        if (options["diagnose"] == "true") resolver.diagnose(id).forEach { (app, answer) -> println("    $app: $answer") }
        if (options["diagnose"] == "formats") resolver.diagnoseFormats(id).forEach { println("    $it") }
    }
    if (failed > 0) exitProcess(1)
}
