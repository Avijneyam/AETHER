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
    // Songs that arrive with no artist, wherever they come from.
    fun nameless(where: String, items: List<app.aether.core.model.MediaEntity>) {
        val bare = items.filterIsInstance<app.aether.core.model.Track>().filter { it.artists.isEmpty() }
        if (bare.isNotEmpty()) println("✗ no artist in $where: ${bare.take(4).map { it.title + " [" + it.kind + "]" }} (${bare.size} of ${items.size})")
    }
    sections.forEach { nameless("section " + it.title, it.items) }
    (source.search("snooze sza") as? Outcome.Ok)?.value?.let { nameless("search", it.items) }
    for (q in listOf("snooze", "snooze sza", "sza", "blinding lights", "arijit")) (source.suggestions(q) as? Outcome.Ok)?.value?.let { sg ->
        println("suggestions for $q: ${sg.items.map { e -> if (e is app.aether.core.model.Track) e.title + " by " + e.artists.map { it.name } + " [" + e.kind + "]" else e::class.simpleName + " " + e.toString().take(40) }}")
    }
    val firstTrack = sections.flatMap { it.items }.filterIsInstance<app.aether.core.model.Track>().firstOrNull()
    firstTrack?.let { t -> (source.upNext(t) as? Outcome.Ok)?.value?.let { nameless("up next of " + t.title, it.tracks) } }
    // An album's own track list, when one is named.
    options["album"]?.takeIf { it.isNotBlank() }?.let { name ->
        val found = (source.search(name) as? Outcome.Ok)?.value?.items?.filterIsInstance<app.aether.core.model.Album>()?.firstOrNull()
        val page = found?.let { (source.album(it.id) as? Outcome.Ok)?.value }
        println("Album ${found?.title}: ${page?.tracks?.size} tracks, first: ${page?.tracks?.firstOrNull()?.let { it.title + " by " + it.artists.map { a -> a.name } }}")
        page?.let { nameless("album " + found.title, it.tracks) }
    }
    // The radio's answer for the first suggested song of a search, its first few entries as they come.
    options["radio"]?.takeIf { it.isNotBlank() }?.let { q ->
        val t = (source.search(q) as? Outcome.Ok)?.value?.items?.filterIsInstance<app.aether.core.model.Track>()?.firstOrNull()
        println("Radio of ${t?.title} by ${t?.artists?.map { it.name }} (${t?.id})")
        t?.let { (source.upNext(it) as? Outcome.Ok)?.value?.tracks?.take(4)?.forEach { r -> println("  ${r.id} ${r.title} by ${r.artists.map { a -> a.name }} [${r.kind}]") } }
        t?.let { (source.upNext(it.copy(context = null)) as? Outcome.Ok)?.value?.tracks?.take(2)?.forEach { r -> println("  (no context) ${r.id} ${r.title} by ${r.artists.map { a -> a.name }}") } }
    }
    val filter = options["filter"]?.lowercase()
    for (s in sections) {
        val more = s.more ?: continue
        if (filter != null && filter !in s.title.lowercase()) continue
        val r = source.browse(more)
        val count = (r as? Outcome.Ok)?.value?.sections?.sumOf { it.items.size } ?: -1
        (r as? Outcome.Ok)?.value?.sections?.forEach { nameless("see all " + s.title, it.items) }
        println("${if (count > 0) "✓" else "✗"} ${s.title}: ${more.key} ${more.params ?: ""} -> ${if (r is Outcome.Ok) "${r.value.sections.size} sections, $count items" else (r as Outcome.Err).error}")
    }
}
