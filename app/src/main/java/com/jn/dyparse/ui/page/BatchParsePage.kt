package com.jn.dyparse.ui.page

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jn.dyparse.BatchDisplayPreferences
import com.jn.dyparse.DouyinAuthStore
import com.jn.dyparse.ParserViewModel
import com.jn.dyparse.VideoPlayer
import com.jn.dyparse.copyPlainText
import com.jn.dyparse.data.BatchAuthorParseSummary
import com.jn.dyparse.data.BatchParseResult
import com.jn.dyparse.data.GalleryMedia
import com.jn.dyparse.data.ParseResult
import com.jn.dyparse.data.galleryImageCount
import com.jn.dyparse.data.galleryItems
import com.jn.dyparse.data.livePhotoCount
import com.jn.dyparse.data.totalMediaAssetCount
import com.jn.dyparse.ui.LivePhotoPreviewDialog
import com.jn.dyparse.ui.theme.MiuixPrimaryButton
import com.jn.dyparse.ui.theme.MiuixSurface
import com.jn.dyparse.ui.theme.MiuixTextField
import com.jn.dyparse.ui.theme.floatingBottomBarContentPadding
import com.jn.dyparse.rememberIsResumed
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AuthorCaptureRequest(
    val input: String,
    val countInput: String,
    val positionInput: String,
    val key: Long = System.nanoTime()
)

private data class CapturedAuthorWorks(
    val awemeIds: List<String>,
    val author: String?,
    val authorUid: String?,
    val authorSecUid: String?,
    val avatarUrl: String?,
    val isComplete: Boolean
)

private class AuthorCaptureBridge(
    private val onBody: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onBody(body: String?) {
        val payload = body.orEmpty()
        mainHandler.post { onBody(payload) }
    }

    @JavascriptInterface
    fun onError(error: String?) {
        val message = error.orEmpty()
        mainHandler.post { onError(message) }
    }
}

