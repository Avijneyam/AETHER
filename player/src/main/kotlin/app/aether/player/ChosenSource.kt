package app.aether.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.Stream
import app.aether.core.model.StreamPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * How each song in the queue is played, decided when the player gets to it (it prepares a song only
 * as it nears it, so nothing is resolved ahead of need and no address waits to expire):
 *
 * - kept on the phone: its file in the listener's Music folder, or the kept audio in the app's store;
 * - otherwise its stream is asked for, as the song is: an exact copy from one of the listener's own
 *   sources when one has it (lossless, say), or the song's own. A plain file plays as before, through
 *   the cache that finds a kept song by its id; a DASH or HLS manifest (as some sources serve their
 *   Hi-Res copies) plays as one.
 *
 * Every song's audio still goes through the same renderers, so float output, the equaliser's bypass and
 * the measured labels are the same whichever way it came.
 */
@OptIn(UnstableApi::class)
internal class ChosenSources(
    private val context: Context,
    private val env: PlaybackEnvironment,
    private val plain: DefaultMediaSourceFactory,
) : MediaSource.Factory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var drm: DrmSessionManagerProvider? = null
    private var errors: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        drm = drmSessionManagerProvider
        plain.setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        errors = loadErrorHandlingPolicy
        plain.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    override fun getSupportedTypes(): IntArray = plain.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val id = mediaItem.localConfiguration?.uri?.trackId() ?: return plain.createMediaSource(mediaItem)
        return ChosenMediaSource(mediaItem, scope) { choose(id, mediaItem) }
    }

    /** Off the main thread: what this song plays from. */
    private suspend fun choose(id: MediaId, item: MediaItem): MediaSource {
        env.keptFile(id)?.let { file ->
            return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                .createMediaSource(item.buildUpon().setUri(file).setMimeType(null).build())
        }
        if (AudioStore.kept(context, id)) return plain.createMediaSource(item)
        val stream = when (val outcome = env.stream(id, StreamPurpose.LISTEN, item.toTrack())) {
            is Outcome.Ok -> outcome.value
            // The plain path asks again and says why, as it always has.
            is Outcome.Err -> return plain.createMediaSource(item)
        }
        return if (stream.manifest == Stream.Manifest.NONE) plain.createMediaSource(item) else copySource(stream, item)
    }

    /**
     * A copy's own audio, straight from its address and with its headers: a plain file, or its DASH or
     * HLS manifest. For a song carried over to a copy found mid-song, and for every manifest.
     */
    fun copySource(stream: Stream, item: MediaItem): MediaSource = when (stream.manifest) {
        Stream.Manifest.NONE -> ProgressiveMediaSource.Factory(pieces(stream)).apply { errors?.let(::setLoadErrorHandlingPolicy) }
            .createMediaSource(item.buildUpon().setUri(stream.url).setMimeType(null).build())
        Stream.Manifest.DASH -> DashMediaSource.Factory(pieces(stream)).apply { errors?.let(::setLoadErrorHandlingPolicy) }
            .createMediaSource(item.buildUpon().setUri(stream.url).setMimeType(MimeTypes.APPLICATION_MPD).build())
        Stream.Manifest.HLS -> HlsMediaSource.Factory(pieces(stream)).apply { errors?.let(::setLoadErrorHandlingPolicy) }
            .createMediaSource(item.buildUpon().setUri(stream.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
    }

    /** A manifest's pieces, fetched with the headers its source asked for. */
    private fun pieces(stream: Stream) = DefaultDataSource.Factory(context, AudioStore.http(env).setDefaultRequestProperties(stream.headers))
}

/**
 * A song whose way of playing is decided only once the player prepares it: [choose] runs then, off
 * the playback thread, and its answer becomes this source's one child. Until then the player holds the
 * song's place in the queue (its own placeholder covers it); a failure is reported where the player
 * looks for one.
 */
@OptIn(UnstableApi::class)
private class ChosenMediaSource(
    private val item: MediaItem,
    private val scope: CoroutineScope,
    private val choose: suspend () -> MediaSource,
) : CompositeMediaSource<Unit>() {
    private var child: MediaSource? = null
    private var failure: IOException? = null
    private var job: Job? = null
    private var handler: Handler? = null

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val here = Util.createHandlerForCurrentLooper()
        handler = here
        job = scope.launch {
            val chosen = runCatching { choose() }
            here.post {
                if (handler !== here) return@post
                chosen.onSuccess { source ->
                    child = source
                    prepareChildSource(Unit, source)
                }.onFailure { failure = it as? IOException ?: IOException(it.message ?: "This song couldn't be loaded") }
            }
        }
    }

    override fun onChildSourceInfoRefreshed(childSourceId: Unit?, mediaSource: MediaSource, newTimeline: Timeline) = refreshSourceInfo(newTimeline)

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child) { "asked for a period before the song was chosen" }.createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        child?.releasePeriod(mediaPeriod)
    }

    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun releaseSourceInternal() {
        job?.cancel()
        job = null
        handler = null
        child = null
        failure = null
        super.releaseSourceInternal()
    }
}
