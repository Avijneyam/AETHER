package app.aether.sources.youtube

import app.aether.core.model.Outcome
import app.aether.ytmusic.YouTubeMusic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Opens every "See all" on Home (and on the first artist page it finds) live and says what each holds
 * (`./gradlew :sources:youtube:browseCheck`). A "See all" that comes back empty is printed with an ✗.
 */
fun main(args: Array<String>) = runBlocking {
    val options = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val source = YouTubeSource(YouTubeMusic(language = { "en" }, region = { options["region"]?.ifBlank { null } ?: "IN" }), YouTubeStreamResolver(OkHttpClient()))
    val home = (source.home() as? Outcome.Ok)?.value ?: return@runBlocking println("✗ home didn't load")
    var sections = home.sections
    var next = home.next
    repeat(4) { next?.let { t -> (source.homeMore(t) as? Outcome.Ok)?.value?.let { sections = sections + it.sections; next = it.next } } }
    (source.explore() as? Outcome.Ok)?.value?.let { e -> sections = sections + e.sections; println("Discover: ${e.sections.map { it.title + if (it.more != null) " (see all)" else "" }}") }
    // An artist's page too, when one is named: its every "See all".
    options["artist"]?.takeIf { it.isNotBlank() }?.let { name ->
        val found = (source.search(name) as? Outcome.Ok)?.value?.items?.filterIsInstance<app.aether.core.model.Artist>()?.firstOrNull()
        val page = found?.let { (source.artist(it.id) as? Outcome.Ok)?.value }
        println("Artist ${found?.name}: ${page?.sections?.map { it.title + if (it.more != null) " (see all)" else "" }}")
        page?.let { sections = it.sections }
    }
    val filter = options["filter"]?.lowercase()
    for (s in sections) {
        val more = s.more ?: continue
        if (filter != null && filter !in s.title.lowercase()) continue
        val r = source.browse(more)
        val count = (r as? Outcome.Ok)?.value?.sections?.sumOf { it.items.size } ?: -1
        println("${if (count > 0) "✓" else "✗"} ${s.title}: ${more.key} ${more.params ?: ""} -> ${if (r is Outcome.Ok) "${r.value.sections.size} sections, $count items" else (r as Outcome.Err).error}")
    }
}
