package app.aether.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Where artists' films are kept once they've been fetched, so a page opened again starts its film
 * at once. A cache of its own, oldest gone first, apart from the songs kept for listening offline.
 */
@OptIn(UnstableApi::class)
object FilmCache {
    private const val MaxBytes = 400L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    fun of(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "films"),
            LeastRecentlyUsedCacheEvictor(MaxBytes),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }
}
