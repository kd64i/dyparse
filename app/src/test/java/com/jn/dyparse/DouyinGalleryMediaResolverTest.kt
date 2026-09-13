package com.jn.dyparse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinGalleryMediaResolverTest {
    @Test
    fun collectGalleryMediaIncludesLivePhotoVideos() {
        val item = mapOf(
            "image_post_info" to mapOf(
                "images" to listOf(
                    mapOf(
                        "display_image" to mapOf(
                            "url_list" to listOf("https://example.com/cover_1.webp")
                        ),
                        "video" to mapOf(
                            "play_addr" to mapOf(
                                "url_list" to listOf("https://example.com/live_1.mp4")
                            )
                        )
                    ),
                    mapOf(
                        "video" to mapOf(
                            "play_addr" to mapOf(
                                "url_list" to listOf("https://example.com/live_2.mp4")
                            )
                        )
                    )
                )
            )
        )

        val media = DouyinGalleryMediaResolver.collectGalleryMedia(item)

        assertEquals(2, media.size)
        assertEquals("https://example.com/cover_1.webp", media[0].imageUrl)
        assertEquals("https://example.com/live_1.mp4", media[0].livePhotoRawUrl)
        assertNull(media[1].imageUrl)
        assertEquals("https://example.com/live_2.mp4", media[1].livePhotoRawUrl)
        assertTrue(media.all { it.hasLivePhoto })
    }

    @Test
    fun collectGalleryMediaKeepsDirectImageUrls() {
        val item = mapOf(
            "images" to listOf(
                mapOf("url_list" to listOf("https://example.com/image_1.jpeg")),
                mapOf("image_url" to mapOf("url_list" to listOf("https://example.com/image_2.jpeg")))
            )
        )

        val media = DouyinGalleryMediaResolver.collectGalleryMedia(item)

        assertEquals(listOf("https://example.com/image_1.jpeg", "https://example.com/image_2.jpeg"), media.map { it.imageUrl })
        assertTrue(media.none { it.hasLivePhoto })
    }

    @Test
    fun collectGalleryMediaKeepsWatermarkFreeImagePriority() {
        val item = mapOf(
            "images" to listOf(
                mapOf(
                    "download_url_list" to listOf("https://example.com/watermark.webp"),
                    "image_url" to mapOf("url_list" to listOf("https://example.com/clean.webp")),
                    "owner_watermark_image" to mapOf("url_list" to listOf("https://example.com/owner.webp"))
                )
            )
        )

        val media = DouyinGalleryMediaResolver.collectGalleryMedia(item)

        assertEquals("https://example.com/clean.webp", media.single().imageUrl)
    }

    @Test
    fun collectGalleryMediaBuildsNoWatermarkEndpointForTopLevelLivePhotos() {
        val item = mapOf(
            "images" to listOf(
                mapOf(
                    "image_url" to mapOf("url_list" to listOf("https://example.com/clean.webp")),
                    "video" to mapOf(
                        "play_addr" to mapOf(
                            "uri" to "v0d00livephoto12345",
                            "url_list" to listOf("https://example.com/watermarked-live.mp4")
                        )
                    )
                )
            )
        )

        val livePhotoUrl = DouyinGalleryMediaResolver.collectGalleryMedia(item)
            .single()
            .livePhotoRawUrl
            .orEmpty()

        assertTrue(livePhotoUrl.contains("/aweme/v1/play/"))
        assertTrue(livePhotoUrl.contains("video_id=v0d00livephoto12345"))
        assertTrue(livePhotoUrl.contains("watermark=0"))
    }

    @Test
    fun collectGalleryMediaMergesCleanImagesWithLivePhotoVideoByIndex() {
        val item = mapOf(
            "images" to listOf(
                mapOf(
                    "download_url_list" to listOf("https://example.com/watermark_1.webp"),
                    "video" to mapOf(
                        "play_addr" to mapOf("uri" to "v0d00livephoto12345")
                    )
                )
            ),
            "image_infos" to listOf(
                mapOf(
                    "label_large" to mapOf(
                        "url_list" to listOf("https://example.com/clean_1.webp")
                    )
                )
            )
        )

        val media = DouyinGalleryMediaResolver.collectGalleryMedia(item).single()

        assertEquals("https://example.com/clean_1.webp", media.imageUrl)
        assertTrue(media.livePhotoRawUrl.orEmpty().contains("watermark=0"))
    }
}
