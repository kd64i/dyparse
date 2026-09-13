package com.jn.dyparse.ui.page

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jn.dyparse.ParserViewModel
import com.jn.dyparse.VideoPlayer
import com.jn.dyparse.copyPlainText
import com.jn.dyparse.data.BatchAuthorParseSummary
import com.jn.dyparse.data.GalleryMedia
import com.jn.dyparse.data.ParseResult
import com.jn.dyparse.data.galleryItems
import com.jn.dyparse.ui.LivePhotoPreviewDialog
import com.jn.dyparse.ui.theme.MiuixSegmentedTabs
import com.jn.dyparse.ui.theme.MiuixSurface
import com.jn.dyparse.ui.theme.floatingBottomBarContentPadding
import com.jn.dyparse.rememberIsResumed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParseHistoryPage(viewModel: ParserViewModel = viewModel()) {
    val history by viewModel.historyState
    val batchHistory by viewModel.batchHistoryState
    var showDetailDialog by remember { mutableStateOf<ParseResult.Success?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var selectedBatch by remember { mutableStateOf<BatchAuthorParseSummary?>(null) }
    var selectedBatchWorks by remember { mutableStateOf<List<ParseResult.Success>>(emptyList()) }
    var isLoadingBatchWorks by remember { mutableStateOf(false) }
    val isResumed by rememberIsResumed()
    val closeBatchDetail = {
        selectedBatch = null
        selectedBatchWorks = emptyList()
    }

    // 详情弹窗：historyState 始终为完整对象（含 playUrl/rawPlayUrl），
    // 点击直接弹出，播放链路与解析页一致（缓存优先，失败自动刷新）。
    fun openDetail(item: ParseResult.Success) {
        showDetailDialog = item
    }

    LaunchedEffect(selectedBatch?.batchId) {
        val batch = selectedBatch ?: return@LaunchedEffect
        isLoadingBatchWorks = true
        selectedBatchWorks = viewModel.getBatchHistoryWorks(batch.batchId)
        isLoadingBatchWorks = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedBatch != null) {
            BackHandler {
                closeBatchDetail()
            }

            if (isLoadingBatchWorks) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                BatchHistoryDetailPage(
                    viewModel = viewModel,
                    summary = selectedBatch!!,
                    works = selectedBatchWorks,
                    onBack = closeBatchDetail,
                    onDelete = {
                        selectedBatch?.let { batch ->
                            viewModel.deleteBatchHistoryItem(batch)
                        }
                        closeBatchDetail()
                    },
                    onWorkUpdated = { updatedItem ->
                        selectedBatchWorks = selectedBatchWorks.replaceHistoryWork(updatedItem)
                    }
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                MiuixSegmentedTabs(
                    labels = listOf("\u4f5c\u54c1\u5386\u53f2", "\u6279\u91cf\u5386\u53f2"),
                    selectedIndex = selectedTab,
                    onSelected = { selectedTab = it },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )

                if (selectedTab == 0) {
                    if (history.isEmpty()) {
                        EmptyHistoryState()
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = floatingBottomBarContentPadding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(history, key = { "${it.videoId}_${it.parseTimestamp}" }) { item ->
                                HistoryItem(item) {
                                    openDetail(item)
                                }
                            }
                        }
                    }
                } else {
                    if (batchHistory.isEmpty()) {
                        EmptyBatchHistoryState()
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = floatingBottomBarContentPadding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(batchHistory, key = { it.batchId }) { item ->
                                BatchHistoryItem(item) {
                                    selectedBatch = item
                                }
                            }
                        }
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

    showDetailDialog?.let { item ->
        ParseItemDetailDialog(
            viewModel = viewModel,
            item = item,
            onDismiss = { showDetailDialog = null },
            onDelete = {
                viewModel.deleteHistoryItem(item)
                showDetailDialog = null
            },
            onItemUpdated = { updatedItem ->
                showDetailDialog = updatedItem
            }
        )
    }
}

private fun List<ParseResult.Success>.replaceHistoryWork(
    updatedItem: ParseResult.Success
): List<ParseResult.Success> {
    return map { item ->
        if (item.videoId == updatedItem.videoId && item.parseTimestamp == updatedItem.parseTimestamp) {
            updatedItem
        } else {
            item
        }
    }
}

@Composable
private fun EmptyHistoryState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "还没有解析记录",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "完成一次解析后，这里会自动保存历史记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyBatchHistoryState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "\u8fd8\u6ca1\u6709\u6279\u91cf\u89e3\u6790\u8bb0\u5f55",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "\u5728\u6279\u91cf\u89e3\u6790\u9875\u5b8c\u6210\u4e00\u6b21\u4f5c\u8005\u4e3b\u9875\u89e3\u6790\u540e\uff0c\u8fd9\u91cc\u4f1a\u6309\u4f5c\u8005\u5206\u7ec4\u663e\u793a\u5386\u53f2\u3002",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BatchHistoryItem(item: BatchAuthorParseSummary, onClick: () -> Unit) {
    MiuixSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\u5df2\u89e3\u6790 ${item.parsedCount} \u6761\uff0c\u89c6\u9891 ${item.videoCount} \u6761\uff0c\u56fe\u96c6 ${item.imageCount} \u6761",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (item.isComplete) {
                        "\u5df2\u5b8c\u6574\u89e3\u6790 ${item.totalAvailableCount} \u6761\u4f5c\u54c1"
                    } else {
                        "\u672c\u6b21\u89e3\u6790 ${item.parsedCount} \u6761\uff0c\u4ecd\u6709\u66f4\u591a\u4f5c\u54c1\u672a\u8f7d\u5165"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun HistoryItem(item: ParseResult.Success, onClick: () -> Unit) {
    MiuixSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dateStr = remember(item.parseTimestamp) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(Date(item.parseTimestamp * 1000))
                    }
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (item.duration > 0) {
                        Text(
                            text = String.format("%.2fs", item.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailDialog(
    viewModel: ParserViewModel,
    item: ParseResult.Success,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val saveState by viewModel.saveState

    var showImageSaveDialog by remember { mutableStateOf(false) }
    var playUrl by remember { mutableStateOf<String?>(null) }
    var isLoadingUrl by remember { mutableStateOf(false) }
    var hasRetriedAfterFailure by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        if (item.type == "video") {
            isLoadingUrl = true
            playUrl = viewModel.getValidVideoUrl(item)
            isLoadingUrl = false
        }
    }

    LaunchedEffect(isLoadingUrl, hasRetriedAfterFailure, item) {
        if (!isLoadingUrl || !hasRetriedAfterFailure || item.type != "video") {
            return@LaunchedEffect
        }
        playUrl = viewModel.refreshValidVideoUrl(item)
        isLoadingUrl = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 5,
                overflow = TextOverflow.Visible,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    copyPlainText(context, clipboardManager, item.title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "作者: ${item.author}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                copyPlainText(context, clipboardManager, item.author)
                            }
                        )
                        Text(
                            text = "ID: ${item.videoId}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                copyPlainText(context, clipboardManager, item.videoId)
                            }
                        )
                    }
                    val publishDateStr = remember(item.timestamp) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(Date(item.timestamp * 1000))
                    }
                    Text(
                        text = publishDateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.type == "video") {
                        when {
                            isLoadingUrl -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            playUrl != null -> VideoPlayer(
                                url = playUrl!!,
                                cacheKey = item.rawPlayUrl ?: item.playUrl ?: playUrl!!,
                                onPlaybackFailure = {
                                    if (isLoadingUrl || hasRetriedAfterFailure) {
                                        return@VideoPlayer
                                    }
                                    hasRetriedAfterFailure = true
                                    isLoadingUrl = true
                                }
                            )
                            else -> Text("无法加载播放地址", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        AsyncImage(
                            model = item.cover,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val buttonWeight = 1f
                    OutlinedButton(
                        onClick = {
                            copyPlainText(context, clipboardManager, item.inputUrl, "链接已复制")
                        },
                        modifier = Modifier
                            .weight(buttonWeight)
                            .height(32.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    ) {
                        Text("复制链接", fontSize = 10.sp)
                    }

                    if (item.type == "video") {
                        val playUrlToCopy = item.rawPlayUrl ?: item.playUrl
                        if (!playUrlToCopy.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = {
                                    copyPlainText(context, clipboardManager, playUrlToCopy, "直链已复制")
                                },
                                modifier = Modifier
                                    .weight(buttonWeight)
                                    .height(32.dp),
                                contentPadding = PaddingValues(0.dp),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            ) {
                                Text("复制直链", fontSize = 10.sp)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (item.type == "video") {
                                viewModel.saveMedia(context, item)
                                onDismiss()
                            } else {
                                showImageSaveDialog = true
                            }
                        },
                        enabled = !saveState.isSaving && !isLoadingUrl,
                        modifier = Modifier
                            .weight(buttonWeight)
                            .height(32.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (item.type == "video") "保存视频" else "保存图片",
                            fontSize = 10.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDelete()
                    Toast.makeText(context, "记录已删除", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text(
                    text = "删除记录",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
        }
    )

    if (showImageSaveDialog) {
        ImageSaveDialog(
            viewModel = viewModel,
            item = item,
            onDismiss = { showImageSaveDialog = false },
            context = context
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageSaveDialog(
    viewModel: ParserViewModel,
    item: ParseResult.Success,
    onDismiss: () -> Unit,
    context: Context
) {
    val saveState by viewModel.saveState
    val galleryItems = item.galleryItems
    val selectedItems = remember { mutableStateMapOf<Int, Boolean>() }
    val selectedCount = selectedItems.count { it.value }
    val mediaToSave = galleryItems.filter { selectedItems[it.index] == true }
    var previewLivePhoto by remember { mutableStateOf<GalleryMedia?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择图片", style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                items(galleryItems, key = { it.index }) { media ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .combinedClickable(
                                enabled = !saveState.isSaving,
                                onClick = {
                                    selectedItems[media.index] = !(selectedItems[media.index] ?: false)
                                },
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
                        if (selectedItems[media.index] == true) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetMedia = if (selectedCount > 0) mediaToSave else galleryItems
                    viewModel.saveMedia(context, item, targetMedia)
                    onDismiss()
                },
                enabled = !saveState.isSaving && galleryItems.isNotEmpty(),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = if (selectedCount > 0) "保存选中 ($selectedCount)" else "保存全部",
                    fontSize = 12.sp
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saveState.isSaving
            ) {
                Text("取消", fontSize = 12.sp)
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
