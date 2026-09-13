package com.jn.dyparse

import java.net.URI
import java.net.URLEncoder

/** 淇濆瓨鍓嶆帰娴嬪埌鐨勮棰戣祫婧愪俊鎭紙渚涗笅杞介樁娈靛鐢紝閬垮厤閲嶅鎺㈡祴锛?*/
data class SaveSizeProbe(
    val totalBytes: Long,
    val rangeSupported: Boolean
)

data class DouyinDownloadRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val probe: SaveSizeProbe? = null
)

object DouyinVideoDownloadResolver {
    private const val DOUYIN_REFERER = "https://www.douyin.com/"
    private const val DOUYIN_DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    fun resolveOriginalVideoDownloadRequest(
        item: Map<String, Any>,
        cookieHeader: String?
    ): DouyinDownloadRequest? {
        val video = item.dig<Map<String, Any>>("video") ?: return null
        val videoId = video.dig<String>("play_addr", "uri")
            ?: video.dig<String>("vid")
            ?: video.dig<String>("download_addr", "uri")
        if (!videoId.isNullOrBlank() &&
            !videoId.startsWith("http", ignoreCase = true) &&
            !videoId.contains("mp3", ignoreCase = true)
        ) {
            return buildOriginalPlayEndpointRequest(videoId, cookieHeader)
        }

        val fallbackUrl = firstAddressUrl(video["play_addr"])
            ?: firstAddressUrl(video["download_addr"])
            ?: return null
        return DouyinDownloadRequest(
            url = fallbackUrl,
            headers = buildDownloadHeaders(fallbackUrl, cookieHeader)
        )
    }

    fun buildOriginalPlayEndpointRequest(
        videoId: String,
        cookieHeader: String?
    ): DouyinDownloadRequest? {
        val normalizedVideoId = videoId.trim().takeIf { it.isNotBlank() } ?: return null
        val url = buildOriginalPlayEndpointUrl(normalizedVideoId)
        return DouyinDownloadRequest(
            url = url,
            headers = buildDownloadHeaders(url, cookieHeader)
        )
    }

    internal fun buildDownloadHeaders(
        url: String,
        cookieHeader: String?
    ): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to DOUYIN_DESKTOP_USER_AGENT,
            "Referer" to DOUYIN_REFERER,
            "Accept" to "*/*"
        )
        val normalizedCookie = DouyinAuthStore.normalizeCookieHeader(cookieHeader.orEmpty())
            .takeIf { it.isNotBlank() }
        if (normalizedCookie != null && shouldAttachCookie(url)) {
            headers["Cookie"] = normalizedCookie
        }
        return headers
    }


    private fun firstAddressUrl(address: Any?): String? {
        val addressMap = address as? Map<*, *> ?: return null
        return (addressMap["url_list"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? String }
            .firstOrNull { it.isNotBlank() }
    }

    private fun buildOriginalPlayEndpointUrl(videoId: String, ratio: String? = null): String {
        val encodedVideoId = URLEncoder.encode(videoId, "UTF-8")
        val encodedRatio = URLEncoder.encode(ratio?.takeIf { it.isNotBlank() } ?: "default", "UTF-8")
        return "https://www.iesdouyin.com/aweme/v1/play/" +
            "?video_id=$encodedVideoId&ratio=$encodedRatio&line=0" +
            "&is_play_url=1&watermark=0&source=PackSourceEnum_PUBLISH"
    }


    private fun shouldAttachCookie(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host.endsWith("douyin.com") || host.endsWith("iesdouyin.com")
    }

    private inline fun <reified T> Any.dig(vararg path: Any): T? {
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
