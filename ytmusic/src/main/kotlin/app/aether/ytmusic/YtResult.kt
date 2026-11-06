package app.aether.ytmusic

/** What a catalogue call answers: a value, or why there is none. Nothing in this module throws for the service's sake. */
sealed interface YtResult<out T> {
    data class Success<T>(val value: T) : YtResult<T>
    data class Failure(val error: YtError) : YtResult<Nothing>
}

/** Why a call has no value, in terms a screen can explain and act on. */
sealed interface YtError {
    /** No connection at all. */
    data object Offline : YtError

    /** The connection failed part way, or timed out. */
    data object Network : YtError

    /** The service answered with an error, or with something that couldn't be read ([code] null). */
    data class Server(val code: Int?) : YtError

    /** Too many requests: wait [retryAfterMs] when the service says how long. */
    data class RateLimited(val retryAfterMs: Long?) : YtError

    /** This needs a signed-in account. */
    data object AuthRequired : YtError

    /** The account's session has ended; sign in again. */
    data object AuthExpired : YtError

    /** The page, album, artist or playlist doesn't exist (or no longer does). */
    data class NotFound(val what: String) : YtError
}

inline fun <T, R> YtResult<T>.map(transform: (T) -> R): YtResult<R> = when (this) {
    is YtResult.Success -> YtResult.Success(transform(value))
    is YtResult.Failure -> this
}

inline fun <T, R> YtResult<T>.flatMap(transform: (T) -> YtResult<R>): YtResult<R> = when (this) {
    is YtResult.Success -> transform(value)
    is YtResult.Failure -> this
}

fun <T> YtResult<T>.getOrNull(): T? = (this as? YtResult.Success)?.value

/** A page that parsed to nothing usable is the service's failure, not an empty screen. */
internal fun <T : Any> T?.orUnreadable(): YtResult<T> = if (this != null) YtResult.Success(this) else YtResult.Failure(YtError.Server(null))
