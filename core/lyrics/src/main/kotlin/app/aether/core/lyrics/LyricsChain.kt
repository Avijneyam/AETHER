package app.aether.core.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Several libraries asked as one, BitChord's way: all of them at once, and the best answer kept. Words
 * timed word by word beat words timed by the line, which beat plain words; among answers alike, the
 * library earlier in [libraries] wins. It stops waiting as soon as no library still out could do
 * better than what's in hand, and after [budgetMs] whatever it has is the answer.
 *
 * An answer is believed only if it fits the song: words timed past its end by more than a few seconds
 * are another edit's, and are passed over.
 */
class LyricsChain(
    private val libraries: () -> List<LyricsProvider>,
    private val budgetMs: Long = 7_000L,
) : LyricsProvider {
    override val id = "libraries"
    override val attribution = LyricsAttribution("Lyrics libraries", "")

    private class Kept(val index: Int, val rank: Int, val answer: ProviderAnswer.Found)

    override suspend fun lookup(request: LyricsRequest): ProviderAnswer = coroutineScope {
        val asked = libraries()
        if (asked.isEmpty()) return@coroutineScope ProviderAnswer.NotFound
        val pending = asked.mapIndexed { i, p -> i to async { runCatching { p.lookup(request) }.getOrElse { if (it is CancellationException) throw it; ProviderAnswer.Failed } } }.toMutableList()
        val answers = arrayOfNulls<ProviderAnswer>(asked.size)
        var best: Kept? = null
        var waitedOut = false
        try {
            withTimeoutOrNull(budgetMs) {
                while (pending.isNotEmpty()) {
                    val (index, answer) = select<Pair<Int, ProviderAnswer>> {
                        pending.forEach { (i, job) -> job.onAwait { i to it } }
                    }
                    pending.removeAll { it.first == index }
                    answers[index] = answer
                    if (answer is ProviderAnswer.Found && fits(answer.lyrics, request)) {
                        val kept = Kept(index, rank(answer.lyrics), answer.copy(credit = answer.credit ?: asked[index].attribution))
                        if (best == null || better(kept, best!!)) best = kept
                    }
                    // Done once nothing still out could beat what's in hand.
                    val holding = best ?: continue
                    if (pending.none { (i, _) -> couldBeat(asked[i], i, holding) }) break
                }
            } ?: run { waitedOut = true }
        } finally {
            pending.forEach { it.second.cancel() }
        }
        best?.answer ?: if (waitedOut) ProviderAnswer.Failed else verdict(answers.toList())
    }

    private fun couldBeat(provider: LyricsProvider, index: Int, best: Kept): Boolean {
        val bestPossible = if ((provider as? LyricsLibrary)?.timesWords == false) RANK_LINES else RANK_WORDS
        return bestPossible < best.rank || (bestPossible == best.rank && index < best.index)
    }

    private fun better(a: Kept, b: Kept) = a.rank < b.rank || (a.rank == b.rank && a.index < b.index)

    private fun rank(lyrics: Lyrics): Int = when (lyrics) {
        is Lyrics.Synced -> if (lyrics.lines.any { it.words.isNotEmpty() }) RANK_WORDS else RANK_LINES
        Lyrics.Instrumental -> RANK_INSTRUMENTAL
        is Lyrics.Plain -> RANK_PLAIN
    }

    /** Words timed for a longer edit (past the song's end by more than a few seconds) aren't this song's. */
    private fun fits(lyrics: Lyrics, request: LyricsRequest): Boolean {
        val duration = request.durationMs?.takeIf { it > 20_000 } ?: return true
        val synced = lyrics as? Lyrics.Synced ?: return true
        val last = synced.lines.lastOrNull { it.text.isNotBlank() }?.startMs ?: return true
        return last <= duration + OVERRUN_MS
    }

    /**
     * Nothing found, every library having answered: "not found" when they say so (a library that's
     * broken doesn't keep that from being remembered), otherwise why not, as the screen can say it.
     */
    private fun verdict(answers: List<ProviderAnswer?>): ProviderAnswer {
        val got = answers.filterNotNull()
        return when {
            got.any { it == ProviderAnswer.NotFound } && got.none { it == ProviderAnswer.Offline } -> ProviderAnswer.NotFound
            got.isNotEmpty() && got.all { it == ProviderAnswer.Offline } -> ProviderAnswer.Offline
            got.isNotEmpty() && got.all { it is ProviderAnswer.RateLimited } -> got.minOf { (it as ProviderAnswer.RateLimited).retryInMs }.let(ProviderAnswer::RateLimited)
            else -> ProviderAnswer.Failed
        }
    }

    private companion object {
        const val RANK_WORDS = 0
        const val RANK_LINES = 1
        const val RANK_INSTRUMENTAL = 2
        const val RANK_PLAIN = 3
        const val OVERRUN_MS = 8_000L
    }
}
