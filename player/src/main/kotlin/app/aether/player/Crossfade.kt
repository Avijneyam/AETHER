// Ported from PODIUM (player/api AudioEffects.kt, CrossfadeRules, D-77; GPL-3.0).
package app.aether.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * When to crossfade, apart from the player so it can be tested. Never across songs that run into
 * each other on an album (gapless), never into the same song again, and never on songs too short
 * for the overlap.
 */
object CrossfadeRules {
    /** The lengths offered, in seconds; 0 is off. */
    val Choices = listOf(0, 2, 4, 6, 8, 12)

    /** A song in the queue, as the rules need it: which album it's from, its place there, its length. */
    data class Song(val album: String?, val trackNumber: Int?, val durationMs: Long?)

    fun applies(seconds: Int, current: Song, next: Song?, repeatOne: Boolean): Boolean {
        if (seconds <= 0 || next == null || repeatOne) return false
        val overlap = seconds * 1_000L
        // Each song must be long enough to give up the overlap and still be itself.
        if ((current.durationMs ?: 0) < overlap * 3 || (next.durationMs ?: 0) < overlap * 3) return false
        return !gapless(current, next)
    }

    /** The next track of the same album: they're mastered to run into each other. */
    fun gapless(current: Song, next: Song): Boolean {
        val album = current.album ?: return false
        if (album != next.album) return false
        val a = current.trackNumber ?: return false
        val b = next.trackNumber ?: return false
        return b == a + 1
    }

    /** Equal-power curves: the outgoing song's volume [p] of the way through the fade… */
    fun fadeOut(p: Float): Float = cos(p.coerceIn(0f, 1f) * (PI.toFloat() / 2))

    /** …and the incoming song's. Together they keep the loudness steady. */
    fun fadeIn(p: Float): Float = sin(p.coerceIn(0f, 1f) * (PI.toFloat() / 2))

    /** Two players are close enough to swap the same song between them unheard. */
    const val IN_STEP_MS = 25L

    /**
     * The speed for the silent second player while it catches up with the first, playing the same
     * song: [driftMs] ahead (positive) or behind. Silent, so the change in pitch is never heard.
     */
    fun catchUpSpeed(driftMs: Long): Float = (1f - driftMs / 1_000f).coerceIn(0.85f, 1.15f)
}

