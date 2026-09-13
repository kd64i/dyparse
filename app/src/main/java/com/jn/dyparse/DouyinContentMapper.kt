package com.jn.dyparse

import com.jn.dyparse.data.GalleryMedia

/** Shared, defensive mapping for the different Douyin response shapes. */
internal object DouyinContentMapper {
    fun extractCoverUrl(item: Map<String, Any>, galleryMedia: List<GalleryMedia>): String? {
        return item.dig<String>("video", "cover", "url_list", 0)
            ?: item.dig<String>("video", "origin_cover", "url_list", 0)
            ?: galleryMedia.firstNotNullOfOrNull { it.imageUrl }
            ?: item.dig<String>("author", "avatar_thumb", "url_list", 0)
    }

    fun extractGalleryMedia(item: Map<String, Any>): List<GalleryMedia> {
        val resolved = DouyinGalleryMediaResolver.collectGalleryMedia(item)
        if (resolved.isNotEmpty()) {
            return resolved
        }

        return item.dig<List<*>>("image_infos")
            ?.mapIndexedNotNull { index, imageItem ->
                (imageItem as? Map<*, *>)?.let { info ->
                    val imageUrl = info.dig<String>("label_large", "url_list", 0)
                        ?: info.dig<String>("thumbnail", "url_list", 0)
                        ?: info.dig<String>("url_list", 0)
                    imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        GalleryMedia(index = index, imageUrl = url)
                    }
                }
            }
            .orEmpty()
    }

    fun extractRawPlayUrl(item: Map<String, Any>): String? {
        val videoUri = item.dig<String>("video", "play_addr", "uri")
            ?: item.dig<String>("video", "bit_rate", 0, "play_addr", "uri")
        if (!videoUri.isNullOrBlank()) {
            return when {
                videoUri.startsWith("http", ignoreCase = true) -> videoUri
                videoUri.contains("mp3") -> videoUri
                else -> String.format(NativeLib.getApiUrlTemplate(2), videoUri)
            }
        }

        return item.dig<String>("video", "play_addr", "url_list", 0)
            ?: item.dig<String>("video", "bit_rate", 0, "play_addr", "url_list", 0)
            ?: item.dig<String>("video", "download_addr", "url_list", 0)
    }
}

/** Safely traverse Gson's nested Map/List representation without unchecked casts at call sites. */
@Suppress("UNCHECKED_CAST")
internal fun <T> Any.dig(vararg path: Any): T? {
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
