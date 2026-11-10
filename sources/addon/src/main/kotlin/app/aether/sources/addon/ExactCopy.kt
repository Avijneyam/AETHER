// After PODIUM's TrackMatcher (sources/api matching, GPL-3.0), its EXACT tier, for AETHER's addons.
package app.aether.sources.addon

import app.aether.core.lyrics.matching.ArtistNames
import app.aether.core.lyrics.matching.Explicitness
import app.aether.core.lyrics.matching.TrackNormalizer
import app.aether.core.lyrics.matching.VariantNote
import app.aether.core.model.Track
import kotlin.math.abs

/**
 * Whether a song on an addon is an exact copy of a song from YouTube Music (architecture.md §4): the
 * same recording, so that it can play in its place. PODIUM's EXACT tier, which never crosses live and
 * studio, remix and original, explicit and clean, or one artist and another:
 *
 * - The titles name the same song (its identity once packaging like "Remastered" or "Official Audio"
 *   is set aside), and their version markers agree both ways: "Song (Live)" is never "Song".
 * - Explicit and clean editions are different releases: where both sides say, they must agree.
 * - The artists share a name; featured artists named on the copy's title must be the song's.
 * - Both lengths are known and within [DURATION_TOLERANCE_MS]: length is evidence, not proof, and
 *   no copy is taken on length alone.
 * - A remaster on one side only is taken only from the same album.
 * - An immersive mix (Atmos) is never the stereo song.
 *
 * Several copies: the same album first, then the best the addon says it has, then the closest length.
 */
internal object ExactCopy {
    const val DURATION_TOLERANCE_MS = 2_500L

    /** What to search the addon for: the song's name with its version, and its first artist; then the name alone. */
    fun queries(track: Track): List<String> {
        val analysis = TrackNormalizer.analyze(track.title, track.artistLine)
        val versionWords = analysis.version.versionDetail.replace(" | ", " ")
        val title = listOf(analysis.baseTitle, versionWords).filter { it.isNotBlank() }.joinToString(" ")
        val primary = track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: track.artistLine
        return listOfNotNull(
            "$title $primary".trim().takeIf { primary.isNotBlank() && title.isNotBlank() },
            title.takeIf { it.isNotBlank() },
        ).distinct()
    }

    fun matches(track: Track, candidate: AddonTrack): Boolean {
        if (candidate.immersive) return false
        val want = TrackNormalizer.analyze(track.title, track.artistLine)
        val have = TrackNormalizer.analyze(candidate.title, candidate.artist.orEmpty())

        // Explicit and clean: where the copy says which it is, it's the song's edition. (YouTube Music
        // marks every explicit song, so a song it doesn't mark and a copy marked explicit are two editions.)
        if (candidate.explicit != null && candidate.explicit != track.explicit) return false
        val wantHint = want.explicitnessHint ?: Explicitness.UNKNOWN
        val haveHint = have.explicitnessHint ?: Explicitness.UNKNOWN
        if (wantHint.conflictsWith(haveHint)) return false

        // The same song, the same version of it.
        val a = want.version
        val b = have.version
        if (a.identityKey.isEmpty() || a.identityKey != b.identityKey) return false
        if (a.tags != b.tags) return false
        if (a.tags.isNotEmpty() && a.versionDetail != b.versionDetail) return false

        // The same people.
        val wantNames = names(track.artists.map { it.name } + track.artistLine, want.featuredArtists)
        val haveNames = names(listOfNotNull(candidate.artist), have.featuredArtists)
        if (wantNames.isEmpty() || haveNames.isEmpty() || (wantNames intersect haveNames).isEmpty()) return false
        val featuredHere = have.featuredArtists.flatMap { ArtistNames.comparableForms(it) }.toSet()
        if (featuredHere.isNotEmpty() && (featuredHere intersect wantNames).isEmpty()) return false
        val featuredThere = want.featuredArtists.flatMap { ArtistNames.comparableForms(it) }.toSet()
        if (featuredThere.isNotEmpty() && (featuredThere intersect haveNames).isEmpty()) return false

        // The same length.
        val da = track.durationMs ?: return false
        val db = candidate.durationMs ?: return false
        if (abs(da - db) > DURATION_TOLERANCE_MS) return false

        // A remaster said on one side only: the same master only if it's the same album.
        val remasterA = VariantNote.REMASTER in a.variants
        val remasterB = VariantNote.REMASTER in b.variants
        if (remasterA != remasterB && !sameAlbum(track, candidate)) return false
        return true
    }

    /** The copy to play: an exact one, the same album first, then the best the addon has, then the closest length. */
    fun best(track: Track, candidates: List<AddonTrack>): AddonTrack? = candidates
        .filter { matches(track, it) }
        .sortedWith(
            compareBy<AddonTrack> { if (sameAlbum(track, it)) 0 else 1 }
                .thenByDescending { rank(it.quality) }
                .thenBy { abs((it.durationMs ?: 0L) - (track.durationMs ?: 0L)) },
        )
        .firstOrNull()

    private fun names(credits: List<String>, featured: List<String>): Set<String> =
        (credits + featured).filter { it.isNotBlank() }.flatMap { ArtistNames.comparableForms(it) }.toSet()

    private fun sameAlbum(track: Track, candidate: AddonTrack): Boolean {
        val a = track.album?.title?.let { TrackNormalizer.analyze(it).version.identityKey }?.takeIf { it.isNotEmpty() } ?: return false
        val b = candidate.album?.let { TrackNormalizer.analyze(it).version.identityKey }?.takeIf { it.isNotEmpty() } ?: return false
        return a == b
    }

    /** How good a row says it is, only to choose between exact copies; the decoder has the last word. */
    private fun rank(quality: String?): Int {
        val q = quality?.lowercase() ?: return 0
        return when {
            "hi_res" in q || "hi-res" in q || "hires" in q || "24" in q && "bit" in q -> 3
            "lossless" in q || "flac" in q || "alac" in q || "wav" in q -> 2
            "high" in q || "320" in q -> 1
            else -> 0
        }
    }
}
