package com.jn.dyparse

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.jn.dyparse.data.GalleryMedia
import com.jn.dyparse.data.ParseResult
import com.jn.dyparse.data.galleryItems
import com.jn.dyparse.ui.SaveProgressOverlay
import com.jn.dyparse.ui.LivePhotoPreviewDialog
import com.jn.dyparse.ui.page.BatchParsePage
import com.jn.dyparse.ui.page.ParseHistoryPage
import com.jn.dyparse.ui.theme.DyparseTheme
import com.jn.dyparse.ui.theme.MiuixAlertDialog
import com.jn.dyparse.ui.theme.MiuixOutlinedButton
import com.jn.dyparse.ui.theme.MiuixPrimaryButton
import com.jn.dyparse.ui.theme.MiuixPreferenceRow
import com.jn.dyparse.ui.theme.MiuixSurface
import com.jn.dyparse.ui.theme.MiuixTextField
import com.jn.dyparse.ui.theme.floatingBottomBarContentPadding
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.jn.dyparse.ui.liquid.LiquidBottomTab
import com.jn.dyparse.ui.liquid.LiquidBottomTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            System.loadLibrary("net_utils")
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        setContent {
            DyparseTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun rememberIsResumed(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val isResumed = remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed.value = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return isResumed
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Batch : Screen("batch", "\u6279\u91cf", Icons.AutoMirrored.Filled.ViewList)
    object Home : Screen("home", "解析", Icons.Default.Home)
    object ParseHistory : Screen("history", "历史", Icons.Default.History)
    object Settings : Screen("settings", "设置", Icons.Default.Settings)
}

private val PagerNavigationSpringSpec: SpringSpec<Float> = spring(
    stiffness = 322.2f,
    dampingRatio = 32.31f / (2f * sqrt(322.2f)),
    visibilityThreshold = 0.5f
)

/** Mirrors KernelSU's pager state so tab taps and finger swipes stay synchronized. */
private class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    private var isNavigating by mutableStateOf(false)
    private var navigationJob: Job? = null

    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage || targetIndex !in 0 until pagerState.pageCount) return

        navigationJob?.cancel()
        selectedPage = targetIndex
        isNavigating = true

        navigationJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(targetIndex)
            } finally {
                if (navigationJob == currentJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

private suspend fun PagerState.springAnimateToPage(target: Int) {
    if (target !in 0 until pageCount) return
    var shouldSnapToTarget = false
    scroll(MutatePriority.UserInput) {
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        val distance = target - currentPage - currentPageOffsetFraction
        val scrollPixels = distance * pageSize
        if (abs(scrollPixels) <= 0.5f) return@scroll

        var consumedScroll = 0f
        var skipScroll = false
        Animatable(0f).animateTo(
            targetValue = scrollPixels,
            animationSpec = PagerNavigationSpringSpec
        ) {
            if (skipScroll) return@animateTo

            val delta = value - consumedScroll
            if (abs(delta) > 0.5f) {
                val consumed = scrollBy(delta)
                consumedScroll += consumed
                if (abs(delta - consumed) > 0.1f) {
                    shouldSnapToTarget = true
                    skipScroll = true
                }
            } else {
                consumedScroll = value
            }

            if (abs(velocity) < 0.1f && abs(scrollPixels - consumedScroll) < 1f) {
                skipScroll = true
            }
        }

        val remaining = scrollPixels - consumedScroll
        if (abs(remaining) > 0.5f) {
            scrollBy(remaining)
        }
    }

    if (shouldSnapToTarget || currentPage != target) {
        scrollToPage(target)
    }
}

@Composable
private fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope()
): MainPagerState = remember(pagerState, coroutineScope) {
    MainPagerState(pagerState, coroutineScope)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val parserViewModel: ParserViewModel = viewModel()
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val mediaSaveState by parserViewModel.saveState
    val navigationItems = remember {
        listOf(Screen.Home, Screen.Batch, Screen.ParseHistory, Screen.Settings)
    }
    val pagerState = rememberPagerState(pageCount = { navigationItems.size })
    val mainPagerState = rememberMainPagerState(pagerState)
    val currentPage = pagerState.currentPage
    val currentRoute = navigationItems[mainPagerState.selectedPage].route
    val surfaceColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    // 首帧后静默预热匿名会话身份（ttwid/msToken），
    // 让首装后的第一次解析无需等待 WebView 冷启动 + 预热全链路。
    LaunchedEffect(Unit) {
        parserViewModel.warmUpAnonymousSessionInBackground()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .layerBackdrop(backdrop)
        ) {
            HorizontalPager(
                modifier = Modifier
                    .fillMaxSize()
                    // Draw the window edge-to-edge while consuming only
                    // the top inset, so page content keeps its old y-position.
                    .statusBarsPadding(),
                state = pagerState,
                beyondViewportPageCount = navigationItems.lastIndex,
                key = { navigationItems[it].route }
            ) { page ->
                when (page) {
                    0 -> ParserUI(parserViewModel)
                    1 -> BatchParsePage(parserViewModel)
                    2 -> ParseHistoryPage(parserViewModel)
                    3 -> SettingsScreen(active = currentPage == 3)
                }
            }
        }

        // Keep the bar outside Scaffold so the page remains full-height and the
        // bar is a transparent, floating layer over the page content.
        var bottomBarWidthPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = 12.dp + WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding()
                )
                .zIndex(1f),
            contentAlignment = Alignment.BottomCenter
        ) {
            LiquidGlassNavigationBar(
                items = navigationItems,
                currentRoute = currentRoute,
                backdrop = backdrop,
                onItemSelected = { screen ->
                    mainPagerState.animateToPage(navigationItems.indexOf(screen))
                },
                modifier = Modifier.onSizeChanged { bottomBarWidthPx = it.width }
            )
        }

        // 保存进度浮层：放在底栏之上（zIndex 更高），底部留出底栏高度，
        // 宽度与底部 Tab 栏完全一致，避免被 Tab 底栏遮挡。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f),
            contentAlignment = Alignment.BottomCenter
        ) {
            val bottomBarHeight = 64.dp + 12.dp + WindowInsets.navigationBars
                .asPaddingValues()
                .calculateBottomPadding()
            val bottomBarWidth = if (bottomBarWidthPx > 0) {
                with(LocalDensity.current) { bottomBarWidthPx.toDp() }
            } else {
                312.dp
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomBarHeight + 2.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                SaveProgressOverlay(
                    saveState = mediaSaveState,
                    backdrop = backdrop,
                    modifier = Modifier.width(bottomBarWidth)
                )
            }
        }

        // 保存前大小确认弹窗：视频超过阈值时询问是否继续
        val sizeConfirm by parserViewModel.sizeConfirmRequest
        if (sizeConfirm != null) {
            MiuixAlertDialog(
                onDismissRequest = {},
                title = { Text("视频文件较大") },
                text = {
                    Text(
                        "该视频约 ${formatFileSize(sizeConfirm!!.sizeBytes)}，" +
                            "超过设置的提示大小 ${formatFileSize(sizeConfirm!!.limitBytes)}。\n\n确定要继续保存吗？"
                    )
                },
                confirmButton = {
                    MiuixPrimaryButton(
                        onClick = { parserViewModel.respondSizeConfirm(true) }
                    ) {
                        Text("继续保存")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { parserViewModel.respondSizeConfirm(false) }
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${bytes}B"
    } else {
        String.format(java.util.Locale.US, "%.1f%s", value, units[unitIndex])
    }
}

@Composable
private fun LiquidGlassNavigationBar(
    items: List<Screen>,
    currentRoute: String?,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onItemSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val routeIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    var interactiveIndex by remember { mutableIntStateOf(routeIndex) }

    LaunchedEffect(routeIndex) {
        interactiveIndex = routeIndex
    }

    LiquidBottomTabs(
        selectedTabIndex = { interactiveIndex },
        onTabSelected = { index ->
            interactiveIndex = index
            onItemSelected(items[index])
        },
        backdrop = backdrop,
        tabsCount = items.size,
        modifier = modifier
            .width(IntrinsicSize.Min)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        items.forEachIndexed { index, screen ->
            LiquidBottomTab(
                onClick = { interactiveIndex = index },
                index = index
            ) {
                Icon(
                    imageVector = screen.icon,
                    contentDescription = screen.title,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = screen.title,
                    color = LocalContentColor.current,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ParserUI(viewModel: ParserViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    val parseResult by viewModel.parseResult
    val saveState by viewModel.saveState
    val context = LocalContext.current
    val isResumed by rememberIsResumed()
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val selectedGalleryItems = remember { mutableStateMapOf<Int, Boolean>() }
    var previewLivePhoto by remember { mutableStateOf<GalleryMedia?>(null) }

    val silentClickModifier = @Composable { onClick: () -> Unit ->
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    }

    val isParsing = parseResult is ParseResult.Loading
    val isBusy = isParsing || saveState.isSaving
    val galleryItems = (parseResult as? ParseResult.Success)?.galleryItems.orEmpty()

    LaunchedEffect(galleryItems) {
        selectedGalleryItems.clear()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(16.dp),
            contentPadding = floatingBottomBarContentPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(span = { GridItemSpan(3) }) {
                MiuixSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "视频/图集解析器",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        MiuixTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("请输入抖音分享链接或作品 ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        clipboardManager.getText()?.let { clipText ->
                                            text = clipText.text
                                        }
                                    },
                                    enabled = !isBusy
                                ) {
                                    Icon(Icons.Outlined.ContentPaste, contentDescription = "粘贴")
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { text = "" },
                                enabled = text.isNotBlank() && !isBusy
                            ) {
                                Text("清空")
                            }
                            MiuixPrimaryButton(
                                onClick = { viewModel.parse(text) },
                                enabled = text.isNotBlank() && !isBusy
                            ) {
                                Text(if (isParsing) "解析中..." else "解析")
                            }
                        }
                    }
                }
            }

            when (val result = parseResult) {
                is ParseResult.Idle -> Unit
                is ParseResult.Loading -> {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is ParseResult.Success -> {
                    item(span = { GridItemSpan(3) }) {
                        MiuixSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "作者: ${result.author}",
                                    fontWeight = FontWeight.Bold,
                                    modifier = silentClickModifier {
                                        copyPlainText(context, clipboardManager, result.author)
                                    }
                                )
                                Text(
                                    text = "标题: ${result.title}",
                                    modifier = silentClickModifier {
                                        copyPlainText(context, clipboardManager, result.title)
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }

                    if (result.type == "video" && result.playUrl != null) {
                        item(span = { GridItemSpan(3) }) {
                            VideoPlayer(
                                url = result.playUrl,
                                cacheKey = result.rawPlayUrl ?: result.playUrl
                            )
                        }
                    } else if (result.type == "image" && result.galleryItems.isNotEmpty()) {
                        items(result.galleryItems, key = { it.index }) { media ->
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        enabled = !isBusy,
                                        onClick = {
                                            selectedGalleryItems[media.index] =
                                                !(selectedGalleryItems[media.index] ?: false)
                                        },
                                        onLongClick = {
                                            if (media.hasLivePhoto) {
                                                previewLivePhoto = media
                                            }
                                        }
                                    )
                            ) {
                                AsyncImage(
                                    model = media.imageUrl ?: result.cover,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                if (media.hasLivePhoto) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(6.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                                shape = RoundedCornerShape(999.dp)
                                            )
                                    ) {
                                        Text(
                                            text = "实况",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                androidx.compose.material3.Checkbox(
                                    checked = selectedGalleryItems[media.index] ?: false,
                                    onCheckedChange = { checked ->
                                        selectedGalleryItems[media.index] = checked
                                    },
                                    enabled = !isBusy,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                        }
                    }

                    item(span = { GridItemSpan(3) }) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MiuixPrimaryButton(
                                    onClick = { viewModel.saveMedia(context, result) },
                                    enabled = !isBusy
                                ) {
                                    Text(if (result.type == "video") "保存视频" else "保存全部图片")
                                }

                                if (result.type == "image" && result.galleryItems.isNotEmpty()) {
                                    val selectedCount = selectedGalleryItems.count { it.value }
                                    Button(
                                        onClick = {
                                            val mediaToSave = result.galleryItems.filter {
                                                selectedGalleryItems[it.index] == true
                                            }
                                            viewModel.saveMedia(context, result, mediaToSave)
                                        },
                                        enabled = selectedCount > 0 && !isBusy
                                    ) {
                                        Text("保存选中 ($selectedCount)")
                                    }
                                }
                            }
                        }
                    }
                }
                is ParseResult.Error -> {
                    item(span = { GridItemSpan(3) }) {
                        Text(result.msg, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (!isResumed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            )
        }
    }

    previewLivePhoto?.let { media ->
        LivePhotoPreviewDialog(
            viewModel = viewModel,
            media = media,
            onDismiss = { previewLivePhoto = null }
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun VideoPlayer(
    url: String,
    cacheKey: String? = null,
    onPlaybackFailure: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isResumed by rememberIsResumed()
    var hasReportedPlaybackFailure by remember(url, cacheKey) { mutableStateOf(false) }
    val exoPlayer = remember(context) {
        PreviewPlaybackCache.createPlayer(context).apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    DisposableEffect(exoPlayer, onPlaybackFailure) {
        if (onPlaybackFailure == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (!hasReportedPlaybackFailure) {
                        hasReportedPlaybackFailure = true
                        onPlaybackFailure()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        hasReportedPlaybackFailure = false
                    }
                }
            }
            exoPlayer.addListener(listener)
            onDispose { exoPlayer.removeListener(listener) }
        }
    }

    LaunchedEffect(exoPlayer, url, cacheKey) {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply {
                cacheKey?.takeIf { it.isNotBlank() }?.let(::setCustomCacheKey)
            }
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (isResumed) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(exoPlayer, isResumed) {
        if (isResumed) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                controllerAutoShow = false
                hideController()
            }
        },
        update = { view ->
            if (isResumed) {
                view.player = exoPlayer
                view.visibility = View.VISIBLE
            } else {
                view.player = null
                view.visibility = View.GONE
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    )
}

@Composable
fun SettingsScreen(active: Boolean = true) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val isResumed by rememberIsResumed()
    val scope = rememberCoroutineScope()
    val authRevision by DouyinAuthStore.authRevision.collectAsState()

    var showFeedbackDialog by rememberSaveable { mutableStateOf(false) }
    var showBatchGridDialog by rememberSaveable { mutableStateOf(false) }
    var showBatchParseSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveSizeLimitDialog by rememberSaveable { mutableStateOf(false) }
    var showServerConfigDialog by rememberSaveable { mutableStateOf(false) }
    var serverConfigSummary by rememberSaveable { mutableStateOf(describeServerConfig()) }
    var videoSizeLimitMb by rememberSaveable {
        mutableStateOf(SaveSizePreferences.getLimitMb(appContext))
    }
    var originalVideoSaveEnabled by rememberSaveable {
        mutableStateOf(DouyinAuthStore.isOriginalVideoSaveEnabled(appContext))
    }
    var highestQualityVideoSaveEnabled by rememberSaveable {
        mutableStateOf(DouyinAuthStore.isHighestQualityVideoSaveEnabled(appContext))
    }
    var batchGridColumns by rememberSaveable { mutableStateOf(BatchDisplayPreferences.getBatchGridColumns(appContext)) }
    var batchWorkIntervalMs by rememberSaveable {
        mutableStateOf(BatchParsePreferences.getSettings(appContext).workIntervalMs)
    }
    var authorPageIntervalMs by rememberSaveable {
        mutableStateOf(BatchParsePreferences.getSettings(appContext).authorPageIntervalMs)
    }
    var consecutiveFailureStopCount by rememberSaveable {
        mutableStateOf(BatchParsePreferences.getSettings(appContext).consecutiveFailureStopCount)
    }
    var cacheSize by rememberSaveable { mutableStateOf("计算中...") }
    var isClearingCache by rememberSaveable { mutableStateOf(false) }

    fun refreshVideoQualitySavePreferences() {
        val preferences = DouyinAuthStore.syncVideoQualitySavePreferences(appContext)
        originalVideoSaveEnabled = preferences.originalEnabled
        highestQualityVideoSaveEnabled = preferences.highestQualityEnabled
    }

    LaunchedEffect(Unit, authRevision) {
        cacheSize = getCacheSize(appContext)
        refreshVideoQualitySavePreferences()
        batchGridColumns = BatchDisplayPreferences.getBatchGridColumns(appContext)
        BatchParsePreferences.getSettings(appContext).let { settings ->
            batchWorkIntervalMs = settings.workIntervalMs
            authorPageIntervalMs = settings.authorPageIntervalMs
            consecutiveFailureStopCount = settings.consecutiveFailureStopCount
        }
    }

    // 每次进入设置页（pager 切换到该页）时重新计算缓存大小：
    // 播放视频产生的预览缓存、清理缓存后的变化都会实时反映
    LaunchedEffect(active) {
        if (active) {
            cacheSize = getCacheSize(appContext)
        }
    }

    if (showFeedbackDialog) {
        MiuixAlertDialog(
            onDismissRequest = { showFeedbackDialog = false },
            title = { Text("联系作者") },
            text = {
                Text("如果你在使用中遇到问题，或有任何建议，欢迎通过以下方式联系我：\n\nQQ: 1091809621")
            },
            confirmButton = {
                TextButton(onClick = { showFeedbackDialog = false }) {
                    Text("好的")
                }
            }
        )
    }

    if (showBatchGridDialog) {
        MiuixAlertDialog(
            onDismissRequest = { showBatchGridDialog = false },
            title = { Text("\u6279\u91cf\u4f5c\u54c1\u5217\u6570") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatchDisplayPreferences.supportedGridColumns.forEach { columns ->
                        MiuixOutlinedButton(
                            onClick = {
                                BatchDisplayPreferences.setBatchGridColumns(appContext, columns)
                                batchGridColumns = columns
                                showBatchGridDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (columns == batchGridColumns) {
                                    "$columns \u5217\uff08\u5f53\u524d\uff09"
                                } else {
                                    "$columns \u5217"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBatchGridDialog = false }) {
                    Text("\u5173\u95ed")
                }
            }
        )
    }

    if (showBatchParseSettingsDialog) {
        var workIntervalInput by rememberSaveable(showBatchParseSettingsDialog) {
            mutableStateOf(batchWorkIntervalMs.toString())
        }
        var authorPageIntervalInput by rememberSaveable(showBatchParseSettingsDialog) {
            mutableStateOf(authorPageIntervalMs.toString())
        }
        var failureStopInput by rememberSaveable(showBatchParseSettingsDialog) {
            mutableStateOf(consecutiveFailureStopCount.toString())
        }

        MiuixAlertDialog(
            onDismissRequest = { showBatchParseSettingsDialog = false },
            title = { Text("批量请求节奏") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiuixTextField(
                        value = workIntervalInput,
                        onValueChange = { workIntervalInput = it.filter(Char::isDigit) },
                        label = { Text("批量作品间隔（毫秒）") },
                        supportingText = {
                            Text(
                                "默认 ${BatchParsePreferences.DEFAULT_WORK_INTERVAL_MS}ms，建议 ${BatchParsePreferences.RECOMMENDED_WORK_INTERVAL_MS}ms 左右"
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    MiuixTextField(
                        value = authorPageIntervalInput,
                        onValueChange = { authorPageIntervalInput = it.filter(Char::isDigit) },
                        label = { Text("作者分页间隔（毫秒）") },
                        supportingText = {
                            Text(
                                "默认 ${BatchParsePreferences.DEFAULT_AUTHOR_PAGE_INTERVAL_MS}ms，建议 ${BatchParsePreferences.RECOMMENDED_AUTHOR_PAGE_INTERVAL_MS}ms 左右"
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    MiuixTextField(
                        value = failureStopInput,
                        onValueChange = { failureStopInput = it.filter(Char::isDigit) },
                        label = { Text("连续失败停止条数") },
                        supportingText = {
                            Text(
                                "默认 ${BatchParsePreferences.DEFAULT_CONSECUTIVE_FAILURE_STOP_COUNT} 条；普通连续失败达到后停止，填 0 表示普通失败不停"
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                MiuixPrimaryButton(
                    onClick = {
                        val workInterval = workIntervalInput.toIntOrNull()
                            ?: BatchParsePreferences.DEFAULT_WORK_INTERVAL_MS
                        val authorInterval = authorPageIntervalInput.toIntOrNull()
                            ?: BatchParsePreferences.DEFAULT_AUTHOR_PAGE_INTERVAL_MS
                        val failureStop = failureStopInput.toIntOrNull()
                            ?: BatchParsePreferences.DEFAULT_CONSECUTIVE_FAILURE_STOP_COUNT
                        BatchParsePreferences.saveSettings(
                            context = appContext,
                            workIntervalMs = workInterval,
                            authorPageIntervalMs = authorInterval,
                            consecutiveFailureStopCount = failureStop
                        )
                        BatchParsePreferences.getSettings(appContext).let { settings ->
                            batchWorkIntervalMs = settings.workIntervalMs
                            authorPageIntervalMs = settings.authorPageIntervalMs
                            consecutiveFailureStopCount = settings.consecutiveFailureStopCount
                        }
                        showBatchParseSettingsDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            BatchParsePreferences.reset(appContext)
                            BatchParsePreferences.getSettings(appContext).let { settings ->
                                batchWorkIntervalMs = settings.workIntervalMs
                                authorPageIntervalMs = settings.authorPageIntervalMs
                                consecutiveFailureStopCount = settings.consecutiveFailureStopCount
                            }
                            showBatchParseSettingsDialog = false
                        }
                    ) {
                        Text("恢复默认")
                    }
                    TextButton(onClick = { showBatchParseSettingsDialog = false }) {
                        Text("取消")
                    }
                }
            }
        )
    }

    if (showSaveSizeLimitDialog) {
        var limitInput by rememberSaveable(showSaveSizeLimitDialog) {
            mutableStateOf(videoSizeLimitMb.toString())
        }
        MiuixAlertDialog(
            onDismissRequest = { showSaveSizeLimitDialog = false },
            title = { Text("保存视频大小提示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiuixTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it.filter(Char::isDigit) },
                        label = { Text("提示大小（MB）") },
                        supportingText = {
                            Text("填 0 关闭提示；保存视频超过该大小时询问是否继续")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                MiuixPrimaryButton(
                    onClick = {
                        val value = limitInput.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        SaveSizePreferences.setLimitMb(appContext, value)
                        videoSizeLimitMb = value
                        showSaveSizeLimitDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveSizeLimitDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showServerConfigDialog) {
        val defaultConfig = remember { ServerConfigStore.defaults() }
        val savedConfig = remember { ServerConfigStore.getConfig() }
        var apiBaseInput by rememberSaveable(showServerConfigDialog) { mutableStateOf(savedConfig.apiBase) }
        var authorApiInput by rememberSaveable(showServerConfigDialog) { mutableStateOf(savedConfig.authorApiBase) }
        var tokenInput by rememberSaveable(showServerConfigDialog) { mutableStateOf(savedConfig.token) }
        var hmacInput by rememberSaveable(showServerConfigDialog) { mutableStateOf(savedConfig.hmacKey) }
        var isTesting by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf("") }

        MiuixAlertDialog(
            onDismissRequest = { showServerConfigDialog = false },
            title = { Text("服务器配置") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "本项目的解析逻辑全部在服务端，必须填入你自己部署的服务端信息。" +
                            "部署方法见仓库 README 与 server/README.md。" +
                            "三个值必须与服务端 config.php 完全一致。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    MiuixTextField(
                        value = apiBaseInput,
                        onValueChange = { apiBaseInput = it },
                        label = { Text("解析接口地址") },
                        supportingText = { Text("例如 https://你的域名/api/data.php") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MiuixTextField(
                        value = authorApiInput,
                        onValueChange = { authorApiInput = it },
                        label = { Text("作者列表接口地址") },
                        supportingText = { Text("例如 https://你的域名/api/author_list.php") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MiuixTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("API Token") },
                        supportingText = { Text("对应服务端 config.php 的 API_TOKEN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    MiuixTextField(
                        value = hmacInput,
                        onValueChange = { hmacInput = it },
                        label = { Text("HMAC 密钥") },
                        supportingText = { Text("对应服务端 config.php 的 API_HMAC_KEY") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (testResult.isNotBlank()) {
                        Text(testResult, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                MiuixPrimaryButton(
                    onClick = {
                        val apiBase = ServerConfigStore.normalizeUrl(apiBaseInput)
                        val authorApi = ServerConfigStore.normalizeUrl(authorApiInput)
                        if (apiBase == null || authorApi == null) {
                            Toast.makeText(
                                context,
                                "两个地址都必须以 http:// 或 https:// 开头",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@MiuixPrimaryButton
                        }
                        ServerConfigStore.save(
                            ServerConfigStore.Config(
                                apiBase = apiBase,
                                authorApiBase = authorApi,
                                token = tokenInput.trim(),
                                hmacKey = hmacInput.trim()
                            )
                        )
                        serverConfigSummary = describeServerConfig()
                        showServerConfigDialog = false
                        Toast.makeText(context, "服务器配置已保存", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                Row {
                    MiuixOutlinedButton(
                        onClick = {
                            if (isTesting) return@MiuixOutlinedButton
                            isTesting = true
                            testResult = "测试中…"
                            scope.launch {
                                testResult = ServerApiClient.testConnection(
                                    apiBase = apiBaseInput,
                                    token = tokenInput,
                                    hmacKey = hmacInput
                                )
                                isTesting = false
                            }
                        }
                    ) {
                        Text(if (isTesting) "测试中…" else "测试连接")
                    }
                    TextButton(
                        onClick = {
                            apiBaseInput = defaultConfig.apiBase
                            authorApiInput = defaultConfig.authorApiBase
                            tokenInput = defaultConfig.token
                            hmacInput = defaultConfig.hmacKey
                            testResult = ""
                        }
                    ) {
                        Text("恢复默认")
                    }
                    TextButton(onClick = { showServerConfigDialog = false }) {
                        Text("取消")
                    }
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = floatingBottomBarContentPadding(top = 16.dp)
        ) {
            item {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                SectionTitle("通用", modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.CleaningServices,
                    title = "清理缓存",
                    subtitle = if (isClearingCache) "清理中..." else cacheSize,
                    enabled = !isClearingCache,
                    onClick = {
                        scope.launch {
                            isClearingCache = true
                            try {
                                clearCache(appContext)
                                // 清理后重新计算缓存大小，确保 UI 实时更新
                                cacheSize = getCacheSize(appContext)
                                Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                            } finally {
                                isClearingCache = false
                            }
                        }
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "版本信息",
                    subtitle = getAppVersion(appContext)
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Cloud,
                    title = "服务器配置",
                    subtitle = serverConfigSummary,
                    onClick = { showServerConfigDialog = true }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "\u6279\u91cf\u6bcf\u884c\u663e\u793a",
                    subtitle = "$batchGridColumns \u5217",
                    onClick = { showBatchGridDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "批量请求节奏",
                    onClick = { showBatchParseSettingsDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "保存视频大小提示",
                    subtitle = if (videoSizeLimitMb > 0) {
                        "超过 ${videoSizeLimitMb}MB 时提示"
                    } else {
                        "已关闭"
                    },
                    onClick = { showSaveSizeLimitDialog = true }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                SectionTitle("\u4fdd\u5b58\u8bbe\u7f6e", modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Outlined.Info,
                    title = "原画质保存",
                    checked = originalVideoSaveEnabled,
                    onCheckedChange = { checked ->
                        originalVideoSaveEnabled =
                            DouyinAuthStore.setOriginalVideoSaveEnabled(appContext, checked)
                    }
                )
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Outlined.School,
                    title = "最高画质保存",
                    checked = highestQualityVideoSaveEnabled,
                    onCheckedChange = { checked ->
                        highestQualityVideoSaveEnabled =
                            DouyinAuthStore.setHighestQualityVideoSaveEnabled(appContext, checked)
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                SectionTitle("关于与帮助", modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.Feedback,
                    title = "问题反馈",
                    onClick = { showFeedbackDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Outlined.School,
                    title = "使用教程",
                    onClick = {
                        Toast.makeText(context, "直接粘贴链接或作品 ID 后点击解析即可。", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        if (!isResumed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 设置页「服务器配置」的副标题：只显示主机名，避免把 Token 之类显示在列表上。
 */
private fun describeServerConfig(): String {
    val config = ServerConfigStore.getConfig()
    if (ServerConfigStore.isPlaceholder(config.apiBase)) {
        return "未配置（必填）"
    }
    return runCatching { java.net.URI(config.apiBase).host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: config.apiBase
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    MiuixSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.6f)
    ) {
        MiuixPreferenceRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            enabled = enabled,
            onClick = onClick
        )
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    MiuixSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.6f)
    ) {
        MiuixPreferenceRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            enabled = enabled,
            onClick = { onCheckedChange(!checked) },
            trailing = {
                CompactSwitch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange
                )
            }
        )
    }
}

/** 紧凑 Switch：高度与文本行对齐（约 20dp），不撑高行 */
@Composable
private fun CompactSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val trackWidth = 36.dp
    val trackHeight = 20.dp
    val thumbSize = 14.dp
    val color = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    }
    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
            .padding(3.dp)
    ) {
        // thumb 用 align 切换，避免 offset 超出裁剪；padding 3dp 保证不贴边
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(thumbSize)
                .background(Color.White, CircleShape)
        )
    }
}

fun getAppVersion(context: Context): String {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }
}

@Suppress("DEPRECATION")
fun getAppVersionCode(context: Context): Int {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            packageInfo.versionCode
        }
    } catch (_: Exception) {
        0
    }
}

private suspend fun getCacheSize(context: Context): String = withContext(Dispatchers.IO) {
    val regularCacheSize = listOfNotNull(context.cacheDir, context.externalCacheDir)
        .sumOf { it.safeDirectorySize() }
    val persistentPreviewCacheSize = PreviewPlaybackCache.getCacheDirectory(context).safeDirectorySize()
    val livePhotoUrlCacheSize = File(context.filesDir, "live_photo_play_url_cache.json")
        .takeIf { it.exists() }
        ?.length()
        ?: 0L
    val totalSize = regularCacheSize + persistentPreviewCacheSize + livePhotoUrlCacheSize
    formatBytes(totalSize)
}

private suspend fun clearCache(context: Context): String = withContext(Dispatchers.IO) {
    listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { directory ->
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                child.deleteRecursively()
            } else {
                child.delete()
            }
        }
    }

    PreviewPlaybackCache.clear(context)
    File(context.filesDir, "live_photo_play_url_cache.json").delete()

    val remainingSize = listOfNotNull(context.cacheDir, context.externalCacheDir)
        .sumOf { it.safeDirectorySize() } +
        PreviewPlaybackCache.getCacheDirectory(context).safeDirectorySize() +
        (
            File(context.filesDir, "live_photo_play_url_cache.json")
                .takeIf { it.exists() }
                ?.length()
                ?: 0L
        )
    formatBytes(remainingSize)
}

private fun File.safeDirectorySize(): Long {
    if (!exists()) {
        return 0L
    }

    return walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
}

private fun formatBytes(sizeInBytes: Long): String {
    val sizeInMb = sizeInBytes / (1024.0 * 1024.0)
    return String.format("%.2f MB", sizeInMb)
}
