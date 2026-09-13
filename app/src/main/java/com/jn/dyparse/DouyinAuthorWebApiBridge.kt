package com.jn.dyparse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DouyinAuthorWebApiBridge(
    private val context: Context
) {
    data class BrowserAuthorCollectionResult(
        val awemeIds: List<String>,
        val isComplete: Boolean,
        val awemePageBodies: List<String> = emptyList(),
        val pageAwemeIds: List<String> = emptyList()
    )

    private inner class WebAppBridge {
        @JavascriptInterface
        fun onResult(payload: String?) {
            val callback = pendingResultCallback ?: return
            clearPendingCallbacks()
            callback(payload)
        }
    }

    private var webView: WebView? = null
    private var currentPageUrl: String? = null
    private var currentUserAgent: String? = null
    private var isPageVerified = false

    @Volatile
    private var pendingResultCallback: ((String?) -> Unit)? = null
    @Volatile
    private var pageLoadContinuation: CancellableContinuation<Unit>? = null
    private val observedAuthorPostRequests = CopyOnWriteArrayList<String>()

    companion object {
        private const val BRIDGE_NAME = "DyparseBridge"
        private const val FETCH_TIMEOUT_MS = 25_000L
        private const val PAGE_LOAD_TIMEOUT_MS = 25_000L
        private const val PAGE_READY_TIMEOUT_MS = 12_000L
        private const val PAGE_STABILIZE_TIMEOUT_MS = 18_000L
        private const val EVALUATE_JS_TIMEOUT_MS = 8_000L
        private const val PAGE_SETTLE_DELAY_MS = 1_200L
        private const val AUTHOR_RESOURCE_WAIT_ATTEMPTS = 24
        private const val AUTHOR_SCROLL_WAIT_MS = 1_000L
        private const val AUTHOR_BROWSER_COLLECT_WARMUP_ROUNDS = 6
        private const val AUTHOR_BROWSER_COLLECT_MAX_SCROLL_ROUNDS = 120
        private const val AUTHOR_BROWSER_COLLECT_IDLE_ROUNDS = 8
        private const val AUTHOR_BROWSER_COLLECT_SCROLL_DELAY_MS = 1_200L
        private const val WEB_VIEWPORT_WIDTH = 1080
        private const val WEB_VIEWPORT_HEIGHT = 1920

        private val mobileUserAgent by lazy { NativeLib.getParserUserAgent() }
        private const val desktopUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }

    suspend fun fetchAuthorPostPage(
        profileUrl: String,
        secUserId: String,
        cursor: Long,
        count: Int
    ): String {
        val activeWebView = ensureWebView()
        syncSavedAuthCookieToWebView()
        val capturedRequestUrl = prepareAuthorProfileAndFindPostRequest(
            activeWebView = activeWebView,
            profileUrl = profileUrl,
            secUserId = secUserId,
            cursor = cursor,
            requestedCount = count
        )
        val result = replayAbsoluteGetRequest(
            activeWebView = activeWebView,
            requestUrl = capturedRequestUrl
        )
        val payload = runCatching { JSONObject(result) }
            .getOrElse { throw IOException("Failed to decode author batch payload") }

        if (!payload.optBoolean("ok")) {
            throw IOException(payload.optString("error").ifBlank { "Author batch request failed" })
        }

        val body = payload.optString("body")
        if (body.isBlank()) {
            throw IOException("Author batch response is empty")
        }
        return body
    }

    suspend fun fetchLegacyAuthorPostPage(
        profileUrl: String,
        secUserId: String,
        cursor: Long,
        count: Int
    ): String {
        val activeWebView = ensureWebView()
        syncSavedAuthCookieToWebView()
        ensurePageReady(
            activeWebView = activeWebView,
            pageUrl = profileUrl,
            userAgent = resolveProfileUserAgent(profileUrl)
        )

        val requestUrl = buildLegacyAuthorPostRequestUrl(secUserId, cursor, count)
        val result = replayAbsoluteGetRequest(
            activeWebView = activeWebView,
            requestUrl = requestUrl
        )
        val payload = runCatching { JSONObject(result) }
            .getOrElse { throw IOException("Failed to decode legacy author batch payload") }

        if (!payload.optBoolean("ok")) {
            throw IOException(payload.optString("error").ifBlank { "Legacy author batch request failed" })
        }

        val body = payload.optString("body")
        if (body.isBlank()) {
            throw IOException("Legacy author batch response is empty")
        }
        return body
    }

    suspend fun collectAuthorWorkIds(
        profileUrl: String,
        secUserId: String,
        expectedCount: Int = 0,
        knownAwemeIds: Collection<String> = emptyList()
    ): BrowserAuthorCollectionResult {
        val activeWebView = ensureWebView()
        syncSavedAuthCookieToWebView()
        ensurePageReady(
            activeWebView = activeWebView,
            pageUrl = profileUrl,
            userAgent = resolveProfileUserAgent(profileUrl)
        )

        val mergedIds = linkedSetOf<String>().apply {
            knownAwemeIds.map { it.trim() }.filter { it.isNotBlank() }.forEach(::add)
        }
        val pageIds = linkedSetOf<String>()
        val handledRequestUrls = linkedSetOf<String>()
        val awemePageBodies = mutableListOf<String>()
        if (expectedCount > 0 && mergedIds.size >= expectedCount) {
            return BrowserAuthorCollectionResult(
                awemeIds = mergedIds.take(expectedCount),
                isComplete = true,
                awemePageBodies = awemePageBodies.toList(),
                pageAwemeIds = pageIds.toList()
            )
        }

        repeat(AUTHOR_BROWSER_COLLECT_WARMUP_ROUNDS) {
            mergeAuthorIdsFromPageSignals(
                activeWebView = activeWebView,
                secUserId = secUserId,
                handledRequestUrls = handledRequestUrls,
                mergedIds = mergedIds,
                pageIds = pageIds,
                awemePageBodies = awemePageBodies
            )
            if (expectedCount > 0 && mergedIds.size >= expectedCount) {
                return BrowserAuthorCollectionResult(
                    awemeIds = mergedIds.take(expectedCount),
                    isComplete = true,
                    awemePageBodies = awemePageBodies.toList(),
                    pageAwemeIds = pageIds.toList()
                )
            }
            delay(AUTHOR_SCROLL_WAIT_MS)
        }

        var stableRounds = 0
        for (scrollRound in 0 until AUTHOR_BROWSER_COLLECT_MAX_SCROLL_ROUNDS) {
            val beforeCount = mergedIds.size
            scrollAuthorFeedStep(activeWebView, scrollRound)
            delay(AUTHOR_BROWSER_COLLECT_SCROLL_DELAY_MS)
            mergeAuthorIdsFromPageSignals(
                activeWebView = activeWebView,
                secUserId = secUserId,
                handledRequestUrls = handledRequestUrls,
                mergedIds = mergedIds,
                pageIds = pageIds,
                awemePageBodies = awemePageBodies
            )

            if (expectedCount > 0 && mergedIds.size >= expectedCount) {
                return BrowserAuthorCollectionResult(
                    awemeIds = mergedIds.take(expectedCount),
                    isComplete = true,
                    awemePageBodies = awemePageBodies.toList(),
                    pageAwemeIds = pageIds.toList()
                )
            }

            stableRounds = if (mergedIds.size == beforeCount) {
                stableRounds + 1
            } else {
                0
            }
            if (stableRounds >= AUTHOR_BROWSER_COLLECT_IDLE_ROUNDS) {
                break
            }
            if (scrollRound == AUTHOR_BROWSER_COLLECT_MAX_SCROLL_ROUNDS - 1) {
                break
            }
        }

        mergeAuthorIdsFromPageSignals(
            activeWebView = activeWebView,
            secUserId = secUserId,
            handledRequestUrls = handledRequestUrls,
            mergedIds = mergedIds,
            pageIds = pageIds,
            awemePageBodies = awemePageBodies
        )
        return BrowserAuthorCollectionResult(
            awemeIds = if (expectedCount > 0) mergedIds.take(expectedCount) else mergedIds.toList(),
            isComplete = expectedCount > 0 && mergedIds.size >= expectedCount || expectedCount <= 0,
            awemePageBodies = awemePageBodies.toList(),
            pageAwemeIds = pageIds.toList()
        )
    }

    suspend fun fetchAwemeDetail(awemeId: String): String {
        val activeWebView = ensureWebView()
        syncSavedAuthCookieToWebView()
        ensurePageReady(
            activeWebView = activeWebView,
            pageUrl = "https://www.douyin.com/video/$awemeId",
            userAgent = desktopUserAgent
        )

        val result = runFetchRequest(
            activeWebView = activeWebView,
            path = "/aweme/v1/web/aweme/detail/",
            params = mapOf(
                "device_platform" to "webapp",
                "aid" to "6383",
                "channel" to "channel_pc_web",
                "aweme_id" to awemeId
            )
        )
        val payload = runCatching { JSONObject(result) }
            .getOrElse { throw IOException("Failed to decode aweme detail payload") }

        if (!payload.optBoolean("ok")) {
            throw IOException(payload.optString("error").ifBlank { "Aweme detail request failed" })
        }

        val body = payload.optString("body")
        if (body.isBlank()) {
            throw IOException("Aweme detail response is empty")
        }
        return body
    }

    suspend fun warmUpDouyinCookies(): String {
        val activeWebView = ensureWebView()
        syncSavedAuthCookieToWebView()
        DouyinRequestLimiter.acquire(workRequestIntervalMs())
        runCatching {
            ensurePageReady(
                activeWebView = activeWebView,
                pageUrl = "https://www.douyin.com/",
                userAgent = desktopUserAgent
            )
        }.onFailure {
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    configureUserAgent(activeWebView, desktopUserAgent)
                    activeWebView.stopLoading()
                    activeWebView.loadUrl("https://www.douyin.com/")
                }
                delay(3_500L)
            }
        }

        return withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().flush()
            listOf(
                "https://www.douyin.com/",
                "https://douyin.com/",
                "https://www.iesdouyin.com/"
            )
                .flatMap { url ->
                    CookieManager.getInstance()
                        .getCookie(url)
                        .orEmpty()
                        .split(";")
                }
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains("=") }
                .distinctBy { it.substringBefore("=").trim() }
                .joinToString("; ")
                .also { DouyinRequestLimiter.reportSuccess() }
        }
    }

    suspend fun destroy() {
        withContext(Dispatchers.Main.immediate) {
            webView?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            clearPendingCallbacks()
            resetPageState()
            webView = null
        }
    }

    private suspend fun syncSavedAuthCookieToWebView() {
        val savedCookie = DouyinAuthStore.getCookie(context).orEmpty()
        if (savedCookie.isBlank()) {
            return
        }
        withContext(Dispatchers.Main.immediate) {
            DouyinCookieWebViewSync.applyCookieHeader(savedCookie)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main.immediate) {
        webView ?: WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = desktopUserAgent
            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
            addJavascriptInterface(WebAppBridge(), BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    val continuation = pageLoadContinuation
                    if (continuation?.isActive == true) {
                        pageLoadContinuation = null
                        continuation.resume(Unit)
                    }
                    super.onPageFinished(view, finishedUrl)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.url?.toString()
                        ?.takeIf(::isAuthorPostRequestUrl)
                        ?.let { observedAuthorPostRequests.addIfAbsent(it) }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            webView = this
            layoutOffscreenWebView(this)
        }
    }

    private suspend fun ensurePageReady(
        activeWebView: WebView,
        pageUrl: String,
        userAgent: String
    ) {
        val shouldReload = currentPageUrl != pageUrl || currentUserAgent != userAgent || !isPageVerified
        if (!shouldReload) {
            val readyState = runCatching {
                evaluateJavascript(activeWebView, "document.readyState")
            }.getOrNull()
            if (readyState == "complete") {
                return
            }
        }

        configureUserAgent(activeWebView, userAgent)
        loadStablePage(activeWebView, pageUrl)
        currentPageUrl = pageUrl
        currentUserAgent = userAgent
        isPageVerified = true
    }

    private suspend fun configureUserAgent(activeWebView: WebView, userAgent: String) {
        withContext(Dispatchers.Main.immediate) {
            if (activeWebView.settings.userAgentString != userAgent) {
                activeWebView.settings.userAgentString = userAgent
            }
        }
    }

    private suspend fun prepareAuthorProfileAndFindPostRequest(
        activeWebView: WebView,
        profileUrl: String,
        secUserId: String,
        cursor: Long,
        requestedCount: Int
    ): String {
        val profileUserAgent = if (isIesDouyinShareUserUrl(profileUrl)) {
            mobileUserAgent
        } else {
            desktopUserAgent
        }
        configureUserAgent(activeWebView, profileUserAgent)

        val shouldReload = currentPageUrl != profileUrl || currentUserAgent != profileUserAgent || cursor == 0L
        if (shouldReload) {
            loadUrlForRequestCapture(activeWebView, profileUrl)
            currentPageUrl = profileUrl
            currentUserAgent = profileUserAgent
            isPageVerified = false
        }

        return waitForAuthorPostRequestUrl(
            activeWebView = activeWebView,
            secUserId = secUserId,
            cursor = cursor,
            requestedCount = requestedCount
        )
    }

    private suspend fun loadUrlForRequestCapture(activeWebView: WebView, url: String) {
        withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().flush()
            observedAuthorPostRequests.clear()
            pageLoadContinuation = null
            layoutOffscreenWebView(activeWebView)
            activeWebView.stopLoading()
            activeWebView.loadUrl(url)
        }
    }

    private fun layoutOffscreenWebView(activeWebView: WebView) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(WEB_VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(WEB_VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
        activeWebView.measure(widthSpec, heightSpec)
        activeWebView.layout(0, 0, WEB_VIEWPORT_WIDTH, WEB_VIEWPORT_HEIGHT)
    }

    private suspend fun loadStablePage(activeWebView: WebView, url: String) {
        try {
            loadUrlAndAwait(activeWebView, url)
            withTimeout(PAGE_STABILIZE_TIMEOUT_MS) {
                while (true) {
                    val readyState = runCatching {
                        evaluateJavascript(activeWebView, "document.readyState")
                    }.getOrNull()
                    val html = runCatching { getOuterHtml(activeWebView) }.getOrNull().orEmpty()

                    if (readyState == "complete" && html.isNotBlank() && !isAntiBotPage(html)) {
                        return@withTimeout
                    }

                    delay(PAGE_SETTLE_DELAY_MS)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            resetPageState()
            throw IOException("Douyin page challenge did not complete", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            resetPageState()
            throw IOException("Douyin page challenge did not complete")
        }
    }

    private suspend fun waitForDocumentReady(activeWebView: WebView) {
        withTimeout(PAGE_READY_TIMEOUT_MS) {
            while (true) {
                val readyState = runCatching {
                    evaluateJavascript(activeWebView, "document.readyState")
                }.getOrNull()

                if (readyState == "complete") {
                    return@withTimeout
                }

                delay(250L)
            }
        }
    }

    private suspend fun loadUrlAndAwait(activeWebView: WebView, url: String) {
        withTimeout(PAGE_LOAD_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                CookieManager.getInstance().flush()
                suspendCancellableCoroutine<Unit> { continuation ->
                    observedAuthorPostRequests.clear()
                    pageLoadContinuation = continuation

                    continuation.invokeOnCancellation {
                        if (pageLoadContinuation === continuation) {
                            pageLoadContinuation = null
                        }
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            activeWebView.stopLoading()
                        } else {
                            activeWebView.post { activeWebView.stopLoading() }
                        }
                    }

                    activeWebView.stopLoading()
                    activeWebView.loadUrl(url)
                }
            }
        }
    }

    private suspend fun getOuterHtml(activeWebView: WebView): String {
        return evaluateJavascript(
            activeWebView,
            "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()"
        )
    }

    private suspend fun evaluateJavascript(activeWebView: WebView, script: String): String {
        return withTimeout(EVALUATE_JS_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    activeWebView.evaluateJavascript(script) { value ->
                        if (!continuation.isActive) {
                            return@evaluateJavascript
                        }

                        val decoded = decodeEvaluateJavascriptValue(value)
                        if (decoded == null) {
                            continuation.resumeWithException(IOException("Failed to evaluate author batch script"))
                        } else {
                            continuation.resume(decoded)
                        }
                    }
                }
            }
        }
    }

    private suspend fun runFetchRequest(
        activeWebView: WebView,
        path: String,
        params: Map<String, String>
    ): String {
        val pathLiteral = JSONObject.quote(path)
        val paramsLiteral = JSONObject(params).toString()
        val script = """
            (function() {
              try {
                const params = new URLSearchParams($paramsLiteral);
                fetch($pathLiteral + '?' + params.toString(), {
                  method: 'GET',
                  credentials: 'include'
                }).then(async function(response) {
                  const body = await response.text();
                  $BRIDGE_NAME.onResult(JSON.stringify({
                    ok: response.ok,
                    status: response.status,
                    body: body,
                    error: response.ok ? '' : ('HTTP ' + response.status)
                  }));
                }).catch(function(error) {
                  $BRIDGE_NAME.onResult(JSON.stringify({
                    ok: false,
                    error: String(error)
                  }));
                });
              } catch (error) {
                $BRIDGE_NAME.onResult(JSON.stringify({
                  ok: false,
                  error: String(error)
                }));
              }
            })();
        """.trimIndent()

        DouyinRequestLimiter.acquire(workRequestIntervalMs())
        val result = withTimeout(FETCH_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                pendingResultCallback = callback@{ payload ->
                    if (!continuation.isActive) {
                        return@callback
                    }

                    val decoded = payload?.takeIf { it.isNotBlank() }
                    if (decoded == null) {
                        continuation.resumeWithException(IOException("Author batch request failed"))
                    } else {
                        continuation.resume(decoded)
                    }
                }

                continuation.invokeOnCancellation {
                    clearPendingCallbacks()
                }

                activeWebView.post {
                    if (!continuation.isActive) {
                        return@post
                    }
                    activeWebView.evaluateJavascript(script, null)
                }
            }
        }
        recordBridgeResult(result)
        return result
    }

    private suspend fun waitForAuthorPostRequestUrl(
        activeWebView: WebView,
        secUserId: String,
        cursor: Long,
        requestedCount: Int
    ): String {
        repeat(AUTHOR_RESOURCE_WAIT_ATTEMPTS) { attempt ->
            observedAuthorPostRequests
                .firstOrNull { matchesAuthorCursor(it, secUserId, cursor) }
                ?.let { return it }

            val requestUrls = runCatching {
                getAuthorPostRequestUrls(activeWebView, secUserId)
            }.getOrDefault(emptyList())
            requestUrls.firstOrNull { matchesAuthorCursor(it, secUserId, cursor) }?.let { matchedUrl ->
                return matchedUrl
            }

            if (attempt > 0 || cursor > 0 || requestedCount > 15) {
                if (cursor > 0) {
                    scrollAuthorFeedStep(activeWebView, attempt)
                } else {
                    scrollToAuthorFeedBottom(activeWebView)
                }
            }
            delay(AUTHOR_SCROLL_WAIT_MS)
        }

        throw IOException("Author post request URL not found")
    }

    private suspend fun mergeAuthorIdsFromPageSignals(
        activeWebView: WebView,
        secUserId: String,
        handledRequestUrls: MutableSet<String>,
        mergedIds: MutableSet<String>,
        pageIds: MutableSet<String>,
        awemePageBodies: MutableList<String>
    ) {
        val requestUrls = buildList {
            addAll(observedAuthorPostRequests.filter { matchesAuthorSecUserId(it, secUserId) })
            addAll(getAuthorPostRequestUrls(activeWebView, secUserId))
        }.distinct()

        requestUrls.forEach { requestUrl ->
            if (!handledRequestUrls.add(requestUrl)) {
                return@forEach
            }
            val body = runCatching {
                replayAbsoluteGetRequest(activeWebView, requestUrl)
            }.getOrNull() ?: return@forEach
            extractAuthorBody(body)
                .takeIf { it.contains("\"aweme_list\"", ignoreCase = true) }
                ?.let { authorBody ->
                    if (awemePageBodies.none { it == authorBody }) {
                        awemePageBodies.add(authorBody)
                    }
            }
            extractAwemeIdsFromAuthorBody(body).forEach { mergedIds.add(it) }
        }

        extractAwemeIdsFromPage(activeWebView).forEach { pageIds.add(it) }
    }

    private suspend fun getAuthorPostRequestUrls(
        activeWebView: WebView,
        secUserId: String
    ): List<String> {
        val secUserIdLiteral = JSONObject.quote(secUserId)
        val script = """
            (function() {
              const secUid = $secUserIdLiteral;
              const resources = performance.getEntriesByType('resource')
                .map(function(entry) { return entry.name || ''; })
                .filter(function(url) {
                  const encodedSecUid = encodeURIComponent(secUid);
                  const isPostApi = url.indexOf('/web/api/v2/aweme/post/') !== -1 ||
                    url.indexOf('/aweme/v1/web/aweme/post/') !== -1;
                  const hasSecUid = url.indexOf('sec_uid=' + encodedSecUid) !== -1 ||
                    url.indexOf('sec_user_id=' + encodedSecUid) !== -1;
                  return isPostApi && hasSecUid;
                });
              return JSON.stringify(resources);
            })()
        """.trimIndent()

        val rawResult = evaluateJavascript(activeWebView, script)
        val jsonArray = runCatching { org.json.JSONArray(rawResult) }.getOrNull()
            ?: return emptyList()

        return buildList(jsonArray.length()) {
            for (index in 0 until jsonArray.length()) {
                val value = jsonArray.optString(index)
                if (!value.isNullOrBlank()) {
                    add(value)
                }
            }
        }.distinct()
    }

    private suspend fun extractAwemeIdsFromPage(activeWebView: WebView): List<String> {
        val script = """
            (function() {
              const result = [];
              const seen = new Set();
              const push = function(id) {
                if (!id || seen.has(id)) return;
                seen.add(id);
                result.push(id);
              };
              const collectFrom = function(text, pattern) {
                if (!text) return;
                pattern.lastIndex = 0;
                let match;
                while ((match = pattern.exec(text)) !== null) {
                  push(match[1]);
                }
              };
              const links = document.querySelectorAll('a[href]');
              for (const node of links) {
                const href = node.getAttribute('href') || '';
                collectFrom(href, /\/video\/(\d{15,20})/g);
                collectFrom(href, /\/note\/(\d{15,20})/g);
              }
              const html = document.documentElement ? document.documentElement.innerHTML : '';
              collectFrom(html, /"aweme_id":"(\d{15,20})"/g);
              collectFrom(html, /"group_id":"(\d{15,20})"/g);
              return JSON.stringify(result);
            })()
        """.trimIndent()

        val rawResult = runCatching {
            evaluateJavascript(activeWebView, script)
        }.getOrNull() ?: return emptyList()
        val jsonArray = runCatching { org.json.JSONArray(rawResult) }.getOrNull()
            ?: return emptyList()
        return buildList(jsonArray.length()) {
            for (index in 0 until jsonArray.length()) {
                jsonArray.optString(index)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun extractAwemeIdsFromAuthorBody(payload: String): List<String> {
        val body = extractAuthorBody(payload)
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val awemeList = json.optJSONArray("aweme_list") ?: return emptyList()
        return buildList(awemeList.length()) {
            for (index in 0 until awemeList.length()) {
                awemeList.optJSONObject(index)
                    ?.optString("aweme_id")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun extractAuthorBody(payload: String): String {
        return runCatching {
            val root = JSONObject(payload)
            root.optString("body").takeIf { it.isNotBlank() } ?: payload
        }.getOrDefault(payload)
    }

    private fun matchesAuthorCursor(url: String, secUserId: String, cursor: Long): Boolean {
        val queryParameters = authorPostQueryParameters(url) ?: return false
        val resourceSecUid = queryParameters["sec_uid"]
            ?: queryParameters["sec_user_id"]
            ?: return false
        val resourceCursor = queryParameters["max_cursor"]?.toLongOrNull() ?: 0L
        return resourceSecUid == secUserId && resourceCursor == cursor
    }

    private fun matchesAuthorSecUserId(url: String, secUserId: String): Boolean {
        val queryParameters = authorPostQueryParameters(url) ?: return false
        val resourceSecUid = queryParameters["sec_uid"]
            ?: queryParameters["sec_user_id"]
            ?: return false
        return resourceSecUid == secUserId
    }

    private fun authorPostQueryParameters(url: String): Map<String, String>? {
        val query = url.substringAfter("?", missingDelimiterValue = "")
            .substringBefore("#")
        if (query.isBlank()) {
            return null
        }
        return query.split("&")
            .mapNotNull { part ->
                val separatorIndex = part.indexOf('=')
                if (separatorIndex <= 0) {
                    return@mapNotNull null
                }
                val key = java.net.URLDecoder.decode(part.substring(0, separatorIndex), Charsets.UTF_8.name())
                val value = java.net.URLDecoder.decode(part.substring(separatorIndex + 1), Charsets.UTF_8.name())
                key to value
            }
            .toMap()
    }

    private fun isAuthorPostRequestUrl(url: String): Boolean {
        return url.contains("/web/api/v2/aweme/post/", ignoreCase = true) ||
            url.contains("/aweme/v1/web/aweme/post/", ignoreCase = true)
    }

    private fun isIesDouyinShareUserUrl(url: String): Boolean {
        return url.contains("://www.iesdouyin.com/share/user/", ignoreCase = true)
    }

    private fun resolveProfileUserAgent(profileUrl: String): String {
        return if (isIesDouyinShareUserUrl(profileUrl)) {
            mobileUserAgent
        } else {
            desktopUserAgent
        }
    }

    private fun buildLegacyAuthorPostRequestUrl(
        secUserId: String,
        cursor: Long,
        count: Int
    ): String {
        return "https://www.iesdouyin.com/web/api/v2/aweme/post/" +
            "?reflow_source=reflow_page" +
            "&aid=1128" +
            "&sec_uid=${java.net.URLEncoder.encode(secUserId, Charsets.UTF_8.name())}" +
            "&max_cursor=$cursor" +
            "&count=$count"
    }

    private suspend fun scrollToAuthorFeedBottom(activeWebView: WebView) {
        val script = """
            (function() {
              const target = Math.max(
                document.body ? document.body.scrollHeight : 0,
                document.documentElement ? document.documentElement.scrollHeight : 0
              );
              window.scrollTo(0, target);
              return String(target);
            })()
        """.trimIndent()
        runCatching { evaluateJavascript(activeWebView, script) }
    }

    private suspend fun scrollAuthorFeedStep(activeWebView: WebView, scrollRound: Int) {
        val script = """
            (function() {
              const currentY = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
              const viewport = window.innerHeight || 900;
              const delta = Math.max(Math.floor(viewport * 1.8), 2200);
              const target = currentY + delta;
              window.scrollTo(0, target);
              return String(target);
            })()
        """.trimIndent()
        runCatching { evaluateJavascript(activeWebView, script) }
        if (scrollRound % 6 == 5) {
            scrollToAuthorFeedBottom(activeWebView)
        }
    }

    private suspend fun replayAbsoluteGetRequest(
        activeWebView: WebView,
        requestUrl: String
    ): String {
        val requestUrlLiteral = JSONObject.quote(requestUrl)
        val script = """
            (function() {
              try {
                fetch($requestUrlLiteral, {
                  method: 'GET',
                  credentials: 'include'
                }).then(async function(response) {
                  const body = await response.text();
                  $BRIDGE_NAME.onResult(JSON.stringify({
                    ok: response.ok,
                    status: response.status,
                    body: body,
                    error: response.ok ? '' : ('HTTP ' + response.status)
                  }));
                }).catch(function(error) {
                  $BRIDGE_NAME.onResult(JSON.stringify({
                    ok: false,
                    error: String(error)
                  }));
                });
              } catch (error) {
                $BRIDGE_NAME.onResult(JSON.stringify({
                  ok: false,
                  error: String(error)
                }));
              }
            })();
        """.trimIndent()

        DouyinRequestLimiter.acquire(authorPageRequestIntervalMs())
        val result = withTimeout(FETCH_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                pendingResultCallback = callback@{ payload ->
                    if (!continuation.isActive) {
                        return@callback
                    }

                    val decoded = payload?.takeIf { it.isNotBlank() }
                    if (decoded == null) {
                        continuation.resumeWithException(IOException("Author batch request failed"))
                    } else {
                        continuation.resume(decoded)
                    }
                }

                continuation.invokeOnCancellation {
                    clearPendingCallbacks()
                }

                activeWebView.post {
                    if (!continuation.isActive) {
                        return@post
                    }
                    activeWebView.evaluateJavascript(script, null)
                }
            }
        }
        recordBridgeResult(result)
        return result
    }

    private suspend fun recordBridgeResult(payload: String) {
        val root = runCatching { JSONObject(payload) }.getOrNull()
        if (root == null) {
            DouyinRequestLimiter.reportSuccess()
            return
        }

        val status = root.optInt("status", 200)
        val body = root.optString("body")
        val error = root.optString("error")
        if (DouyinRequestLimiter.isLikelyRiskStatus(status) ||
            DouyinRequestLimiter.isLikelyRiskBody(body) ||
            DouyinRequestLimiter.isLikelyRiskBody(error)
        ) {
            DouyinRequestLimiter.reportRiskFailure()
        } else {
            DouyinRequestLimiter.reportSuccess()
        }
    }

    private fun workRequestIntervalMs(): Long {
        return BatchParsePreferences.getSettings(context).workIntervalMs.toLong()
    }

    private fun authorPageRequestIntervalMs(): Long {
        return BatchParsePreferences.getSettings(context).authorPageIntervalMs.toLong()
    }

    private fun isAntiBotPage(html: String): Boolean {
        val normalizedHtml = html.lowercase()
        val hasReload = normalizedHtml.contains("window.location.reload()")
        val hasSignatureScript = normalizedHtml.contains("__ac_signature")
        val hasChallengeRuntime = normalizedHtml.contains("window.byted_acrawler.init({aid:99999999")
        return hasReload && hasSignatureScript && hasChallengeRuntime
    }

    private fun clearPendingCallbacks() {
        pendingResultCallback = null
    }

    private fun resetPageState() {
        currentPageUrl = null
        currentUserAgent = null
        isPageVerified = false
    }

    private fun decodeEvaluateJavascriptValue(value: String?): String? {
        if (value == null || value == "null") {
            return null
        }

        return runCatching {
            JSONObject("{\"value\":$value}").getString("value")
        }.getOrNull()
    }
}