@Composable
fun BatchParsePage(viewModel: ParserViewModel = viewModel()) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var countInput by rememberSaveable { mutableStateOf("") }
    var positionInput by rememberSaveable { mutableStateOf("") }
    var captureRequest by remember { mutableStateOf<AuthorCaptureRequest?>(null) }
    val batchParseResult by viewModel.batchParseResult
    val saveState by viewModel.saveState
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val isResumed by rememberIsResumed()
    val authRevision by DouyinAuthStore.authRevision.collectAsState()

    val isParsing = batchParseResult is BatchParseResult.Loading
    val isCapturing = captureRequest != null
    val isBusy = isParsing || saveState.isSaving || isCapturing

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "\u4f5c\u8005\u4e3b\u9875\u6279\u91cf\u89e3\u6790",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            MiuixSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    MiuixTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("\u8bf7\u8f93\u5165\u4f5c\u8005\u4e3b\u9875\u94fe\u63a5\u6216\u5206\u4eab\u53e3\u4ee4") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy,
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    clipboardManager.getText()?.let { clipText ->
                                        input = clipText.text
                                    }
                                },
                                enabled = !isBusy
                            ) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MiuixTextField(
                            value = countInput,
                            onValueChange = { countInput = it.filter(Char::isDigit) },
                            label = { Text("\u89e3\u6790\u6570\u91cf") },
                            modifier = Modifier.weight(1f),
                            enabled = !isBusy,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        MiuixTextField(
                            value = positionInput,
                            onValueChange = { newValue ->
                                positionInput = newValue.filter { it.isDigit() || it == '~' || it == '-' || it == '\uff5e' || it.isWhitespace() }
                            },
                            label = { Text("\u6307\u5b9a\u6392\u5e8f") },
                            modifier = Modifier.weight(1f),
                            enabled = !isBusy,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "\u4e0d\u586b\u6570\u91cf\u9ed8\u8ba4\u89e3\u6790\u5168\u90e8\uff1b\u6307\u5b9a\u6392\u5e8f\u4f8b\u5982 10 \u6216 10~11",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = {
                        input = ""
                        countInput = ""
                        positionInput = ""
                    },
                    enabled = !isBusy && (input.isNotBlank() || countInput.isNotBlank() || positionInput.isNotBlank())
                ) {
                    Text("\u6e05\u7a7a")
                }
                MiuixPrimaryButton(
                    onClick = {
                        if (shouldCaptureAuthorPageInWebView(input)) {
                            captureRequest = AuthorCaptureRequest(input.trim(), countInput.trim(), positionInput.trim())
                        } else {
                            viewModel.parseAuthorBatch(input, countInput, positionInput)
                        }
                    },
                    enabled = input.isNotBlank() && !isBusy
                ) {
                    Text(if (isParsing || isCapturing) "\u89e3\u6790\u4e2d..." else "\u5f00\u59cb\u6279\u91cf\u89e3\u6790")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = batchParseResult) {
                    is BatchParseResult.Idle -> {
                        BatchHintCard()
                    }

                    is BatchParseResult.Loading -> {
                        if (state.summary != null && state.works.isNotEmpty()) {
                            BatchResultScreen(
                                viewModel = viewModel,
                                summary = state.summary,
                                works = state.works,
                                progressState = state
                            )
                        } else {
                            BatchLoadingCard(state)
                        }
                    }

                    is BatchParseResult.Error -> {
                        BatchErrorCard(state.msg)
                    }

                    is BatchParseResult.Success -> {
                        BatchResultScreen(
                            viewModel = viewModel,
                            summary = state.summary,
                            works = state.works
                        )
                    }
                }
            }
        }

        if (!isResumed) {
            Box(modifier = Modifier.fillMaxSize())
        }

        captureRequest?.let { request ->
            AuthorWebCaptureDialog(
                request = request,
                requestedCount = resolveAuthorCaptureRequestedCount(request.countInput, request.positionInput),
                onCaptured = { captured ->
                    captureRequest = null
                    viewModel.parseAuthorBatchFromCapturedIds(
                        text = request.input,
                        countInput = request.countInput,
                        positionInput = request.positionInput,
                        awemeIds = captured.awemeIds,
                        author = captured.author,
                        authorUid = captured.authorUid,
                        authorSecUid = captured.authorSecUid,
                        avatarUrl = captured.avatarUrl,
                        isComplete = captured.isComplete
                    )
                },
                onFallbackToNormalParse = {
                    captureRequest = null
                    viewModel.parseAuthorBatch(request.input, request.countInput, request.positionInput)
                },
                onCancel = { captureRequest = null },
                viewModel = viewModel
            )
        }
    }
}

