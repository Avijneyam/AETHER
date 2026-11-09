package app.aether.ui.profile

import app.aether.core.data.Played
import app.aether.core.model.Artwork
import app.aether.core.model.MediaId
import app.aether.core.model.MediaKind
import app.aether.core.model.Track
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An artist as the listener has played them. */
class ArtistStat(val name: String, val id: MediaId?, val plays: Int, val minutes: Int, val topSong: Track, val topSongPlays: Int, val art: Artwork)

class SongStat(val track: Track, val plays: Int)

class TypeShare(val label: String, val count: Int, val share: Float)

/** A question a profile answers about itself, and the song that answers it. */
class Prompt(val question: String, val track: Track, val detail: String)

/**
 * Everything the Profile says, worked out on the phone from what's been played here. One song is
 * enough for it to say something. A play is counted past a song's first half-minute, and the time is
 * those songs' lengths, so it's how much music was played rather than a stopwatch.
 */
class ProfileStats(
    val plays: Int,
    val songCount: Int,
    val artistCount: Int,
    val minutes: Long,
    val artists: List<ArtistStat>,
    val songs: List<SongStat>,
    /** The share of plays that were a song coming back. */
    val repeatShare: Float,
    val comebacks: Int,
    val topThreeShare: Float,
    val afterDarkShare: Float,
    val afterDarkPlays: Int,
    /** The share of this month's plays that were a song heard for the first time. */
    val freshShare: Float,
    val freshSongs: Int,
    /** Plays and minutes on each of the last thirty days, oldest first. */
    val playsByDay: List<Int>,
    val minutesByDay: List<Int>,
    val hours: List<Int>,
    val weekdays: List<Int>,
    val streak: Int,
    val bestStreak: Int,
    val activeDays: Int,
    val since: LocalDate?,
    val types: List<TypeShare>,
    val collabShare: Float,
    val explicitShare: Float,
    val videoShare: Float,
    val averageLengthMs: Long,
    val prompts: List<Prompt>,
    val persona: String,
    val secondPersona: String?,
) {
    val isEmpty: Boolean get() = plays == 0
    val peakHour: Int? get() = hours.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
    val peakDay: Int? get() = weekdays.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
    val sinceLabel: String? get() = since?.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
}

private val Film = Regex("""\bfrom\s+["“'‘(]|\bO\.?S\.?T\.?\b|soundtrack""", RegexOption.IGNORE_CASE)
private val Remix = Regex("""\bremix(ed)?\b|\bmashup\b|\bedit\b""", RegexOption.IGNORE_CASE)
private val Live = Regex("""[(\[\-–]\s*live\b|\blive at\b|\blive from\b""", RegexOption.IGNORE_CASE)
private val Acoustic = Regex("""\bacoustic\b|\bunplugged\b""", RegexOption.IGNORE_CASE)
private val Slowed = Regex("""\bslowed\b|\breverb\b|\blo-?fi\b|\bsped up\b""", RegexOption.IGNORE_CASE)
private val Cover = Regex("""\bcover\b""", RegexOption.IGNORE_CASE)
private val Instrumental = Regex("""\binstrumental\b|\bkaraoke\b""", RegexOption.IGNORE_CASE)

/** What a song is, as far as its title says. */
private fun typeOf(title: String): String = when {
    Film.containsMatchIn(title) -> "Film & soundtrack"
    Remix.containsMatchIn(title) -> "Remixes"
    Slowed.containsMatchIn(title) -> "Slowed & lo-fi"
    Live.containsMatchIn(title) -> "Live"
    Acoustic.containsMatchIn(title) -> "Acoustic"
    Cover.containsMatchIn(title) -> "Covers"
    Instrumental.containsMatchIn(title) -> "Instrumental"
    else -> "Studio originals"
}

private class Tally(val name: String, var id: MediaId?) {
    var plays = 0
    var ms = 0L
    val songs = LinkedHashMap<MediaId, Int>()
    val tracks = HashMap<MediaId, Track>()
}

