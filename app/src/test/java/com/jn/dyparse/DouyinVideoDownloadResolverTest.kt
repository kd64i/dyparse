package com.jn.dyparse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地兜底下载地址解析的单元测试。
 *
 * 注意：「最高画质」不再由客户端按 bit_rate 挑选，已改为服务端解析
 * （`ServerApiClient.parse(highest = true)` → `highest=1`），因此这里只覆盖
 * 原画质（ratio=default）与请求头拼装逻辑。
 */
class DouyinVideoDownloadResolverTest {
    @Test
    fun buildDownloadHeadersOnlyAttachesCookieForDouyinHosts() {
        val cookieHeader = "sessionid=abc123; uid_tt=demo"

        val douyinHeaders = DouyinVideoDownloadResolver.buildDownloadHeaders(
            url = "https://www.douyin.com/aweme/v1/play/?video_id=1",
            cookieHeader = cookieHeader
        )
        val cdnHeaders = DouyinVideoDownloadResolver.buildDownloadHeaders(
            url = "https://v3-dy-o.zjcdn.example.com/video.mp4",
            cookieHeader = cookieHeader
        )

        assertEquals(cookieHeader, douyinHeaders["Cookie"])
        assertNull(cdnHeaders["Cookie"])
    }

    @Test
    fun resolveOriginalVideoDownloadRequestFallsBackToPrimaryPlayAddr() {
        val item = mapOf(
            "video" to mapOf(
                "play_addr" to mapOf(
                    "url_list" to listOf("https://cdn.example.com/default.mp4"),
                    "width" to 1920,
                    "height" to 1080
                )
            )
        )

        val request = DouyinVideoDownloadResolver.resolveOriginalVideoDownloadRequest(
            item = item,
            cookieHeader = null
        )

        assertEquals("https://cdn.example.com/default.mp4", request?.url)
    }

    @Test
    fun resolveOriginalVideoDownloadRequestBuildsDefaultRatioPlayEndpointFromUri() {
        val item = mapOf(
            "video" to mapOf(
                "height" to 2160,
                "width" to 3840,
                "play_addr" to mapOf(
                    "uri" to "v0d00fg10000d0rdmbfog65jivqmngdg",
                    "url_list" to listOf(
                        "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0d00fg10000d0rdmbfog65jivqmngdg&ratio=720p&line=0"
                    )
                )
            )
        )

        val request = DouyinVideoDownloadResolver.resolveOriginalVideoDownloadRequest(
            item = item,
            cookieHeader = "sessionid=abc123"
        )

        val url = request?.url.orEmpty()
        assertTrue(url.contains("/aweme/v1/play/"))
        assertTrue(url.contains("video_id=v0d00fg10000d0rdmbfog65jivqmngdg"))
        assertTrue(url.contains("ratio=default"))
    }

    @Test
    fun resolveOriginalVideoDownloadRequestUsesDefaultRatioInsteadOfHighestBitrate() {
        val item = mapOf(
            "video" to mapOf(
                "height" to 1080,
                "width" to 1920,
                "play_addr" to mapOf(
                    "uri" to "v0d00default12345"
                ),
                "bit_rate" to listOf(
                    mapOf(
                        "bit_rate" to 6_000_000,
                        "gear_name" to "adapt_1440_0",
                        "play_addr" to mapOf(
                            "uri" to "v0d00highest1440",
                            "width" to 2560,
                            "height" to 1440
                        )
                    )
                )
            )
        )

        val request = DouyinVideoDownloadResolver.resolveOriginalVideoDownloadRequest(
            item = item,
            cookieHeader = "sessionid=abc123"
        )

        val url = request?.url.orEmpty()
        assertTrue(url.contains("video_id=v0d00default12345"))
        assertTrue(url.contains("ratio=default"))
        assertFalse(url.contains("v0d00highest1440"))
        assertFalse(url.contains("ratio=1440p"))
    }

    @Test
    fun buildOriginalPlayEndpointRequestUsesDefaultRatio() {
        val request = DouyinVideoDownloadResolver.buildOriginalPlayEndpointRequest(
            videoId = "v0d00fg10000d0rdmbfog65jivqmngdg",
            cookieHeader = "sessionid=abc123"
        )

        val url = request?.url.orEmpty()
        assertTrue(url.contains("/aweme/v1/play/"))
        assertTrue(url.contains("video_id=v0d00fg10000d0rdmbfog65jivqmngdg"))
        assertTrue(url.contains("ratio=default"))
        assertEquals("sessionid=abc123", request?.headers?.get("Cookie"))
    }
}