private fun resolveAuthorCaptureRequestedCount(countInput: String, positionInput: String): Int? {
    val normalizedPosition = positionInput.trim()
    if (normalizedPosition.isNotBlank()) {
        val rangeMatch = Regex("^(\\d+)\\s*[~\\-\uFF5E]\\s*(\\d+)$")
            .matchEntire(normalizedPosition)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toIntOrNull()
            val end = rangeMatch.groupValues[2].toIntOrNull()
            if (start != null && end != null && start > 0 && end > 0 && start <= end) {
                return end
            }
        }

        normalizedPosition.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    }

    return countInput.trim().toIntOrNull()?.takeIf { it > 0 }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AuthorWebCaptureDialog(
    request: AuthorCaptureRequest,
    requestedCount: Int?,
    onCaptured: (CapturedAuthorWorks) -> Unit,
    onFallbackToNormalParse: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ParserViewModel
) {
    val context = LocalContext.current
    val awemeIds = remember(request.key) { linkedSetOf<String>() }
    val capturedUrls = remember(request.key) { linkedSetOf<String>() }
    var author by remember(request.key) { mutableStateOf<String?>(null) }
    var authorUid by remember(request.key) { mutableStateOf<String?>(null) }
    var authorSecUid by remember(request.key) { mutableStateOf<String?>(null) }
    var avatarUrl by remember(request.key) { mutableStateOf<String?>(null) }
    var capturedCount by remember(request.key) { mutableStateOf(0) }
    var statusText by remember(request.key) { mutableStateOf("\u6b63\u5728\u6253\u5f00\u4f5c\u8005\u4e3b\u9875...") }
    var pageUrl by remember(request.key) { mutableStateOf<String?>(null) }
    var lastHasMore by remember(request.key) { mutableStateOf(true) }
    var completed by remember(request.key) { mutableStateOf(false) }
    var webViewRef by remember(request.key) { mutableStateOf<WebView?>(null) }

    LaunchedEffect(request.key) {
        statusText = "\u6b63\u5728\u89e3\u6790\u4f5c\u8005\u4e3b\u9875\u771f\u5b9e\u5730\u5740..."
        pageUrl = runCatching {
            viewModel.resolveAuthorCaptureUrl(request.input)
        }.getOrElse {
            extractFirstUrl(request.input) ?: request.input
        }
        statusText = "\u6b63\u5728\u6253\u5f00\u4f5c\u8005\u4e3b\u9875..."
    }

    fun finishCapture(isComplete: Boolean) {
        if (completed || awemeIds.isEmpty()) {
            return
        }
        completed = true
        onCaptured(
            CapturedAuthorWorks(
                awemeIds = awemeIds.toList(),
                author = author,
                authorUid = authorUid,
                authorSecUid = authorSecUid,
                avatarUrl = avatarUrl,
                isComplete = isComplete
            )
        )
    }

    fun scheduleAutoScroll(view: WebView?, attempt: Int = 0) {
        view ?: return
        if (completed || attempt > 180) {
            return
        }
        view.postDelayed(
            {
                if (!completed) {
                    autoScrollAuthorPage(view)
                    if (attempt == 45 && capturedCount == 0) {
                        statusText = "\u8fd8\u6ca1\u6355\u83b7\u5230\u5217\u8868\u63a5\u53e3\uff0c\u5982\u9875\u9762\u51fa\u73b0\u9a8c\u8bc1\u6216\u6253\u5f00\u6309\u94ae\uff0c\u8bf7\u5728\u4e0b\u65b9\u9875\u9762\u5185\u64cd\u4f5c"
                    }
                    scheduleAutoScroll(view, attempt + 1)
                }
            },
            1_000L
        )
    }

    fun handleCapturedBody(body: String) {
        val parsed = parseAuthorPostBody(body)
        if (parsed.awemeIds.isNotEmpty()) {
            awemeIds.addAll(parsed.awemeIds)
            capturedCount = awemeIds.size
            author = author ?: parsed.author
            authorUid = authorUid ?: parsed.authorUid
            authorSecUid = authorSecUid ?: parsed.authorSecUid
            avatarUrl = avatarUrl ?: parsed.avatarUrl
            lastHasMore = parsed.hasMore
            statusText = "\u5df2\u6355\u83b7\u4f5c\u54c1\u5217\u8868\uff0c\u6b63\u5728\u7ee7\u7eed\u6eda\u52a8..."

            val reachedRequestedCount = requestedCount != null && capturedCount >= requestedCount
            if (reachedRequestedCount || !parsed.hasMore) {
                finishCapture(isComplete = !parsed.hasMore)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("\u6b63\u5728\u81ea\u52a8\u83b7\u53d6\u4f5c\u54c1 ID") },
        text = {
            Column {
                Text(
                    text = "$statusText\n\u5df2\u83b7\u53d6 $capturedCount \u4e2a\u4f5c\u54c1 ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (pageUrl == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.loadsImagesAutomatically = false
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.userAgentString = buildDesktopUserAgentForWebView(ctx)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            addJavascriptInterface(
                                AuthorCaptureBridge(
                                    onBody = ::handleCapturedBody,
                                    onError = { error ->
                                        if (capturedCount == 0 && error.isNotBlank()) {
                                            statusText = "\u5217\u8868\u63a5\u53e3\u590d\u8bf7\u6c42\u5931\u8d25: ${error.take(80)}"
                                        }
                                    }
                                ),
                                "DyAuthorCapture"
                            )
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    webRequest: WebResourceRequest?
                                ): android.webkit.WebResourceResponse? {
                                    val url = webRequest?.url?.toString().orEmpty()
                                    if (!isAuthorPostApiUrl(url) || webRequest == null) {
                                        return super.shouldInterceptRequest(view, webRequest)
                                    }

                                    synchronized(capturedUrls) {
                                        if (capturedUrls.add(url)) {
                                            view?.post {
                                                statusText = "\u5df2\u53d1\u73b0\u4f5c\u54c1\u5217\u8868\u63a5\u53e3\uff0c\u6b63\u5728\u8bfb\u53d6\u8fd4\u56de\u6570\u636e..."
                                                fetchAuthorPostBodyInsideWebView(view, url)
                                            }
                                        }
                                    }
                                    return super.shouldInterceptRequest(view, webRequest)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    statusText = "\u9875\u9762\u5df2\u6253\u5f00\uff0c\u6b63\u5728\u7b49\u5f85\u4f5c\u54c1\u5217\u8868\u63a5\u53e3..."
                                    scheduleAutoScroll(view)
                                }
                            }
                            loadUrl(pageUrl!!)
                        }
                    },
                        update = { view ->
                            webViewRef = view
                            if (view.url != pageUrl) {
                                view.loadUrl(pageUrl!!)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { finishCapture(isComplete = !lastHasMore) },
                enabled = capturedCount > 0
            ) {
                Text("\u7528\u5df2\u83b7\u53d6\u7684 $capturedCount \u6761\u5f00\u59cb")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onFallbackToNormalParse) {
                    Text("\u6539\u7528\u540e\u53f0\u6293\u53d6")
                }
                TextButton(
                    onClick = {
                        webViewRef?.stopLoading()
                        onCancel()
                    }
                ) {
                    Text("\u53d6\u6d88")
                }
            }
        }
    )

    LaunchedEffect(request.key) {
        webViewRef?.let(::scheduleAutoScroll)
    }
}

