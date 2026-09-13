package com.jn.dyparse

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 应用入口：配置 Coil 图片加载器（内存 + 磁盘缓存）。
 */
class DyparseApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 服务器配置要先于任何网络调用初始化（解析全程依赖它）
        ServerConfigStore.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(200L * 1024 * 1024) // 200MB
                    .build()
            }
            .crossfade(false)
            .build()
    }
}