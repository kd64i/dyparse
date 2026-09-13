package com.jn.dyparse.ui.page

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.compose.AsyncImage
import com.jn.dyparse.ParserViewModel
import com.jn.dyparse.VideoPlayer
import com.jn.dyparse.copyPlainText
import com.jn.dyparse.data.GalleryMedia
import com.jn.dyparse.data.ParseResult
import com.jn.dyparse.data.galleryItems
import com.jn.dyparse.ui.LivePhotoPreviewDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ParseItemDetailDialog(
    viewModel: ParserViewModel,
    item: ParseResult.Success,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onItemUpdated: (ParseResult.Success) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val saveState by viewModel.saveState

    var showImageSaveDialog by remember { mutableStateOf(false) }
    var currentItem by remember(item) { mutableStateOf(item) }
    var playUrl by remember { mutableStateOf<String?>(null) }
    var isLoadingUrl by remember { mutableStateOf(false) }
    var hasRetriedAfterFailure by remember(item) { mutableStateOf(false) }

    LaunchedEffect(item) {
        currentItem = item
        if (item.type == "video") {
            isLoadingUrl = true
            val playableItem = viewModel.getPlayableVideoItem(item)
            currentItem = playableItem
            playUrl = playableItem.playUrl
            if (playableItem != item) {
                onItemUpdated(playableItem)
            }
            isLoadingUrl = false
        }
    }
    val displayItem = currentItem

    LaunchedEffect(isLoadingUrl, hasRetriedAfterFailure, currentItem) {
        if (!isLoadingUrl || !hasRetriedAfterFailure || currentItem.type != "video") {
            return@LaunchedEffect
        }
        val previousItem = currentItem
        val playableItem = viewModel.refreshPlayableVideoItem(currentItem)
        currentItem = playableItem
        playUrl = playableItem.playUrl
        if (playableItem != previousItem) {
            onItemUpdated(playableItem)
        }
        isLoadingUrl = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = displayItem.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 5,
                overflow = TextOverflow.Visible,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    copyPlainText(context, clipboardManager, displayItem.title)
                }
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "作者: ${displayItem.author}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                copyPlainText(context, clipboardManager, displayItem.author)
                            }
                        )
                        Text(
                            text = "ID: ${displayItem.videoId}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                copyPlainText(context, clipboardManager, displayItem.videoId)
                            }
                        )
                    }
                    val publishDateStr = remember(displayItem.timestamp) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(Date(displayItem.timestamp * 1000))
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
                    if (displayItem.type == "video") {
                        when {
                            isLoadingUrl -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            playUrl != null -> VideoPlayer(
                                url = playUrl!!,
                                cacheKey = displayItem.rawPlayUrl ?: displayItem.playUrl ?: playUrl!!,
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
                            model = displayItem.cover,
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
                            copyPlainText(context, clipboardManager, displayItem.inputUrl, "链接已复制")
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

                    if (displayItem.type == "video") {
                        val playUrlToCopy = displayItem.rawPlayUrl ?: displayItem.playUrl
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
                            if (displayItem.type == "video") {
                                viewModel.saveMedia(context, displayItem)
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
                            text = if (displayItem.type == "video") "保存视频" else "保存图片",
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
            if (onDelete != null) {
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
        }
    )

    if (showImageSaveDialog) {
        ParseItemImageSaveDialog(
            viewModel = viewModel,
            item = displayItem,
            onDismiss = { showImageSaveDialog = false },
            context = context
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParseItemImageSaveDialog(
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
                                androidx.compose.material3.Icon(
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