@Composable
private fun BatchResultScreen(
    viewModel: ParserViewModel,
    summary: BatchAuthorParseSummary,
    works: List<ParseResult.Success>,
    progressState: BatchParseResult.Loading? = null
) {
    Box(modifier = Modifier.fillMaxSize()) {
        BatchResultContent(
            viewModel = viewModel,
            summary = summary,
            works = works
        )

        progressState?.let { state ->
            BatchProgressBadge(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
fun BatchResultContent(
    viewModel: ParserViewModel,
    summary: BatchAuthorParseSummary,
    works: List<ParseResult.Success>,
    modifier: Modifier = Modifier,
    showDeleteAction: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onWorkUpdated: (ParseResult.Success) -> Unit = {}
) {
    val context = LocalContext.current
    val isResumed by rememberIsResumed()
    val saveState by viewModel.saveState
    val selection = remember { mutableStateMapOf<String, Boolean>() }
    var visibleWorks by remember { mutableStateOf(works) }
    var previewItem by remember { mutableStateOf<ParseResult.Success?>(null) }
    var gridColumns by remember { mutableStateOf(BatchDisplayPreferences.getBatchGridColumns(context)) }
    var selectionMode by remember { mutableStateOf(false) }

    LaunchedEffect(works) {
        visibleWorks = works
    }

    LaunchedEffect(isResumed) {
        if (isResumed) {
            gridColumns = BatchDisplayPreferences.getBatchGridColumns(context)
        }
    }

    LaunchedEffect(visibleWorks) {
        val validKeys = visibleWorks.map(::workKey).toSet()
        selection.keys.toList()
            .filterNot(validKeys::contains)
            .forEach(selection::remove)
        if (visibleWorks.isEmpty()) {
            selectionMode = false
        }
    }

    val selectedWorks = visibleWorks.filter { selection[workKey(it)] == true }
    val selectedCount = selectedWorks.size
    val selectedMediaCount = selectedWorks.sumOf(::mediaCount)

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns),
        modifier = modifier.fillMaxSize(),
        contentPadding = floatingBottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BatchSummaryCard(summary = summary)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            BatchActionBar(
                summary = summary,
                selectedCount = selectedCount,
                selectedMediaCount = selectedMediaCount,
                saveEnabled = !saveState.isSaving,
                selectionMode = selectionMode,
                onSaveAll = { viewModel.saveBatchMedia(context, summary, visibleWorks) },
                onSaveSelected = {
                    if (selectedWorks.isEmpty()) {
                        Toast.makeText(context, "\u8bf7\u5148\u9009\u62e9\u4f5c\u54c1", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.saveBatchMedia(context, summary, selectedWorks)
                    }
                },
                onSelectAll = {
                    selectionMode = true
                    val shouldSelectAll = selectedCount < visibleWorks.size
                    visibleWorks.forEach { selection[workKey(it)] = shouldSelectAll }
                },
                onCancelSelection = {
                    selectionMode = false
                    selection.clear()
                },
                showDeleteAction = showDeleteAction,
                onDelete = onDelete
            )
        }
        items(visibleWorks, key = { workKey(it) }) { item ->
            BatchWorkCard(
                item = item,
                gridColumns = gridColumns,
                selectionMode = selectionMode,
                selected = selection[workKey(item)] == true,
                onSelectedChange = { checked ->
                    selection[workKey(item)] = checked
                },
                onPreview = { previewItem = item },
                onLongPress = {
                    selectionMode = true
                    selection[workKey(item)] = true
                }
            )
        }
    }

    previewItem?.let { item ->
        ParseItemDetailDialog(
            viewModel = viewModel,
            item = item,
            onDismiss = { previewItem = null },
            onItemUpdated = { updatedItem ->
                visibleWorks = visibleWorks.replaceWork(updatedItem)
                previewItem = updatedItem
                onWorkUpdated(updatedItem)
            }
        )
    }
}

@Composable
fun BatchHistoryDetailPage(
    viewModel: ParserViewModel,
    summary: BatchAuthorParseSummary,
    works: List<ParseResult.Success>,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onWorkUpdated: (ParseResult.Success) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Text("\u8fd4\u56de")
            }
            Text(
                text = summary.author,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onDelete) {
                Text("\u5220\u9664")
            }
        }

        BatchResultContent(
            viewModel = viewModel,
            summary = summary,
            works = works,
            modifier = Modifier.weight(1f),
            showDeleteAction = false,
            onWorkUpdated = onWorkUpdated
        )
    }
}

