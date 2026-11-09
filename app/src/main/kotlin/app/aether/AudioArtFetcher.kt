package app.aether

import android.content.Context
import android.util.Size
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toAndroidUri

/**
 * The picture of a song on the phone: a media store address names the audio, so left to itself the
 * image loader would try to read the sound as a picture. This asks the store for its own thumbnail
 * of the file's embedded art. A song with none fails here and simply shows no picture.
 */
class AudioArtFetcher(private val context: Context, private val uri: Uri, private val px: Int) : Fetcher {
    override suspend fun fetch(): FetchResult = ImageFetchResult(
        image = context.contentResolver.loadThumbnail(uri.toAndroidUri(), Size(px, px), null).asImage(),
        isSampled = true,
        dataSource = DataSource.DISK,
    )

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "content" || data.authority != "media" || data.path?.contains("/audio/media/") != true) return null
            return AudioArtFetcher(context, data, options.size.width.pxOrElse { 512 })
        }
    }
}
