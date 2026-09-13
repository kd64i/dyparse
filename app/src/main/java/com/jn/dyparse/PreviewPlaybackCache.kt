package com.jn.dyparse

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PreviewPlaybackCache {
    private const val CACHE_DIRECTORY_NAME = "preview_playback"
    private const val MAX_CACHE_BYTES = 96L * 1024L * 1024L

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Volatile
    private var mediaCache: SimpleCache? = null

    fun createPlayer(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(getMediaCache(appContext))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(dataSourceFactory)
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    fun getCacheDirectory(context: Context): File {
        return File(context.applicationContext.filesDir, CACHE_DIRECTORY_NAME)
    }

    @Synchronized
    fun clear(context: Context) {
        mediaCache?.release()
        mediaCache = null
        getCacheDirectory(context).deleteRecursively()
    }

    @Synchronized
    private fun getMediaCache(context: Context): SimpleCache {
        mediaCache?.let { return it }
        val cacheDirectory = getCacheDirectory(context).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        return SimpleCache(
            cacheDirectory,
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            getDatabaseProvider(context)
        ).also { mediaCache = it }
    }

    @Synchronized
    private fun getDatabaseProvider(context: Context): StandaloneDatabaseProvider {
        databaseProvider?.let { return it }
        return StandaloneDatabaseProvider(context.applicationContext).also {
            databaseProvider = it
        }
    }
}
