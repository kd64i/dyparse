package com.jn.dyparse

import android.app.Application
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jn.dyparse.data.BatchAuthorParseSummary
import com.jn.dyparse.data.GalleryMedia
import com.jn.dyparse.data.HistoryRepository
import com.jn.dyparse.data.ParseResult
import com.jn.dyparse.data.totalMediaAssetCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.regex.Pattern

class AuthorBatchManager(
    private val application: Application,
    private val gson: Gson,
    private val client: OkHttpClient,
    private val historyRepository: HistoryRepository
) {
    private data class ResolvedAuthorInput(
        val secUserId: String,
        val matchedUrl: String,
        val finalUrl: String?,
        val authorUrl: String,
        val directPostApiUrl: HttpUrl? = null,
        val directPostApiUrls: List<HttpUrl> = emptyList(),
        val directPostApiHeaders: Map<String, String> = emptyMap(),
        val msToken: String = DouyinABogusSigner.generateMsToken(),
        val authCookie: String? = null
    )

    private data class CachedAuthorPostRequest(
        val secUserId: String,
        val urls: List<String>,
        val headers: Map<String, String>,
        val savedAt: Long = System.currentTimeMillis()
    )

    private data class AuthorPagePayload(
        val items: List<Map<String, Any>>,
        val hasMore: Boolean,
        val nextCursor: Long,
        val totalCount: Int?
    )

    private enum class AuthorSignatureMode {
        ABOGUS,
        XBOGUS
    }

    companion object {
        private const val AUTHOR_PAGE_FETCH_COUNT = 18
        private const val AUTHOR_PAGE_ID_TIMEOUT_MS = 35_000L
        private const val AUTHOR_BROWSER_PAGE_CANDIDATE_EXTRA_BUFFER = 12
        private const val AUTHOR_POST_CACHE_FILE_NAME = ".author_post_request_cache"

        private val mobileUserAgent by lazy { NativeLib.getParserUserAgent() }
        private const val desktopUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val signedAuthorUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val signedAuthorBrowserVersion = "130.0.0.0"
        private val urlPattern by lazy { Pattern.compile(NativeLib.getRegex(4)) }
        private val strictUrlPattern by lazy { Pattern.compile("https?://[^\\s'\"<>\\\\]+", Pattern.CASE_INSENSITIVE) }
        private val authorPathPattern by lazy { Pattern.compile("(?:share/)?user/([^/?]+)") }
        private val curlHeaderPattern by lazy {
            Pattern.compile("(?:^|\\s)(?:-H|--header)\\s+(?:'([^']*)'|\"([^\"]*)\")", Pattern.CASE_INSENSITIVE)
        }
        private val curlCookiePattern by lazy {
            Pattern.compile("(?:^|\\s)(?:-b|--cookie)\\s+(?:'([^']*)'|\"([^\"]*)\")", Pattern.CASE_INSENSITIVE)
        }
        private val rawHeaderLinePattern by lazy {
            Pattern.compile("^\\s*([A-Za-z0-9-]+)\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE)
        }
        private val skippedCapturedHeaders = setOf(
            "host",
            "connection",
            "content-length",
            "accept-encoding"
        )
        private val commonRequestHeaders = setOf(
            "accept",
            "accept-language",
            "cache-control",
            "cookie",
            "pragma",
            "referer",
            "user-agent"
        )
    }

    private val authorPostCacheFile = File(application.filesDir, AUTHOR_POST_CACHE_FILE_NAME)
    private val authorPostCacheMutex = Mutex()
    private var authorPostCache: MutableMap<String, CachedAuthorPostRequest>? = null
    private val webApiBridge = DouyinAuthorWebApiBridge(application.applicationContext)

    suspend fun loadHistoryIndex(): List<BatchAuthorParseSummary> =
        historyRepository.getBatchHistory()

    suspend fun parseAuthorBatch(
        input: String,
        requestedCount: Int?,
        collectStartIndex: Int = 1,
        onProgress: (author: String?, currentCount: Int) -> Unit
    ): Pair<BatchAuthorParseSummary, List<ParseResult.Success>> = withContext(Dispatchers.IO) {
        val resolvedInput = resolveAuthorInput(input)
        val batchId = UUID.randomUUID().toString()
        val requestedLimit = requestedCount?.coerceAtLeast(1)
        val skipBeforeCount = (collectStartIndex - 1).coerceAtLeast(0)
        val works = mutableListOf<ParseResult.Success>()

        var totalCountHint: Int? = null
        var authorName: String? = null
        var authorUid: String? = null
        var avatarUrl: String? = null
        var stoppedBeforeComplete = false
        val seenAwemeIds = linkedSetOf<String>()
        var nextCursor = 0L
        var hasMore = true

        val shouldPreferBrowserCollection =
            requestedLimit == null || requestedLimit > AUTHOR_PAGE_FETCH_COUNT
        val isAuthenticatedAuthorRequest = shouldUseAuthenticatedBrowserProfile(resolvedInput)
        fun hasReachedRequestedLimit(): Boolean {
            return requestedLimit != null && seenAwemeIds.size >= requestedLimit
        }

        if (shouldPreferBrowserCollection) {
            val firstPageCount = minOf(
                requestedLimit ?: AUTHOR_PAGE_FETCH_COUNT,
                AUTHOR_PAGE_FETCH_COUNT
            ).coerceAtLeast(1)
            val firstPage = runCatching {
                fetchAuthorPage(resolvedInput, 0L, firstPageCount)
            }.onFailure { throwable ->
                Log.w("AuthorBatchManager", "Initial author page fetch failed, browser fallback will continue", throwable)
            }.getOrNull()

            if (firstPage != null) {
                totalCountHint = firstPage.totalCount ?: totalCountHint
                nextCursor = firstPage.nextCursor
                hasMore = firstPage.hasMore && firstPage.nextCursor != 0L
                if (firstPage.hasMore && firstPage.nextCursor == 0L) {
                    stoppedBeforeComplete = true
                }
                firstPage.items.forEach { item ->
                    val added = mergeSeedWork(
                        item = item,
                        batchId = batchId,
                        authorSecUid = resolvedInput.secUserId,
                        authorNameState = { current -> authorName = current ?: authorName },
                        authorUidState = { current -> authorUid = current ?: authorUid },
                        avatarUrlState = { current -> avatarUrl = current ?: avatarUrl },
                        seenAwemeIds = seenAwemeIds,
                        works = works,
                        skipBeforeCount = skipBeforeCount
                    )
                    if (added) {
                        onProgress(authorName, works.size)
                    }
                }
            }

            // 列表已走服务器（cookie 全量），不再因本地无 cookie 限制翻页。
            // 服务器 has_more 决定是否继续；除非作者主页确实只有一页。
            if (!hasMore) {
                stoppedBeforeComplete = false
            }

            while (hasMore && !hasReachedRequestedLimit()) {
                val requestCursor = nextCursor
                val remaining = requestedLimit?.minus(seenAwemeIds.size)
                val pageCount = minOf(AUTHOR_PAGE_FETCH_COUNT, remaining ?: AUTHOR_PAGE_FETCH_COUNT)
                    .coerceAtLeast(1)
                val page = runCatching {
                    fetchAuthorPage(
                        resolvedInput = resolvedInput,
                        cursor = requestCursor,
                        count = pageCount
                    )
                }.recoverCatching {
                    fetchAuthorPageInBrowserContext(
                        resolvedInput = resolvedInput,
                        cursor = requestCursor,
                        count = pageCount
                    )
                }.onFailure { throwable ->
                    Log.w("AuthorBatchManager", "Browser context pagination stopped early", throwable)
                    stoppedBeforeComplete = true
                }.getOrNull() ?: break

                totalCountHint = page.totalCount ?: totalCountHint
                if (page.items.isEmpty()) {
                    stoppedBeforeComplete = stoppedBeforeComplete || page.hasMore || requestCursor > 0L
                    break
                }

                page.items.forEach { item ->
                    if (hasReachedRequestedLimit()) {
                        return@forEach
                    }
                    val added = mergeSeedWork(
                        item = item,
                        batchId = batchId,
                        authorSecUid = resolvedInput.secUserId,
                        authorNameState = { current -> authorName = current ?: authorName },
                        authorUidState = { current -> authorUid = current ?: authorUid },
                        avatarUrlState = { current -> avatarUrl = current ?: avatarUrl },
                        seenAwemeIds = seenAwemeIds,
                        works = works,
                        skipBeforeCount = skipBeforeCount
                    )
                    if (added) {
                        onProgress(authorName, works.size)
                    }
                }

                val newCursor = page.nextCursor
                if (page.hasMore && (newCursor == requestCursor || newCursor == 0L)) {
                    stoppedBeforeComplete = true
                    hasMore = false
                } else {
                    hasMore = page.hasMore
                    nextCursor = newCursor
                }
            }

            val requestedCountMissing = requestedLimit != null && seenAwemeIds.size < requestedLimit
            if (isAuthenticatedAuthorRequest &&
                (stoppedBeforeComplete ||
                    hasMore ||
                    requestedCountMissing)
            ) {
                val browserExpectedCount = requestedLimit ?: 0
                val browserResult = webApiBridge.collectAuthorWorkIds(
                    profileUrl = buildBrowserCollectionProfileUrl(resolvedInput),
                    secUserId = resolvedInput.secUserId,
                    expectedCount = browserExpectedCount,
                    knownAwemeIds = seenAwemeIds
                )
                browserResult.awemePageBodies.forEach { pageBody ->
                    val page = runCatching { parseAuthorPageBody(pageBody, nextCursor) }
                        .onFailure { throwable ->
                            Log.w("AuthorBatchManager", "Browser aweme_list reuse failed", throwable)
                        }
                        .getOrNull()
                    page?.items.orEmpty().forEach { item ->
                        if (hasReachedRequestedLimit()) {
                            return@forEach
                        }
                        val added = mergeSeedWork(
                            item = item,
                            batchId = batchId,
                            authorSecUid = resolvedInput.secUserId,
                            authorNameState = { current -> authorName = current ?: authorName },
                            authorUidState = { current -> authorUid = current ?: authorUid },
                            avatarUrlState = { current -> avatarUrl = current ?: avatarUrl },
                            seenAwemeIds = seenAwemeIds,
                            works = works,
                            skipBeforeCount = skipBeforeCount
                        )
                        if (added) {
                            onProgress(authorName, works.size)
                        }
                    }
                }
                browserResult.awemeIds.forEach { awemeId ->
                    if (hasReachedRequestedLimit()) {
                        return@forEach
                    }
                    if (!seenAwemeIds.add(awemeId)) {
                        return@forEach
                    }
                    if (seenAwemeIds.size <= skipBeforeCount) {
                        return@forEach
                    }
                    works.add(
                        buildSeedWork(
                            awemeId = awemeId,
                            batchId = batchId,
                            author = authorName,
                            authorUid = authorUid,
                            authorSecUid = resolvedInput.secUserId,
                            avatarUrl = avatarUrl,
                            item = emptyMap()
                        )
                    )
                    onProgress(authorName, works.size)
                }
                val missingAfterTrustedRecovery = requestedLimit
                    ?.minus(seenAwemeIds.size)
                    ?.coerceAtLeast(0)
                    ?: 0
                if (missingAfterTrustedRecovery > 0) {
                    browserResult.pageAwemeIds
                        .asSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it !in seenAwemeIds }
                        .distinct()
                        .take(missingAfterTrustedRecovery + AUTHOR_BROWSER_PAGE_CANDIDATE_EXTRA_BUFFER)
                        .forEach { awemeId ->
                            if (hasReachedRequestedLimit()) {
                                return@forEach
                            }
                            val recoveredWork = recoverBrowserPageCandidate(
                                awemeId = awemeId,
                                batchId = batchId,
                                authorSecUid = resolvedInput.secUserId,
                                author = authorName,
                                authorUid = authorUid,
                                avatarUrl = avatarUrl
                            ) ?: return@forEach
                            if (!seenAwemeIds.add(recoveredWork.videoId)) {
                                return@forEach
                            }
                            works.add(recoveredWork)
                            onProgress(authorName, works.size)
                        }
                }
                if (!browserResult.isComplete && requestedLimit == null) {
                    stoppedBeforeComplete = true
                }
            }
            hasMore = false
        } else {
            while (hasMore && !hasReachedRequestedLimit()) {
                val requestCursor = nextCursor
                val remaining = requestedLimit - seenAwemeIds.size
                val pageCount = minOf(AUTHOR_PAGE_FETCH_COUNT, remaining)
                    .coerceAtLeast(1)
                val page = try {
                    fetchAuthorPage(resolvedInput, requestCursor, pageCount)
                } catch (e: IOException) {
                    if (seenAwemeIds.isNotEmpty() && resolvedInput.hasDirectPostApi()) {
                        Log.w("AuthorBatchManager", "Direct post API pagination stopped early", e)
                        stoppedBeforeComplete = true
                        break
                    }
                    throw e
                }
                totalCountHint = page.totalCount ?: totalCountHint

                if (page.items.isEmpty()) {
                    stoppedBeforeComplete = stoppedBeforeComplete || page.hasMore || requestCursor > 0L
                    break
                }

                page.items.forEach { item ->
                    if (hasReachedRequestedLimit()) {
                        return@forEach
                    }
                    val added = mergeSeedWork(
                        item = item,
                        batchId = batchId,
                        authorSecUid = resolvedInput.secUserId,
                        authorNameState = { current -> authorName = current ?: authorName },
                        authorUidState = { current -> authorUid = current ?: authorUid },
                        avatarUrlState = { current -> avatarUrl = current ?: avatarUrl },
                        seenAwemeIds = seenAwemeIds,
                        works = works,
                        skipBeforeCount = skipBeforeCount
                    )
                    if (added) {
                        onProgress(authorName, works.size)
                    }
                }

                val newCursor = page.nextCursor
                if (page.hasMore && (newCursor == requestCursor || newCursor == 0L)) {
                    stoppedBeforeComplete = true
                    hasMore = false
                } else {
                    hasMore = page.hasMore
                    nextCursor = newCursor
                }
            }
        }

        if (works.isEmpty()) {
            if (skipBeforeCount > 0) {
                throw IOException("\u672a\u83b7\u53d6\u5230\u6307\u5b9a\u6392\u5e8f\u7684\u4f5c\u54c1")
            }
            throw IOException("\u6279\u91cf\u89e3\u6790\u5931\u8d25")
        }

        val videoCount = works.count { it.type == "video" }
        val imageCount = works.count { it.type == "image" }
        val totalMediaCount = works.sumOf { it.totalMediaAssetCount }
        val isComplete = !stoppedBeforeComplete && (requestedLimit == null || !hasMore)
        val totalAvailableCount = when {
            totalCountHint != null -> totalCountHint
            isComplete -> works.size
            else -> maxOf(requestedLimit ?: works.size, works.size)
        }

        val summary = BatchAuthorParseSummary(
            batchId = batchId,
            author = authorName ?: "\u672a\u77e5\u4f5c\u8005",
            authorUid = authorUid,
            authorSecUid = resolvedInput.secUserId,
            avatarUrl = avatarUrl,
            inputUrl = input,
            resolvedUrl = resolvedInput.finalUrl,
            authorUrl = resolvedInput.authorUrl,
            requestedCount = requestedLimit,
            parsedCount = works.size,
            totalAvailableCount = totalAvailableCount,
            isComplete = isComplete,
            videoCount = videoCount,
            imageCount = imageCount,
            totalMediaCount = totalMediaCount
        )

        summary to works
    }

    suspend fun resolveAuthorCaptureUrl(input: String): String = withContext(Dispatchers.IO) {
        resolveAuthorInput(input).authorUrl
    }

    suspend fun createAuthorBatchFromAwemeIds(
        input: String,
        requestedCount: Int?,
        collectStartIndex: Int = 1,
        awemeIds: List<String>,
        author: String?,
        authorUid: String?,
        authorSecUid: String?,
        avatarUrl: String?,
        isComplete: Boolean
    ): Pair<BatchAuthorParseSummary, List<ParseResult.Success>> = withContext(Dispatchers.IO) {
        val batchId = UUID.randomUUID().toString()
        val requestedLimit = requestedCount?.coerceAtLeast(1)
        val skipBeforeCount = (collectStartIndex - 1).coerceAtLeast(0)
        val distinctIds = awemeIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val limitedIds = requestedLimit?.let(distinctIds::take) ?: distinctIds
        val normalizedIds = limitedIds
            .drop(skipBeforeCount)

        if (normalizedIds.isEmpty()) {
            if (skipBeforeCount > 0) {
                throw IOException("\u672a\u83b7\u53d6\u5230\u6307\u5b9a\u6392\u5e8f\u7684\u4f5c\u54c1")
            }
            throw IOException("\u672a\u83b7\u53d6\u5230\u4f5c\u8005\u4f5c\u54c1 ID")
        }

        val works = normalizedIds.map { awemeId ->
            buildSeedWork(
                awemeId = awemeId,
                batchId = batchId,
                author = author,
                authorUid = authorUid,
                authorSecUid = authorSecUid,
                avatarUrl = avatarUrl,
                item = emptyMap()
            )
        }

        val summary = BatchAuthorParseSummary(
            batchId = batchId,
            author = author?.takeIf { it.isNotBlank() } ?: "\u672a\u77e5\u4f5c\u8005",
            authorUid = authorUid,
            authorSecUid = authorSecUid,
            avatarUrl = avatarUrl,
            inputUrl = input,
            resolvedUrl = null,
            authorUrl = null,
            requestedCount = requestedLimit,
            parsedCount = works.size,
            totalAvailableCount = if (isComplete) {
                distinctIds.size
            } else {
                maxOf(requestedLimit ?: distinctIds.size, distinctIds.size)
            },
            isComplete = isComplete,
            videoCount = works.size,
            imageCount = 0,
            totalMediaCount = works.size
        )

        summary to works
    }

    suspend fun saveBatchHistory(
        summary: BatchAuthorParseSummary,
        works: List<ParseResult.Success>
    ): List<BatchAuthorParseSummary> = historyRepository.saveBatchHistory(summary, works)

    suspend fun getBatchHistoryWorks(batchId: String): List<ParseResult.Success> =
        historyRepository.getBatchHistoryWorks(batchId)

    suspend fun deleteBatchHistoryItem(summary: BatchAuthorParseSummary): List<BatchAuthorParseSummary> =
        historyRepository.deleteBatchHistory(summary.batchId)

    suspend fun updateBatchWork(
        item: ParseResult.Success,
        transform: (ParseResult.Success) -> ParseResult.Success
    ) = historyRepository.updateBatchWork(item, transform)

    suspend fun destroy() {
        webApiBridge.destroy()
    }

    private fun authorPageRequestIntervalMs(): Long {
        return BatchParsePreferences.getSettings(application).authorPageIntervalMs.toLong()
    }

    private suspend fun fetchAuthorPage(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int
    ): AuthorPagePayload = withContext(Dispatchers.IO) {
        try {
            withTimeout(AUTHOR_PAGE_ID_TIMEOUT_MS) {
                // 优先走服务器列表（带 cookie + a_bogus，能拿到完整作品列表）；
                // 服务器不可用/失败时回落本地链路
                val serverPage = runCatching {
                    ServerAuthorClient.fetchAuthorList(
                        input = resolvedInput.authorUrl,
                        maxCursor = cursor,
                        count = count
                    )
                }.getOrNull()
                if (serverPage != null) {
                    AuthorPagePayload(
                        items = serverPage.items,
                        hasMore = serverPage.hasMore,
                        nextCursor = serverPage.nextCursor,
                        totalCount = serverPage.totalCount
                    )
                } else {
                    val body = fetchPreferredAuthorPageBody(resolvedInput, cursor, count)
                    parseAuthorPageBody(body, cursor)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw IOException(
                "\u83b7\u53d6\u4f5c\u8005\u4f5c\u54c1 ID \u8d85\u65f6\uff0c\u5df2\u505c\u6b62\u7b49\u5f85\uff1b\u8bf7\u7a0d\u540e\u91cd\u8bd5\u6216\u66f4\u6362\u7f51\u7edc",
                timeout
            )
        }
    }

    private suspend fun fetchAuthorPageInBrowserContext(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int
    ): AuthorPagePayload = withContext(Dispatchers.IO) {
        val body = fetchBrowserFallbackAuthorPageBody(
            resolvedInput = resolvedInput,
            cursor = cursor,
            count = count
        )
        parseAuthorPageBody(body, cursor)
    }

    private suspend fun parseAuthorPageBody(
        body: String,
        cursor: Long
    ): AuthorPagePayload {
        if (DouyinRequestLimiter.isLikelyRiskBody(body)) {
            DouyinRequestLimiter.reportRiskFailure()
        }
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val data: Map<String, Any> = gson.fromJson(body, type)
        val statusCode = data.dig<Number>("status_code")?.toInt() ?: 0
        if (statusCode != 0) {
            if (DouyinRequestLimiter.isLikelyRiskStatus(statusCode) ||
                DouyinRequestLimiter.isLikelyRiskBody(body)
            ) {
                DouyinRequestLimiter.reportRiskFailure()
            }
            throw IOException("\u4f5c\u8005\u4e3b\u9875\u63a5\u53e3\u8fd4\u56de\u5f02\u5e38 ($statusCode)")
        }

        val items = (data["aweme_list"] as? List<*>)?.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            it as? Map<String, Any>
        }.orEmpty()

        return AuthorPagePayload(
            items = items,
            hasMore = (data.dig<Number>("has_more")?.toInt() ?: 0) == 1,
            nextCursor = data.dig<Number>("max_cursor")?.toLong() ?: cursor,
            totalCount = data.dig<Number>("total")?.toInt()
                ?: data.dig<Number>("aweme_count")?.toInt()
                ?: data.dig<Number>("post_count")?.toInt()
        )
    }

    private suspend fun fetchPreferredAuthorPageBody(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int
    ): String {
        val normalizedCount = count.coerceIn(1, AUTHOR_PAGE_FETCH_COUNT)
        if (!resolvedInput.hasDirectPostApi()) {
            val legacyBody = runCatching {
                fetchLegacyAuthorPageBody(resolvedInput, cursor, normalizedCount)
            }.onFailure { throwable ->
                Log.w("AuthorBatchManager", "Legacy author post API request failed", throwable)
            }.getOrNull()
            if (!legacyBody.isNullOrBlank() && legacyBody.contains("\"aweme_list\"", ignoreCase = true)) {
                return legacyBody
            }
        }

        val directAttempt = runCatching {
            fetchDirectAuthorPageBody(resolvedInput, cursor, normalizedCount)
        }
        val directBody = directAttempt.getOrNull()

        if (!directBody.isNullOrBlank() && directBody.contains("\"aweme_list\"", ignoreCase = true)) {
            return directBody
        }

        if (!resolvedInput.hasDirectPostApi()) {
            val browserRetryBody = runCatching {
                fetchBrowserFallbackAuthorPageBody(resolvedInput, cursor, normalizedCount)
            }.onFailure { throwable ->
                Log.w("AuthorBatchManager", "Browser fallback author page retry failed", throwable)
            }.getOrNull()
            if (!browserRetryBody.isNullOrBlank() && browserRetryBody.contains("\"aweme_list\"", ignoreCase = true)) {
                return browserRetryBody
            }

            val legacyRetryBody = runCatching {
                fetchLegacyAuthorPageBody(resolvedInput, cursor, normalizedCount)
            }.onFailure { throwable ->
                Log.w("AuthorBatchManager", "Legacy author post API retry failed", throwable)
            }.getOrNull()
            if (!legacyRetryBody.isNullOrBlank() && legacyRetryBody.contains("\"aweme_list\"", ignoreCase = true)) {
                return legacyRetryBody
            }
        }

        directAttempt.exceptionOrNull()
            ?.takeIf { directBody.isNullOrBlank() }
            ?.let { throwable ->
                throw IOException(
                    "\u4f5c\u8005\u4f5c\u54c1\u5217\u8868\u8bf7\u6c42\u5931\u8d25: ${throwable.message}",
                    throwable
                )
            }

        val responseSnippet = directBody.orEmpty()
            .replace(Regex("\\s+"), " ")
            .take(160)
        if (DouyinRequestLimiter.isLikelyRiskBody(directBody.orEmpty()) ||
            responseSnippet.isBlank()
        ) {
            DouyinRequestLimiter.reportRiskFailure()
        }
        throw IOException(
            "\u4f5c\u8005\u4f5c\u54c1\u5217\u8868\u63a5\u53e3\u672a\u8fd4\u56de\u4f5c\u54c1\u6570\u636e\uff0c\u53ef\u80fd\u89e6\u53d1\u98ce\u63a7\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u6216\u66f4\u6362\u7f51\u7edc\u3002\u54cd\u5e94: $responseSnippet"
        )
    }

    private suspend fun fetchBrowserFallbackAuthorPageBody(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int
    ): String {
        val profileUrl = buildBrowserCollectionProfileUrl(resolvedInput)
        val preferAuthenticatedBrowser = shouldUseAuthenticatedBrowserProfile(resolvedInput)

        val legacyBody = runCatching {
            webApiBridge.fetchLegacyAuthorPostPage(
                profileUrl = profileUrl,
                secUserId = resolvedInput.secUserId,
                cursor = cursor,
                count = count
            )
        }.onFailure { throwable ->
            Log.w("AuthorBatchManager", "Legacy browser fallback author request failed", throwable)
        }.getOrNull()
        if (!legacyBody.isNullOrBlank() && legacyBody.contains("\"aweme_list\"", ignoreCase = true)) {
            return legacyBody
        }

        if (preferAuthenticatedBrowser) {
            val browserBody = runCatching {
                webApiBridge.fetchAuthorPostPage(
                    profileUrl = profileUrl,
                    secUserId = resolvedInput.secUserId,
                    cursor = cursor,
                    count = count
                )
            }.onFailure { throwable ->
                Log.w("AuthorBatchManager", "Authenticated browser author request failed", throwable)
            }.getOrNull()
            if (!browserBody.isNullOrBlank() && browserBody.contains("\"aweme_list\"", ignoreCase = true)) {
                return browserBody
            }
        }

        return webApiBridge.fetchAuthorPostPage(
            profileUrl = profileUrl,
            secUserId = resolvedInput.secUserId,
            cursor = cursor,
            count = count
        )
    }

    private suspend fun fetchLegacyAuthorPageBody(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int
    ): String {
        DouyinRequestLimiter.acquire(authorPageRequestIntervalMs())
        val request = Request.Builder()
            .url(buildLegacyAuthorApiUrl(resolvedInput, cursor, count))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", mobileUserAgent)
            .header("Referer", buildAuthorShareUrl(resolvedInput.secUserId))
            .apply {
                resolvedInput.authCookie
                    ?.takeIf { it.isNotBlank() }
                    ?.let { header("Cookie", it) }
            }
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (DouyinRequestLimiter.isLikelyRiskStatus(response.code)) {
                    DouyinRequestLimiter.reportRiskFailure()
                }
                throw IOException("\u65e7\u7248\u4f5c\u8005\u4f5c\u54c1\u5217\u8868\u8bf7\u6c42\u5931\u8d25 (${response.code})")
            }
            response.body?.string().orEmpty().also { body ->
                if (DouyinRequestLimiter.isLikelyRiskBody(body)) {
                    DouyinRequestLimiter.reportRiskFailure()
                } else {
                    DouyinRequestLimiter.reportSuccess()
                }
            }
        }
    }

    private fun buildLegacyAuthorApiUrl(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int
    ): HttpUrl {
        return "https://www.iesdouyin.com/web/api/v2/aweme/post/".toHttpUrl()
            .newBuilder()
            .addQueryParameter("reflow_source", "reflow_page")
            .addQueryParameter("aid", "1128")
            .addQueryParameter("sec_uid", resolvedInput.secUserId)
            .addQueryParameter("max_cursor", cursor.toString())
            .addQueryParameter("count", count.coerceIn(1, AUTHOR_PAGE_FETCH_COUNT).toString())
            .build()
    }

    private suspend fun fetchDirectAuthorPageBody(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int
    ): String = withContext(Dispatchers.IO) {
        val first = executeAuthorPageRequest(resolvedInput, cursor, count, AuthorSignatureMode.ABOGUS)
        if (first.body.contains("\"aweme_list\"", ignoreCase = true) || resolvedInput.hasDirectPostApi()) {
            return@withContext first.body
        }

        val refreshedMsToken = first.msToken
        val retryInput = if (!refreshedMsToken.isNullOrBlank() && refreshedMsToken != resolvedInput.msToken) {
            resolvedInput.copy(msToken = refreshedMsToken)
        } else {
            resolvedInput
        }

        val second = if (retryInput !== resolvedInput) {
            executeAuthorPageRequest(retryInput, cursor, count, AuthorSignatureMode.ABOGUS)
        } else {
            first
        }
        if (second.body.contains("\"aweme_list\"", ignoreCase = true)) {
            return@withContext second.body
        }

        val third = runCatching {
            executeAuthorPageRequest(retryInput, cursor, count, AuthorSignatureMode.XBOGUS)
        }.getOrElse { throwable ->
            Log.w("AuthorBatchManager", "X-Bogus author post retry failed", throwable)
            return@withContext second.body
        }
        if (third.body.contains("\"aweme_list\"", ignoreCase = true)) {
            return@withContext third.body
        }

        val warmedCookie = runCatching {
            webApiBridge.warmUpDouyinCookies()
        }.onFailure { throwable ->
            Log.w("AuthorBatchManager", "Douyin cookie warm-up failed", throwable)
        }.getOrNull().orEmpty()
        if (warmedCookie.isBlank()) {
            return@withContext third.body
        }

        val cookieMsToken = extractCookieValue(warmedCookie, "msToken")
        val warmedInput = retryInput.copy(
            msToken = cookieMsToken ?: retryInput.msToken,
            directPostApiHeaders = retryInput.directPostApiHeaders + ("Cookie" to warmedCookie)
        )
        val fourth = runCatching {
            executeAuthorPageRequest(warmedInput, cursor, count, AuthorSignatureMode.ABOGUS)
        }.getOrElse { throwable ->
            Log.w("AuthorBatchManager", "Cookie warmed a_bogus author post retry failed", throwable)
            third
        }
        if (fourth.body.contains("\"aweme_list\"", ignoreCase = true)) {
            return@withContext fourth.body
        }

        val fifth = runCatching {
            executeAuthorPageRequest(warmedInput, cursor, count, AuthorSignatureMode.XBOGUS)
        }.getOrElse { throwable ->
            Log.w("AuthorBatchManager", "Cookie warmed X-Bogus author post retry failed", throwable)
            fourth
        }

        fifth.body
    }

    private data class AuthorPageHttpResult(
        val body: String,
        val msToken: String?
    )

    private suspend fun executeAuthorPageRequest(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int,
        signatureMode: AuthorSignatureMode
    ): AuthorPageHttpResult {
        DouyinRequestLimiter.acquire(authorPageRequestIntervalMs())
        val requestBuilder = Request.Builder()
            .url(buildAuthorApiUrl(resolvedInput, cursor, count, signatureMode))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", buildAuthorRequestUserAgent(resolvedInput))
            .header(
                "Referer",
                if (resolvedInput.hasDirectPostApi()) resolvedInput.authorUrl else "https://www.douyin.com/"
            )
        if (!resolvedInput.hasDirectPostApi() &&
            resolvedInput.directPostApiHeaders.headerValue("cookie").isNullOrBlank()
        ) {
            val authCookie = resolvedInput.authCookie
            if (!authCookie.isNullOrBlank()) {
                requestBuilder.header("Cookie", authCookie)
            } else {
                requestBuilder.header("Cookie", "msToken=${resolvedInput.msToken}")
            }
        }
        applyCapturedHeaders(requestBuilder, resolvedInput.directPostApiHeaders)

        return client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                if (DouyinRequestLimiter.isLikelyRiskStatus(response.code)) {
                    DouyinRequestLimiter.reportRiskFailure()
                }
                val prefix = if (resolvedInput.hasDirectPostApi()) {
                    "\u5b8c\u6574\u4f5c\u54c1\u5217\u8868 URL \u8bf7\u6c42\u5931\u8d25"
                } else {
                    "\u4f5c\u8005\u4e3b\u9875\u63a5\u53e3\u8bf7\u6c42\u5931\u8d25"
                }
                throw IOException("$prefix (${response.code})")
            }
            val body = response.body?.string().orEmpty()
            if (DouyinRequestLimiter.isLikelyRiskBody(body)) {
                DouyinRequestLimiter.reportRiskFailure()
            } else {
                DouyinRequestLimiter.reportSuccess()
            }
            AuthorPageHttpResult(
                body = body,
                msToken = extractMsToken(response)
            )
        }
    }

    private fun buildAuthorApiUrl(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int,
        signatureMode: AuthorSignatureMode = AuthorSignatureMode.ABOGUS
    ): HttpUrl {
        resolvedInput.directPostApiUrls
            .firstOrNull { (it.queryParameter("max_cursor")?.toLongOrNull() ?: 0L) == cursor }
            ?.let { return it }

        if (resolvedInput.directPostApiUrls.isNotEmpty()) {
            throw IOException("\u672a\u83b7\u53d6\u5230\u8be5\u9875\u4f5c\u54c1\u5217\u8868\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5")
        }

        resolvedInput.directPostApiUrl?.let { capturedUrl ->
            return capturedUrl.newBuilder()
                .setQueryParameter("max_cursor", cursor.toString())
                .build()
        }

        return buildSignedAuthorPostApiUrl(resolvedInput, cursor, count, signatureMode)
    }

    private fun buildAuthorRequestUserAgent(resolvedInput: ResolvedAuthorInput): String {
        resolvedInput.directPostApiHeaders.headerValue("user-agent")?.let { return it }
        val browserVersion = (resolvedInput.directPostApiUrl ?: resolvedInput.directPostApiUrls.firstOrNull())
            ?.queryParameter("browser_version")
            ?.takeIf { it.isNotBlank() }
            ?: return signedAuthorUserAgent
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$browserVersion Safari/537.36"
    }

    private fun buildSignedAuthorPostApiUrl(
        resolvedInput: ResolvedAuthorInput,
        cursor: Long,
        count: Int,
        signatureMode: AuthorSignatureMode
    ): HttpUrl {
        val unsignedUrl = "https://www.douyin.com/aweme/v1/web/aweme/post/".toHttpUrl()
            .newBuilder()
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("sec_user_id", resolvedInput.secUserId)
            .addQueryParameter("max_cursor", cursor.toString())
            .addQueryParameter("locate_query", "false")
            .addQueryParameter("show_live_replay_strategy", "1")
            .addQueryParameter("need_time_list", "1")
            .addQueryParameter("time_list_query", "0")
            .addQueryParameter("whale_cut_token", "")
            .addQueryParameter("cut_version", "1")
            .addQueryParameter("count", count.toString())
            .addQueryParameter("publish_video_strategy_type", "2")
            .addQueryParameter("from_user_page", "1")
            .addQueryParameter("update_version_code", "170400")
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("pc_libra_divert", "Windows")
            .addQueryParameter("support_h265", "1")
            .addQueryParameter("support_dash", "1")
            .addQueryParameter("cpu_core_num", "12")
            .addQueryParameter("version_code", "290100")
            .addQueryParameter("version_name", "29.1.0")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", signedAuthorBrowserVersion)
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", signedAuthorBrowserVersion)
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "100")
            .addQueryParameter("msToken", resolvedInput.msToken)
            .build()

        return when (signatureMode) {
            AuthorSignatureMode.ABOGUS -> DouyinABogusSigner.sign(unsignedUrl, signedAuthorUserAgent)
            AuthorSignatureMode.XBOGUS -> DouyinABogusSigner.signXBogus(unsignedUrl, signedAuthorUserAgent)
        }
    }

    private fun applyCapturedHeaders(
        requestBuilder: Request.Builder,
        capturedHeaders: Map<String, String>
    ) {
        capturedHeaders.forEach { (name, value) ->
            val normalizedName = name.trim()
            val normalizedValue = value.trim()
            val lowerName = normalizedName.lowercase()
            if (normalizedName.isBlank() ||
                normalizedValue.isBlank() ||
                lowerName in skippedCapturedHeaders ||
                lowerName.startsWith(":")
            ) {
                return@forEach
            }

            requestBuilder.header(normalizedName, normalizedValue)
        }
    }

    private fun buildSeedWork(
        awemeId: String,
        batchId: String,
        author: String?,
        authorUid: String?,
        authorSecUid: String?,
        avatarUrl: String?,
        item: Map<String, Any>
    ): ParseResult.Success {
        // 封面优先用作品封面（flat_cover），没有再用作者头像
        val workCover = item.dig<String>("flat_cover")
            ?: item.dig<String>("video", "cover", "url_list", 0)
            ?: item.dig<String>("video", "origin_cover", "url_list", 0)
            ?: avatarUrl
        // 图集（含实况视频地址，flat_live_photos 与 flat_images 按下标对应）
        val flatImages = item.dig<List<*>>("flat_images")?.mapNotNull { it as? String }.orEmpty()
        val flatLivePhotosRaw = item.dig<List<*>>("flat_live_photos").orEmpty()
        val galleryMedia = if (flatImages.isNotEmpty()) {
            flatImages.mapIndexed { index, url ->
                GalleryMedia(
                    index = index,
                    imageUrl = url,
                    livePhotoRawUrl = (flatLivePhotosRaw.getOrNull(index) as? String)
                        ?.takeIf { it.isNotBlank() }
                )
            }
        } else {
            null
        }
        return ParseResult.Success(
            author = author ?: "\u672a\u77e5\u4f5c\u8005",
            authorUid = authorUid,
            authorSecUid = item.dig<String>("author", "sec_uid") ?: authorSecUid,
            title = item.dig<String>("desc") ?: awemeId,
            type = if (!flatImages.isNullOrEmpty()) "image" else "video",
            playUrl = null,
            rawPlayUrl = item.dig<String>("flat_raw_play_url"),
            images = flatImages.takeIf { it.isNotEmpty() },
            galleryMedia = galleryMedia,
            timestamp = item.dig<Number>("create_time")?.toLong()
                ?: (System.currentTimeMillis() / 1000),
            videoId = awemeId,
            cover = workCover,
            inputUrl = buildWorkShareUrl(awemeId),
            resolvedUrl = buildWorkPageUrl(awemeId),
            source = "batch",
            batchId = batchId
        )
    }

    /** 兼容提取 aweme_id（抖音可能返回数字或字符串） */
    private fun extractAwemeId(item: Map<String, Any>): String? {
        return when (val raw = item["aweme_id"]) {
            is String -> raw.takeIf { it.isNotBlank() }
            is Number -> raw.toString()
            else -> null
        }
    }

    private fun mergeSeedWork(
        item: Map<String, Any>,
        batchId: String,
        authorSecUid: String?,
        authorNameState: (String?) -> Unit,
        authorUidState: (String?) -> Unit,
        avatarUrlState: (String?) -> Unit,
        seenAwemeIds: MutableSet<String>,
        works: MutableList<ParseResult.Success>,
        skipBeforeCount: Int = 0
    ): Boolean {
        val awemeId = extractAwemeId(item) ?: return false
        if (!seenAwemeIds.add(awemeId)) {
            return false
        }

        val authorName = item.dig<String>("author", "nickname")
        val authorUid = item.dig<String>("author", "uid")
        val avatarUrl = item.dig<String>("author", "avatar_thumb", "url_list", 0)
        authorNameState(authorName)
        authorUidState(authorUid)
        avatarUrlState(avatarUrl)
        if (seenAwemeIds.size <= skipBeforeCount) {
            return false
        }

        val reusableSeed = runCatching {
            parseAwemeItem(
                item = item,
                inputUrl = buildWorkShareUrl(awemeId),
                resolvedUrl = buildWorkPageUrl(awemeId),
                batchId = batchId,
                authorSecUid = authorSecUid
            ).copy(source = "batch_seed")
        }.getOrElse {
            buildSeedWork(
                awemeId = awemeId,
                batchId = batchId,
                author = authorName,
                authorUid = authorUid,
                authorSecUid = authorSecUid,
                avatarUrl = avatarUrl,
                item = item
            )
        }
        works.add(reusableSeed)
        return true
    }

    private suspend fun recoverBrowserPageCandidate(
        awemeId: String,
        batchId: String,
        authorSecUid: String,
        author: String?,
        authorUid: String?,
        avatarUrl: String?
    ): ParseResult.Success? {
        val body = runCatching {
            webApiBridge.fetchAwemeDetail(awemeId)
        }.onFailure { throwable ->
            Log.w("AuthorBatchManager", "Browser page candidate detail fetch failed: $awemeId", throwable)
        }.getOrNull() ?: return null

        val type = object : TypeToken<Map<String, Any>>() {}.type
        val data = runCatching {
            gson.fromJson<Map<String, Any>>(body, type)
        }.getOrNull() ?: return null
        val item = data.dig<Map<String, Any>>("aweme_detail")
            ?: data.dig<Map<String, Any>>("item_detail")
            ?: return null
        if (!BatchAuthorMatcher.matchesItemAuthor(item, expectedSecUid = authorSecUid)) {
            return null
        }

        val resolvedAwemeId = extractAwemeId(item) ?: awemeId
        return runCatching {
            parseAwemeItem(
                item = item,
                inputUrl = buildWorkShareUrl(resolvedAwemeId),
                resolvedUrl = buildWorkPageUrl(resolvedAwemeId),
                batchId = batchId,
                authorSecUid = authorSecUid
            ).copy(source = "batch_seed")
        }.getOrElse {
            buildSeedWork(
                awemeId = resolvedAwemeId,
                batchId = batchId,
                author = author,
                authorUid = authorUid,
                authorSecUid = authorSecUid,
                avatarUrl = avatarUrl,
                item = item
            )
        }
    }

    private suspend fun resolveAuthorInput(input: String): ResolvedAuthorInput = withContext(Dispatchers.IO) {
        val savedAuthCookie = DouyinAuthStore.getCookie(application)
        val savedMsToken = DouyinAuthStore.extractCookieValue(savedAuthCookie, "msToken")
        val matchedUrl = extractFirstUrl(input) ?: run {
            throw IOException("\u8bf7\u8f93\u5165\u4f5c\u8005\u4e3b\u9875\u5206\u4eab\u94fe\u63a5")
        }
        val capturedHeaders = extractCapturedRequestHeaders(input)
        val capturedPostApiUrls = extractAuthorPostApiUrls(input)

        if (capturedPostApiUrls.isNotEmpty()) {
            val secUserId = capturedPostApiUrls.firstNotNullOfOrNull(::extractAuthorSecUserId)
                ?: throw IOException("\u4f5c\u54c1\u5217\u8868\u63a5\u53e3 URL \u7f3a\u5c11 sec_user_id/sec_uid")
            val authorUrl = buildDouyinAuthorProfileUrl(secUserId)
            cacheAuthorPostRequest(secUserId, capturedPostApiUrls, capturedHeaders)
            return@withContext ResolvedAuthorInput(
                secUserId = secUserId,
                matchedUrl = matchedUrl,
                finalUrl = matchedUrl,
                authorUrl = authorUrl,
                directPostApiUrl = capturedPostApiUrls.firstOrNull(),
                directPostApiUrls = capturedPostApiUrls,
                directPostApiHeaders = capturedHeaders,
                msToken = extractMsTokenFromCapturedInput(input) ?: savedMsToken ?: resolveBootstrapMsToken(secUserId),
                authCookie = savedAuthCookie
            )
        }

        extractAuthorSecUserId(matchedUrl)?.let { secUserId ->
            val authorUrl = normalizeAuthorUrl(
                rawUrl = matchedUrl,
                secUserId = secUserId
            )
            return@withContext applyCachedAuthorPostRequest(
                ResolvedAuthorInput(
                    secUserId = secUserId,
                    matchedUrl = matchedUrl,
                    finalUrl = matchedUrl,
                    authorUrl = authorUrl,
                    msToken = savedMsToken ?: resolveBootstrapMsToken(secUserId),
                    authCookie = savedAuthCookie
                )
            )
        }

        val request = Request.Builder()
            .url(matchedUrl)
            .header("User-Agent", mobileUserAgent)
            .build()

        DouyinRequestLimiter.acquire(authorPageRequestIntervalMs())
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (DouyinRequestLimiter.isLikelyRiskStatus(response.code)) {
                    DouyinRequestLimiter.reportRiskFailure()
                }
                throw IOException("\u77ed\u94fe\u8bbf\u95ee\u5931\u8d25 (${response.code})")
            }
            DouyinRequestLimiter.reportSuccess()

            val finalUrl = response.request.url.toString()
            val secUserId = extractAuthorSecUserId(finalUrl)
                ?: throw IOException("\u8be5\u94fe\u63a5\u4e0d\u662f\u4f5c\u8005\u4e3b\u9875\u94fe\u63a5")

            applyCachedAuthorPostRequest(
                ResolvedAuthorInput(
                    secUserId = secUserId,
                    matchedUrl = matchedUrl,
                    finalUrl = finalUrl,
                    authorUrl = normalizeAuthorUrl(finalUrl, secUserId),
                    msToken = extractMsToken(response) ?: savedMsToken ?: resolveBootstrapMsToken(secUserId),
                    authCookie = savedAuthCookie
                )
            )
        }
    }

    private suspend fun applyCachedAuthorPostRequest(base: ResolvedAuthorInput): ResolvedAuthorInput {
        val cached = authorPostCacheMutex.withLock {
            readAuthorPostCacheNoLock()[base.secUserId]
        } ?: return base

        val urls = cached.urls
            .mapNotNull { runCatching { it.toHttpUrl() }.getOrNull() }
            .filter(::isAuthorPostApiUrl)
            .distinctBy { it.toString() }
        if (urls.isEmpty()) {
            return base
        }

        return base.copy(
            directPostApiUrl = urls.firstOrNull(),
            directPostApiUrls = urls,
            directPostApiHeaders = cached.headers
        )
    }

    private suspend fun cacheAuthorPostRequest(
        secUserId: String,
        urls: List<HttpUrl>,
        headers: Map<String, String>
    ) {
        val normalizedUrls = urls
            .filter(::isAuthorPostApiUrl)
            .distinctBy { it.toString() }
            .map { it.toString() }
        if (normalizedUrls.isEmpty()) {
            return
        }

        authorPostCacheMutex.withLock {
            val cache = readAuthorPostCacheNoLock()
            val previous = cache[secUserId]
            val mergedUrls = (previous?.urls.orEmpty() + normalizedUrls).distinct()
            val mergedHeaders = previous?.headers.orEmpty() + headers
            cache[secUserId] = CachedAuthorPostRequest(
                secUserId = secUserId,
                urls = mergedUrls,
                headers = mergedHeaders
            )
            writeAuthorPostCacheNoLock(cache)
        }
    }

    private fun readAuthorPostCacheNoLock(): MutableMap<String, CachedAuthorPostRequest> {
        authorPostCache?.let { return it }
        val cache = if (authorPostCacheFile.exists()) {
            runCatching {
                val type = object : TypeToken<Map<String, CachedAuthorPostRequest>>() {}.type
                gson.fromJson<Map<String, CachedAuthorPostRequest>>(
                    authorPostCacheFile.readText(),
                    type
                )
            }.getOrNull().orEmpty().toMutableMap()
        } else {
            mutableMapOf()
        }
        authorPostCache = cache
        return cache
    }

    private fun writeAuthorPostCacheNoLock(cache: Map<String, CachedAuthorPostRequest>) {
        runCatching {
            authorPostCacheFile.writeText(gson.toJson(cache))
        }.onFailure { throwable ->
            Log.w("AuthorBatchManager", "Failed to persist author post request cache", throwable)
        }
    }

    private fun parseAwemeItem(
        item: Map<String, Any>,
        inputUrl: String,
        resolvedUrl: String?,
        batchId: String,
        authorSecUid: String?
    ): ParseResult.Success {
        val author = item.dig<String>("author", "nickname")
            ?: item.dig<String>("flat_author_name")
            ?: "\u672a\u77e5\u4f5c\u8005"
        val authorUid = item.dig<String>("author", "uid")
            ?: item.dig<String>("flat_author_uid")
        val title = item.dig<String>("desc") ?: "\u65e0\u6807\u9898"
        val awemeId = extractAwemeId(item)
            ?: throw IOException("\u89e3\u6790\u5931\u8d25\uff1a\u672a\u627e\u5230\u4f5c\u54c1 ID")
        val timestamp = (item.dig<Number>("create_time"))?.toLong()
            ?: (System.currentTimeMillis() / 1000)

        // 优先使用服务器拍平的 flat 字段（避免深嵌套提取失败）
        val flatImages = item.dig<List<*>>("flat_images")?.mapNotNull { it as? String }.orEmpty()
        val flatLivePhotosRaw = item.dig<List<*>>("flat_live_photos").orEmpty()
        val galleryMedia = if (flatImages.isNotEmpty()) {
            flatImages.mapIndexed { index, url ->
                GalleryMedia(
                    index = index,
                    imageUrl = url,
                    livePhotoRawUrl = (flatLivePhotosRaw.getOrNull(index) as? String)
                        ?.takeIf { it.isNotBlank() }
                )
            }
        } else {
            DouyinContentMapper.extractGalleryMedia(item)
        }
        val cover = item.dig<String>("flat_cover")
            ?: DouyinContentMapper.extractCoverUrl(item, galleryMedia)
        val flatRawPlayUrl = item.dig<String>("flat_raw_play_url")
        if (galleryMedia.isNotEmpty() || flatImages.isNotEmpty()) {
            return ParseResult.Success(
                author = author,
                authorUid = authorUid,
                authorSecUid = item.dig<String>("author", "sec_uid") ?: authorSecUid,
                title = title,
                type = "image",
                playUrl = null,
                rawPlayUrl = null,
                images = (if (flatImages.isNotEmpty()) flatImages
                    else galleryMedia.mapNotNull { it.imageUrl }),
                galleryMedia = galleryMedia,
                timestamp = timestamp,
                videoId = awemeId,
                cover = cover,
                inputUrl = inputUrl,
                resolvedUrl = resolvedUrl,
                source = "batch",
                batchId = batchId
            )
        }

        val rawPlayUrl = flatRawPlayUrl
            ?: DouyinContentMapper.extractRawPlayUrl(item)
            ?: throw IOException("\u89e3\u6790\u5931\u8d25\uff1a\u672a\u627e\u5230\u64ad\u653e\u5730\u5740")

        return ParseResult.Success(
            author = author,
            authorUid = authorUid,
            authorSecUid = item.dig<String>("author", "sec_uid") ?: authorSecUid,
            title = title,
            type = "video",
            playUrl = null,
            rawPlayUrl = rawPlayUrl,
            images = null,
            timestamp = timestamp,
            videoId = awemeId,
            cover = cover,
            inputUrl = inputUrl,
            resolvedUrl = resolvedUrl,
            source = "batch",
            batchId = batchId
        )
    }

    private fun extractAuthorSecUserId(url: String): String? {
        authorPathPattern.matcher(url).let {
            if (it.find()) {
                return it.group(1)
            }
        }

        val httpUrl = runCatching { url.toHttpUrl() }.getOrNull()
        return httpUrl?.let(::extractAuthorSecUserId)
    }

    private fun extractAuthorSecUserId(url: HttpUrl): String? {
        return url.queryParameter("sec_uid")
            ?: url.queryParameter("sec_user_id")
    }

    private fun isAuthorPostApiUrl(url: HttpUrl): Boolean {
        return url.encodedPath.contains("/aweme/v1/web/aweme/post/", ignoreCase = true) ||
            url.encodedPath.contains("/web/api/v2/aweme/post/", ignoreCase = true)
    }

    private fun ResolvedAuthorInput.hasDirectPostApi(): Boolean {
        return directPostApiUrl != null || directPostApiUrls.isNotEmpty()
    }

    private fun extractFirstUrl(input: String): String? {
        strictUrlPattern.matcher(input).let { matcher ->
            if (matcher.find()) {
                return cleanUrlToken(matcher.group(0).orEmpty())
            }
        }

        urlPattern.matcher(input).let { matcher ->
            if (matcher.find()) {
                return cleanUrlToken(matcher.group(0).orEmpty())
            }
        }

        return null
    }

    private fun extractAuthorPostApiUrls(input: String): List<HttpUrl> {
        val urls = mutableListOf<HttpUrl>()
        strictUrlPattern.matcher(input).let { matcher ->
            while (matcher.find()) {
                val url = cleanUrlToken(matcher.group(0).orEmpty())
                val httpUrl = runCatching { url.toHttpUrl() }.getOrNull()
                if (httpUrl != null && isAuthorPostApiUrl(httpUrl)) {
                    urls.add(httpUrl)
                }
            }
        }

        return urls.distinctBy { it.toString() }
    }

    private fun cleanUrlToken(value: String): String {
        return value.trim()
            .trim('\'', '"', '`', '<', '>', '(', ')', ',', '\uff0c', '\u3002', ';', '\uff1b', '^', '\\')
    }

    private fun extractCapturedRequestHeaders(input: String): Map<String, String> {
        val headers = linkedMapOf<String, String>()

        curlHeaderPattern.matcher(input).let { matcher ->
            while (matcher.find()) {
                val headerLine = matcher.group(1) ?: matcher.group(2) ?: continue
                addCapturedHeader(headers, headerLine)
            }
        }

        curlCookiePattern.matcher(input).let { matcher ->
            while (matcher.find()) {
                val cookieValue = matcher.group(1) ?: matcher.group(2) ?: continue
                putHeader(headers, "Cookie", cookieValue)
            }
        }

        rawHeaderLinePattern.matcher(input).let { matcher ->
            while (matcher.find()) {
                val headerName = matcher.group(1) ?: continue
                val headerValue = matcher.group(2) ?: continue
                val lowerName = headerName.lowercase()
                if (lowerName in commonRequestHeaders ||
                    lowerName.startsWith("sec-") ||
                    lowerName.startsWith("x-")
                ) {
                    putHeader(headers, headerName, headerValue)
                }
            }
        }

        return headers
    }

    private fun addCapturedHeader(headers: MutableMap<String, String>, headerLine: String) {
        val separatorIndex = headerLine.indexOf(':')
        if (separatorIndex <= 0) {
            return
        }
        val name = headerLine.substring(0, separatorIndex)
        val value = headerLine.substring(separatorIndex + 1)
        putHeader(headers, name, value)
    }

    private fun putHeader(headers: MutableMap<String, String>, name: String, value: String) {
        val normalizedName = name.trim()
        val normalizedValue = value.trim()
        if (normalizedName.isBlank() || normalizedValue.isBlank()) {
            return
        }

        val existingKey = headers.keys.firstOrNull { it.equals(normalizedName, ignoreCase = true) }
        if (existingKey != null) {
            headers.remove(existingKey)
        }
        headers[normalizedName] = normalizedValue
    }

    private fun Map<String, String>.headerValue(name: String): String? {
        return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractMsToken(response: okhttp3.Response): String? {
        response.header("X-Ms-Token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        response.headers("Set-Cookie").forEach { header ->
            Regex("(?i)(?:^|;)\\s*msToken=([^;]+)")
                .find(header)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return null
    }

    private fun extractMsTokenFromCapturedInput(input: String): String? {
        Regex("(?i)(?:^|[?&;\\s])msToken=([^&;\\s'\"\\\\]+)")
            .find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        Regex("(?i)(?:^|;)\\s*msToken=([^;\\s'\"\\\\]+)")
            .find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    private fun extractCookieValue(cookieHeader: String, name: String): String? {
        return cookieHeader
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveBootstrapMsToken(secUserId: String): String {
        return fetchBootstrapMsToken(secUserId) ?: DouyinABogusSigner.generateMsToken()
    }

    private suspend fun fetchBootstrapMsToken(secUserId: String): String? {
        val bootstrapUrl = "https://www.iesdouyin.com/web/api/v2/aweme/post/".toHttpUrl()
            .newBuilder()
            .addQueryParameter("reflow_source", "reflow_page")
            .addQueryParameter("aid", "1128")
            .addQueryParameter("sec_uid", secUserId)
            .addQueryParameter("max_cursor", "0")
            .addQueryParameter("count", "1")
            .build()

        return runCatching {
            val savedAuthCookie = DouyinAuthStore.getCookie(application)
            val request = Request.Builder()
                .url(bootstrapUrl)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("User-Agent", mobileUserAgent)
                .header("Referer", buildAuthorShareUrl(secUserId))
                .apply {
                    savedAuthCookie
                        ?.takeIf { it.isNotBlank() }
                        ?.let { header("Cookie", it) }
                }
                .build()
            DouyinRequestLimiter.acquire(authorPageRequestIntervalMs())
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && DouyinRequestLimiter.isLikelyRiskStatus(response.code)) {
                    DouyinRequestLimiter.reportRiskFailure()
                } else {
                    DouyinRequestLimiter.reportSuccess()
                }
                extractMsToken(response)
            }
        }.onFailure { throwable ->
            Log.w("AuthorBatchManager", "Bootstrap msToken request failed", throwable)
        }.getOrNull()
    }

    private fun normalizeAuthorUrl(rawUrl: String, secUserId: String): String {
        return runCatching {
            val httpUrl = rawUrl.toHttpUrl()
            val isDouyinUserPage = (
                httpUrl.host.equals("douyin.com", ignoreCase = true) ||
                    httpUrl.host.equals("www.douyin.com", ignoreCase = true)
                ) &&
                httpUrl.encodedPath.contains("/user/")
            val isIesDouyinShareUserPage = isIesDouyinShareUserUrl(httpUrl)
            if (isDouyinUserPage) {
                httpUrl.toString()
            } else if (isIesDouyinShareUserPage) {
                httpUrl.toString()
            } else {
                buildAuthorShareUrl(secUserId)
            }
        }.getOrElse {
            buildAuthorShareUrl(secUserId)
        }
    }

    private fun shouldUseAuthenticatedBrowserProfile(resolvedInput: ResolvedAuthorInput): Boolean {
        return DouyinAuthStore.looksAuthenticated(resolvedInput.authCookie)
    }

    private fun buildBrowserCollectionProfileUrl(resolvedInput: ResolvedAuthorInput): String {
        return if (shouldUseAuthenticatedBrowserProfile(resolvedInput)) {
            buildDouyinAuthorProfileUrl(resolvedInput.secUserId)
        } else {
            resolvedInput.authorUrl.ifBlank { buildAuthorShareUrl(resolvedInput.secUserId) }
        }
    }

    private fun isIesDouyinShareUserUrl(rawUrl: String): Boolean {
        return runCatching { isIesDouyinShareUserUrl(rawUrl.toHttpUrl()) }.getOrDefault(false)
    }

    private fun isIesDouyinShareUserUrl(url: HttpUrl): Boolean {
        return url.host.equals("www.iesdouyin.com", ignoreCase = true) &&
            url.encodedPath.contains("/share/user/", ignoreCase = true)
    }

    private fun buildAuthorShareUrl(secUserId: String): String {
        return "https://www.iesdouyin.com/share/user/$secUserId?sec_uid=$secUserId&from_ssr=1"
    }

    private fun buildDouyinAuthorProfileUrl(secUserId: String): String {
        return "https://www.douyin.com/user/$secUserId"
    }

    private fun buildWorkShareUrl(videoId: String): String {
        return if (videoId.isBlank()) "" else String.format(NativeLib.getApiUrlTemplate(1), videoId)
    }

    private fun buildWorkPageUrl(videoId: String): String {
        return if (videoId.isBlank()) "" else "https://www.douyin.com/video/$videoId"
    }

}
