package com.jn.dyparse

import com.jn.dyparse.data.ParseResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAuthorMatcherTest {
    @Test
    fun itemWithMatchingSecUidIsAccepted() {
        val item = mapOf(
            "author" to mapOf(
                "uid" to "uid-1",
                "sec_uid" to "sec-1"
            )
        )

        assertTrue(
            BatchAuthorMatcher.matchesItemAuthor(
                item = item,
                expectedSecUid = "sec-1"
            )
        )
    }

    @Test
    fun itemWithDifferentSecUidIsRejected() {
        val item = mapOf(
            "author" to mapOf(
                "uid" to "uid-2",
                "sec_uid" to "sec-2"
            )
        )

        assertFalse(
            BatchAuthorMatcher.matchesItemAuthor(
                item = item,
                expectedSecUid = "sec-1"
            )
        )
    }

    @Test
    fun resultWithDifferentParsedAuthorIsRejected() {
        val result = ParseResult.Success(
            author = "other",
            authorUid = "uid-2",
            authorSecUid = "sec-2",
            title = "title",
            type = "video",
            playUrl = null,
            rawPlayUrl = "https://example.com/video.mp4",
            images = null,
            timestamp = 1L,
            videoId = "123",
            cover = null,
            inputUrl = "https://example.com"
        )

        assertFalse(
            BatchAuthorMatcher.matchesResultAuthor(
                result = result,
                expectedUid = "uid-1",
                expectedSecUid = "sec-1"
            )
        )
    }

    @Test
    fun resultWithoutComparableAuthorIdsIsAccepted() {
        val result = ParseResult.Success(
            author = "unknown",
            authorUid = null,
            authorSecUid = null,
            title = "title",
            type = "video",
            playUrl = null,
            rawPlayUrl = "https://example.com/video.mp4",
            images = null,
            timestamp = 1L,
            videoId = "123",
            cover = null,
            inputUrl = "https://example.com"
        )

        assertTrue(
            BatchAuthorMatcher.matchesResultAuthor(
                result = result,
                expectedSecUid = "sec-1"
            )
        )
    }
}
