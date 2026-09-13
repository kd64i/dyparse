package com.jn.dyparse

import com.jn.dyparse.data.GalleryMedia

object DouyinGalleryMediaResolver {
    fun collectGalleryMedia(item: Map<String, Any>): List<GalleryMedia> {
        val sourceLists = collectGallerySourceLists(item)
        val maxSize = sourceLists.maxOfOrNull { it.size } ?: 0
        return (0 until maxSize).mapNotNull { index ->
            val mediaItems = sourceLists.mapNotNull { it.getOrNull(index) }
            val imageUrl = pickBestImageUrl(mediaItems)
            val livePhotoUrl = pickBestLivePhotoUrl(mediaItems)
            if (imageUrl.isNullOrBlank() && livePhotoUrl.isNullOrBlank()) {
                null
            } else {
                GalleryMedia(
                    index = index,
                    imageUrl = imageUrl,
                    livePhotoRawUrl = livePhotoUrl
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectGallerySourceLists(item: Map<String, Any>): List<List<Map<String, Any>>> {
        val imagePost = item["image_post_info"] as? Map<*, *>
        val candidates = listOfNotNull(
            imagePost?.get("images"),
            imagePost?.get("image_list"),
            item["image_list"],
            item["images"],
            item["image_infos"],
            item["original_images"]
        )
        return candidates.mapNotNull { candidate ->
            (candidate as? List<*>)
                ?.mapNotNull { it as? Map<String, Any> }
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun pickBestImageUrl(items: List<Map<String, Any>>): String? {
        return pickFirstUrl(*items.flatMap(::cleanImageSources).toTypedArray())
            ?: pickFirstUrl(*items.flatMap(::downloadImageSources).toTypedArray())
            ?: pickFirstUrl(*items.flatMap(::watermarkImageSources).toTypedArray())
    }

    private fun cleanImageSources(item: Map<String, Any>): List<Any?> {
        return pickFirstUrl(
            item["url_list"],
            item["image_url"],
            item["display_image"],
            item["image"],
            item["cover"],
            item["origin_cover"],
            item["label_large"],
            item["thumbnail"]
        )?.let { listOf(it) } ?: listOf(
            item["url_list"],
            item["image_url"],
            item["display_image"],
            item["image"],
            item["cover"],
            item["origin_cover"],
            item["label_large"],
            item["thumbnail"]
        )
    }

    private fun downloadImageSources(item: Map<String, Any>): List<Any?> {
        return listOf(
            item["download_url"],
            item["download_addr"],
            item["download_url_list"]
        )
    }

    private fun watermarkImageSources(item: Map<String, Any>): List<Any?> {
        return listOf(
            item["owner_watermark_image"]
        )
    }

    private fun pickBestLivePhotoUrl(items: List<Map<String, Any>>): String? {
        return items.firstNotNullOfOrNull(::pickFirstLivePhotoUrl)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pickFirstLivePhotoUrl(item: Map<String, Any>): String? {
        val video = item["video"] as? Map<String, Any>
        return buildPlayUrlFromUri(video) ?: pickFirstUrl(
            video?.get("play_addr_h264"),
            video?.get("play_addr"),
            video?.dig<List<*>>("bit_rate")?.firstOrNull(),
            video?.get("play_addr_lowbr"),
            video?.get("download_addr"),
            item["video_play_addr"],
            item["video_download_addr"]
        )
    }

    private fun buildPlayUrlFromUri(video: Map<String, Any>?): String? {
        val videoId = video.dig<String>("play_addr", "uri")
            ?: video.dig<String>("download_addr", "uri")
            ?: video?.get("vid") as? String
        val normalizedVideoId = videoId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it.startsWith("http", ignoreCase = true) }
            ?.takeUnless { it.contains("mp3", ignoreCase = true) }
            ?: return null
        return DouyinVideoDownloadResolver.buildOriginalPlayEndpointRequest(
            videoId = normalizedVideoId,
            cookieHeader = null
        )?.url
    }

    private fun pickFirstUrl(vararg sources: Any?): String? {
        sources.forEach { source ->
            when (source) {
                is String -> {
                    val value = source.trim()
                    if (value.isNotBlank()) {
                        return value
                    }
                }

                is List<*> -> {
                    source.forEach { value ->
                        val url = pickFirstUrl(value)
                        if (!url.isNullOrBlank()) {
                            return url
                        }
                    }
                }

                is Map<*, *> -> {
                    val url = pickFirstUrl(
                        source["url_list"],
                        source["download_url_list"],
                        source["download_url"],
                        source["download_addr"],
                        source["image_url"],
                        source["display_image"],
                        source["owner_watermark_image"],
                        source["image"],
                        source["play_addr"],
                        source["video_play_addr"],
                        source["video_download_addr"]
                    )
                    if (!url.isNullOrBlank()) {
                        return url
                    }
                }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any?.dig(vararg path: Any): T? {
        var current: Any? = this
        for (key in path) {
            current = when {
                current is Map<*, *> && key is String -> current[key]
                current is List<*> && key is Int -> current.getOrNull(key)
                else -> return null
            }
        }
        return current as? T
    }
}
