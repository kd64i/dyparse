package com.jn.dyparse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jn.dyparse.ParserViewModel
import com.jn.dyparse.VideoPlayer
import com.jn.dyparse.data.GalleryMedia

@Composable
fun LivePhotoPreviewDialog(
    viewModel: ParserViewModel,
    media: GalleryMedia,
    onDismiss: () -> Unit
) {
    var playUrl by remember(media) { mutableStateOf<String?>(null) }
    var isLoading by remember(media) { mutableStateOf(true) }
    var hasRetriedAfterFailure by remember(media) { mutableStateOf(false) }

    LaunchedEffect(media) {
        isLoading = true
        playUrl = viewModel.getPlayableLivePhotoUrl(media)
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator()
                    !playUrl.isNullOrBlank() -> VideoPlayer(
                        url = playUrl!!,
                        cacheKey = media.livePhotoRawUrl ?: playUrl!!,
                        onPlaybackFailure = {
                            if (isLoading || hasRetriedAfterFailure) {
                                return@VideoPlayer
                            }
                            hasRetriedAfterFailure = true
                            isLoading = true
                        }
                    )
                }
            }
        }
    }

    LaunchedEffect(isLoading, hasRetriedAfterFailure, media) {
        if (!isLoading || !hasRetriedAfterFailure) {
            return@LaunchedEffect
        }
        playUrl = viewModel.refreshPlayableLivePhotoUrl(media)
        isLoading = false
    }
}
