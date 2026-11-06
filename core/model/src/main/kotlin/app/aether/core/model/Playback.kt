package app.aether.core.model

/**
 * The playback state machine, as screens see it. The engine derives it from the player; screens
 * only ever read it and send commands.
 *
 * ```
 * IDLE ─play─► LOADING ─ready─► PLAYING ◄─resume/pause─► PAUSED
 *                 │                │  ▲                    │
 *                 ▼                ▼  │                    ▼
 *               ERROR          BUFFERING              SEEKING ─► (back to where it was)
 *                                  │
 *                                  ▼ (end of queue)
 *                                ENDED
 * ```
 */
enum class PlaybackStatus { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, SEEKING, ENDED, ERROR }

enum class RepeatMode { OFF, ALL, ONE }

/** The format actually decoded, measured by the player (never what a source claimed). */
data class HeardFormat(
    val codec: String?,
    val sampleRateHz: Int?,
    val channels: Int?,
    /** Known only for PCM-based formats (FLAC, ALAC, WAV); null for lossy codecs. */
    val bitDepth: Int?,
    val bitrateKbps: Int?,
    val lossless: Boolean,
)

/** The queue as it was left: restored on a cold start, so the music picks up where it stopped. */
data class SavedQueue(val tracks: List<Track>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeat: RepeatMode)

/** Where the player keeps its queue between runs. */
interface QueueStore {
    suspend fun save(queue: SavedQueue)
    suspend fun load(): SavedQueue?
}

/** What the player tells the library: a track was really listened to (past its first moments). */
fun interface PlayRecorder {
    suspend fun played(track: Track)
}

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val heard: HeardFormat? = null,
    /** Why playback stopped, in words a listener can act on. */
    val error: String? = null,
    /** The USB DAC the music is going to, when there is one. */
    val outputDevice: String? = null,
    /** True only when the phone has confirmed a bit-perfect path to [outputDevice]. */
    val bitPerfect: Boolean = false,
) {
    val current: Track? get() = queue.getOrNull(index)
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
    val hasMedia: Boolean get() = current != null
    val upcoming: List<Track> get() = if (index < 0) queue else queue.drop(index + 1)
}