fun profileStats(history: List<Played>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ProfileStats {
    val plays = history.sortedBy { it.at }
    val today = now.atZone(zone).toLocalDate()
    val total = plays.size
    fun share(n: Int, of: Int = total) = if (of == 0) 0f else n.toFloat() / of

    val byTrack = plays.groupBy { it.track.id }
    val songs = byTrack.values.map { SongStat(it.last().track, it.size) }.sortedWith(compareByDescending<SongStat> { it.plays }.thenByDescending { byTrack.getValue(it.track.id).last().at })

    val tallies = LinkedHashMap<String, Tally>()
    val days = HashMap<LocalDate, Int>()
    val daysMinutes = HashMap<LocalDate, Int>()
    val hours = IntArray(24)
    val weekdays = IntArray(7)
    var ms = 0L
    var afterDark = 0
    var collabs = 0
    var explicit = 0
    var videos = 0
    var lengthed = 0
    val types = HashMap<String, Int>()
    plays.forEach { p ->
        val t = p.track
        val length = t.durationMs ?: 0L
        ms += length
        if (length > 0) lengthed++
        val at = Instant.ofEpochMilli(p.at).atZone(zone)
        val day = at.toLocalDate()
        days[day] = (days[day] ?: 0) + 1
        daysMinutes[day] = (daysMinutes[day] ?: 0) + (length / 60_000).toInt()
        hours[at.hour]++
        weekdays[at.dayOfWeek.value - 1]++
        if (at.hour >= 21 || at.hour < 5) afterDark++
        if (t.artists.size > 1) collabs++
        if (t.explicit) explicit++
        if (t.kind == MediaKind.MUSIC_VIDEO || t.kind == MediaKind.VIDEO) videos++
        val type = typeOf(t.title)
        types[type] = (types[type] ?: 0) + 1
        t.artists.distinctBy { it.name.lowercase() }.forEach { a ->
            val tally = tallies.getOrPut(a.name.lowercase()) { Tally(a.name, a.id) }
            if (tally.id == null) tally.id = a.id
            tally.plays++
            tally.ms += length
            tally.songs[t.id] = (tally.songs[t.id] ?: 0) + 1
            tally.tracks[t.id] = t
        }
    }
    val artists = tallies.values.sortedWith(compareByDescending<Tally> { it.plays }.thenByDescending { it.ms }).map { a ->
        val (id, count) = a.songs.entries.maxBy { it.value }
        val top = a.tracks.getValue(id)
        ArtistStat(a.name, a.id, a.plays, (a.ms / 60_000).toInt(), top, count, top.artwork)
    }

    val topThree = artists.take(3).map { it.name.lowercase() }.toSet()
    val byTopThree = plays.count { p -> p.track.artists.any { it.name.lowercase() in topThree } }

    // Heard for the first time, among this last month's plays.
    val monthAgo = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
    val firsts = byTrack.values.map { it.first().at }
    val freshSongs = firsts.count { it >= monthAgo }
    val monthPlays = plays.count { it.at >= monthAgo }

    val playsByDay = (29 downTo 0).map { days[today.minusDays(it.toLong())] ?: 0 }
    val minutesByDay = (29 downTo 0).map { daysMinutes[today.minusDays(it.toLong())] ?: 0 }

    // The current run may end yesterday: today isn't over.
    var streak = 0
    var cursor = if (today in days) today else today.minusDays(1)
    while (cursor in days) {
        streak++
        cursor = cursor.minusDays(1)
    }
    var best = 0
    var run = 0
    var last: LocalDate? = null
    days.keys.sorted().forEach { d ->
        run = if (last?.plusDays(1) == d) run + 1 else 1
        best = maxOf(best, run)
        last = d
    }

    val repeatShare = share(total - songs.size)
    val topThreeShare = share(byTopThree)
    val afterDarkShare = share(afterDark)
    val morning = plays.count { Instant.ofEpochMilli(it.at).atZone(zone).hour in 5..10 }

    val personas = buildList {
        if (afterDarkShare >= 0.4f) add("Night owl")
        if (share(morning) >= 0.4f) add("Early riser")
        if (topThreeShare >= 0.6f && artists.size > 3) add("The loyalist")
        if (repeatShare >= 0.5f) add("On repeat")
        if (total >= 5 && share(artists.size) >= 0.6f) add("The explorer")
        if (best >= 7) add("Every single day")
        add("The listener")
    }

    return ProfileStats(
        plays = total,
        songCount = songs.size,
        artistCount = artists.size,
        minutes = ms / 60_000,
        artists = artists,
        songs = songs,
        repeatShare = repeatShare,
        comebacks = songs.count { it.plays > 1 },
        topThreeShare = topThreeShare,
        afterDarkShare = afterDarkShare,
        afterDarkPlays = afterDark,
        freshShare = share(firsts.count { it >= monthAgo }, monthPlays),
        freshSongs = freshSongs,
        playsByDay = playsByDay,
        minutesByDay = minutesByDay,
        hours = hours.toList(),
        weekdays = weekdays.toList(),
        streak = streak,
        bestStreak = best,
        activeDays = days.size,
        since = plays.firstOrNull()?.let { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() },
        types = types.entries.sortedByDescending { it.value }.map { TypeShare(it.key, it.value, share(it.value)) },
        collabShare = share(collabs),
        explicitShare = share(explicit),
        videoShare = share(videos),
        averageLengthMs = if (lengthed == 0) 0 else ms / lengthed,
        prompts = prompts(plays, songs, now, zone),
        persona = personas.first(),
        secondPersona = personas.getOrNull(1)?.takeIf { it != "The listener" },
    )
}

private fun times(n: Int) = if (n == 1) "Once" else "$n plays"

private fun prompts(plays: List<Played>, songs: List<SongStat>, now: Instant, zone: ZoneId): List<Prompt> = buildList {
    fun mostOf(of: List<Played>): SongStat? =
        of.groupBy { it.track.id }.values.map { SongStat(it.last().track, it.size) }.maxByOrNull { it.plays }

    val week = now.minusSeconds(7L * 24 * 3600).toEpochMilli()
    mostOf(plays.filter { it.at >= week })?.let { add(Prompt("Can't stop playing this week", it.track, times(it.plays))) }
    mostOf(plays.filter { Instant.ofEpochMilli(it.at).atZone(zone).hour in 0..4 })?.let { add(Prompt("My 2 a.m. song", it.track, times(it.plays))) }
    songs.firstOrNull()?.let { add(Prompt("The one I always come back to", it.track, times(it.plays))) }
    plays.firstOrNull()?.let { p ->
        val day = Instant.ofEpochMilli(p.at).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
        add(Prompt("Where it all started", p.track, day))
    }
}.distinctBy { it.track.id }