@Composable
private fun BatchHintCard() {
    Box(modifier = Modifier.fillMaxSize())
}

@Composable
private fun BatchLoadingCard(state: BatchParseResult.Loading) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(state.message.ifBlank { "\u6b63\u5728\u89e3\u6790..." })
            Spacer(modifier = Modifier.height(4.dp))
            val extra = buildString {
                if (!state.author.isNullOrBlank()) {
                    append(state.author)
                    append("  ")
                }
                append("\u5df2\u89e3\u6790 ${state.currentCount}")
                if (state.requestedCount != null) {
                    append(" / ${state.requestedCount}")
                }
            }
            Text(
                text = extra,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BatchProgressBadge(
    state: BatchParseResult.Loading,
    modifier: Modifier = Modifier
) {
    val totalCount = state.requestedCount?.takeIf { it > 0 }
    val processedCount = state.currentCount.coerceAtLeast(state.works.size + state.failedCount)
    val progress = totalCount?.let { processedCount.toFloat() / it }?.coerceIn(0f, 1f)

    Card(
        modifier = modifier.widthIn(max = 220.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = state.message.ifBlank { "\u6b63\u5728\u89e3\u6790..." },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = buildString {
                    append("\u6210\u529f ${state.works.size}")
                    if (state.failedCount > 0) {
                        append("  \u5931\u8d25 ${state.failedCount}")
                    }
                    totalCount?.let {
                        append("  / $it")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BatchErrorCard(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun BatchSummaryCard(summary: BatchAuthorParseSummary) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = summary.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.author,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ElevatedAssistChip(
                        onClick = {},
                        label = { Text("\u4f5c\u54c1 ${summary.parsedCount}") }
                    )
                    ElevatedAssistChip(
                        onClick = {},
                        label = { Text("\u89c6\u9891 ${summary.videoCount}") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    ElevatedAssistChip(
                        onClick = {},
                        label = { Text("\u56fe\u96c6 ${summary.imageCount}") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (summary.isComplete) {
                        "\u5df2\u5b8c\u6574\u89e3\u6790 ${summary.totalAvailableCount} \u6761\u4f5c\u54c1"
                    } else {
                        "\u672c\u6b21\u89e3\u6790 ${summary.parsedCount} \u6761\uff0c\u5171\u6709\u66f4\u591a\u4f5c\u54c1\u672a\u8f7d\u5165"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BatchActionBar(
    summary: BatchAuthorParseSummary,
    selectedCount: Int,
    selectedMediaCount: Int,
    saveEnabled: Boolean,
    selectionMode: Boolean,
    onSaveAll: () -> Unit,
    onSaveSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onCancelSelection: () -> Unit,
    showDeleteAction: Boolean,
    onDelete: (() -> Unit)?
) {
    val compactButtonModifier = Modifier.height(36.dp)
    val compactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

    Column {
        Text(
            text = if (selectedCount > 0) {
                "\u5df2\u9009\u4e2d $selectedCount \u6761\u4f5c\u54c1\uff0c\u5171 $selectedMediaCount \u4e2a\u5a92\u4f53"
            } else {
                "\u652f\u6301\u6df7\u5408\u9009\u62e9\u89c6\u9891\u548c\u56fe\u96c6\uff0c\u76f4\u63a5\u4e00\u952e\u4fdd\u5b58"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSaveAll,
                enabled = saveEnabled,
                modifier = compactButtonModifier,
                contentPadding = compactButtonPadding
            ) {
                Text("\u5168\u90e8\u4fdd\u5b58")
            }
            OutlinedButton(
                onClick = onSelectAll,
                enabled = saveEnabled,
                modifier = compactButtonModifier,
                contentPadding = compactButtonPadding
            ) {
                Text("\u5168\u9009/\u53cd\u9009")
            }
            OutlinedButton(
                onClick = onSaveSelected,
                enabled = saveEnabled && selectedCount > 0,
                modifier = compactButtonModifier,
                contentPadding = compactButtonPadding
            ) {
                Text("\u4fdd\u5b58\u9009\u4e2d ($selectedCount)")
            }
            if (selectionMode) {
                OutlinedButton(
                    onClick = onCancelSelection,
                    enabled = saveEnabled,
                    modifier = compactButtonModifier,
                    contentPadding = compactButtonPadding
                ) {
                    Text("\u53d6\u6d88")
                }
            }
        }
        if (showDeleteAction && onDelete != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = saveEnabled,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = compactButtonModifier,
                    contentPadding = compactButtonPadding
                ) {
                    Text("\u5220\u9664", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (summary.requestedCount != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "\u672c\u6b21\u8f93\u5165\u89e3\u6790\u6570\u91cf\uff1a${summary.requestedCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BatchWorkCard(
    item: ParseResult.Success,
    gridColumns: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onLongPress: () -> Unit
) {
    val cardShape = RoundedCornerShape(if (gridColumns >= 5) 10.dp else 14.dp)
    val labelHorizontalPadding = if (gridColumns >= 5) 6.dp else 10.dp
    val labelVerticalPadding = if (gridColumns >= 5) 3.dp else 4.dp
    val checkboxSize = if (gridColumns >= 5) 36.dp else 42.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.74f)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onSelectedChange(!selected)
                    } else {
                        onPreview()
                    }
                },
                onLongClick = onLongPress
            ),
        shape = cardShape,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            if (item.cover == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.type == "video") {
                            Icons.Outlined.Movie
                        } else {
                            Icons.Outlined.Image
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(if (gridColumns >= 5) 5.dp else 8.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(999.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    )
                ) {
                    Text(
                        text = if (item.type == "video") {
                            "\u89c6\u9891"
                        } else if (item.livePhotoCount > 0) {
                            "\u56fe\u96c6 ${item.galleryImageCount} \u00b7 \u5b9e\u51b5 ${item.livePhotoCount}"
                        } else {
                            "\u56fe\u96c6 ${item.galleryImageCount}"
                        },
                        modifier = Modifier.padding(horizontal = labelHorizontalPadding, vertical = labelVerticalPadding),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(if (gridColumns >= 5) 2.dp else 6.dp)
                        .size(checkboxSize)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BatchMediaPreviewDialog(
    viewModel: ParserViewModel,
    summary: BatchAuthorParseSummary,
    item: ParseResult.Success,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val saveState by viewModel.saveState
    var playUrl by remember(item) { mutableStateOf(item.playUrl) }
    var isLoading by remember(item) { mutableStateOf(item.type == "video" && playUrl.isNullOrBlank()) }
    var hasRetriedAfterFailure by remember(item) { mutableStateOf(false) }

    LaunchedEffect(item) {
        if (item.type == "video" && playUrl.isNullOrBlank()) {
            playUrl = viewModel.getValidVideoUrl(item)
            isLoading = false
        }
    }

    LaunchedEffect(isLoading, hasRetriedAfterFailure, item) {
        if (!isLoading || !hasRetriedAfterFailure || item.type != "video") {
            return@LaunchedEffect
        }
        playUrl = viewModel.refreshValidVideoUrl(item)
        isLoading = false
    }

    if (item.type == "video") {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(
                        text = item.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (!playUrl.isNullOrBlank()) {
                        VideoPlayer(
                            url = playUrl!!,
                            cacheKey = item.rawPlayUrl ?: item.playUrl ?: playUrl!!,
                            onPlaybackFailure = {
                                if (isLoading || hasRetriedAfterFailure) {
                                    return@VideoPlayer
                                }
                                hasRetriedAfterFailure = true
                                isLoading = true
                            }
                        )
                    } else {
                        Text("\u65e0\u6cd5\u52a0\u8f7d\u64ad\u653e\u5730\u5740")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveMedia(context, item) },
                    enabled = !saveState.isSaving && !isLoading && !playUrl.isNullOrBlank()
                ) {
                    Text("\u4fdd\u5b58\u8fd9\u6761\u89c6\u9891")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val link = item.rawPlayUrl ?: item.playUrl ?: item.inputUrl
                            copyPlainText(context, clipboardManager, link)
                        }
                    ) {
                        Text("\u590d\u5236\u94fe\u63a5")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("\u5173\u95ed")
                    }
                }
            }
        )
        return
    }

    val galleryItems = item.galleryItems
    val pagerState = rememberPagerState(pageCount = { galleryItems.size })
    var previewLivePhoto by remember { mutableStateOf<GalleryMedia?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(
                    text = "${item.author}  \u00b7  ${item.galleryImageCount} \u5f20" +
                        if (item.livePhotoCount > 0) "  \u00b7  \u5b9e\u51b5 ${item.livePhotoCount}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp, max = 360.dp)
                ) { page ->
                    val media = galleryItems[page]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (media.hasLivePhoto) {
                                        previewLivePhoto = media
                                    }
                                }
                            )
                    ) {
                        AsyncImage(
                            model = media.imageUrl ?: item.cover,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (media.hasLivePhoto) {
                            Card(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp),
                                shape = RoundedCornerShape(999.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                )
                            ) {
                                Text(
                                    text = "实况",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${pagerState.currentPage + 1} / ${galleryItems.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val currentMedia = galleryItems.getOrNull(pagerState.currentPage)
                        if (currentMedia != null) {
                            viewModel.saveMedia(context, item, listOf(currentMedia))
                        }
                    },
                    enabled = !saveState.isSaving && galleryItems.isNotEmpty()
                ) {
                    Text("\u4fdd\u5b58\u5f53\u524d")
                }
                Button(
                    onClick = { viewModel.saveMedia(context, item) },
                    enabled = !saveState.isSaving && galleryItems.isNotEmpty()
                ) {
                    Text("\u4fdd\u5b58\u6574\u4e2a\u56fe\u96c6")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        copyPlainText(context, clipboardManager, item.inputUrl)
                    }
                ) {
                    Text("\u590d\u5236\u4f5c\u54c1\u94fe\u63a5")
                }
                TextButton(
                    onClick = {
                        viewModel.saveBatchMedia(context, summary, listOf(item))
                    },
                    enabled = !saveState.isSaving
                ) {
                    Text("\u6309\u4f5c\u54c1\u4fdd\u5b58")
                }
            }
        }
    )

    previewLivePhoto?.let { media ->
        LivePhotoPreviewDialog(
            viewModel = viewModel,
            media = media,
            onDismiss = { previewLivePhoto = null }
        )
    }
}
private fun workKey(item: ParseResult.Success): String {
    return "${item.videoId}_${item.parseTimestamp}"
}

private fun List<ParseResult.Success>.replaceWork(updatedItem: ParseResult.Success): List<ParseResult.Success> {
    return map { item ->
        if (workKey(item) == workKey(updatedItem)) {
            updatedItem
        } else {
            item
        }
    }
}

private fun mediaCount(item: ParseResult.Success): Int {
    return item.totalMediaAssetCount
}

private data class ParsedAuthorPostBody(
    val awemeIds: List<String>,
    val author: String?,
    val authorUid: String?,
    val authorSecUid: String?,
    val avatarUrl: String?,
    val hasMore: Boolean
)

private fun shouldCaptureAuthorPageInWebView(input: String): Boolean {
    return false
}

private fun isAuthorPostApiUrl(url: String): Boolean {
    return url.contains("/aweme/v1/web/aweme/post/", ignoreCase = true) ||
        url.contains("/web/api/v2/aweme/post/", ignoreCase = true)
}

private fun extractFirstUrl(input: String): String? {
    return Regex("https?://[^\\s'\"<>\\\\]+", RegexOption.IGNORE_CASE)
        .find(input)
        ?.value
        ?.trim('\'', '"', '`', '<', '>', '(', ')', ',', '\uff0c', '\u3002', ';', '\uff1b', '^', '\\')
}

private fun parseAuthorPostBody(body: String): ParsedAuthorPostBody {
    return runCatching {
        val json = JSONObject(body)
        val awemeList = json.optJSONArray("aweme_list")
        val ids = mutableListOf<String>()
        var author: String? = null
        var authorUid: String? = null
        var authorSecUid: String? = null
        var avatarUrl: String? = null

        if (awemeList != null) {
            for (index in 0 until awemeList.length()) {
                val item = awemeList.optJSONObject(index) ?: continue
                item.optString("aweme_id")
                    .takeIf { it.isNotBlank() }
                    ?.let(ids::add)

                val authorJson = item.optJSONObject("author")
                if (authorJson != null) {
                    author = author ?: authorJson.optString("nickname").takeIf { it.isNotBlank() }
                    authorUid = authorUid ?: authorJson.optString("uid").takeIf { it.isNotBlank() }
                    authorSecUid = authorSecUid ?: authorJson.optString("sec_uid").takeIf { it.isNotBlank() }
                    avatarUrl = avatarUrl ?: authorJson
                        .optJSONObject("avatar_thumb")
                        ?.optJSONArray("url_list")
                        ?.optString(0)
                        ?.takeIf { it.isNotBlank() }
                }
            }
        }

        ParsedAuthorPostBody(
            awemeIds = ids,
            author = author,
            authorUid = authorUid,
            authorSecUid = authorSecUid,
            avatarUrl = avatarUrl,
            hasMore = json.optInt("has_more", 0) == 1
        )
    }.getOrElse {
        ParsedAuthorPostBody(
            awemeIds = emptyList(),
            author = null,
            authorUid = null,
            authorSecUid = null,
            avatarUrl = null,
            hasMore = true
        )
    }
}

private fun fetchAuthorPostBodyInsideWebView(webView: WebView?, url: String) {
    webView ?: return
    val urlLiteral = JSONObject.quote(url)
    val script = """
        (function() {
          try {
            fetch($urlLiteral, {
              method: 'GET',
              credentials: 'include'
            }).then(function(response) {
              return response.text();
            }).then(function(body) {
              window.DyAuthorCapture.onBody(body || '');
            }).catch(function(error) {
              window.DyAuthorCapture.onError(String(error));
            });
          } catch (error) {
            window.DyAuthorCapture.onError(String(error));
          }
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
}

private fun autoScrollAuthorPage(webView: WebView?) {
    webView ?: return
    webView.evaluateJavascript(
        """
        (function() {
          const root = document.scrollingElement || document.documentElement || document.body;
          const current = root ? root.scrollTop : window.scrollY;
          const target = current + Math.max(900, Math.floor(window.innerHeight * 0.9));
          window.scrollTo(0, target);
          return String(target);
        })();
        """.trimIndent(),
        null
    )
}

private fun buildDesktopUserAgentForWebView(context: android.content.Context): String {
    val defaultUserAgent = WebSettings.getDefaultUserAgent(context)
    val chromeVersion = Regex("Chrome/([0-9.]+)")
        .find(defaultUserAgent)
        ?.groupValues
        ?.getOrNull(1)
        ?: "122.0.0.0"
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
}
