package com.jn.dyparse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jn.dyparse.SaveState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.roundToInt

/**
 * 保存进度浮层：液态玻璃样式，与底部 Tab 栏视觉一致。
 * - 底盘（容器）：复刻底栏 —— vibrancy + blur + lens + highlight + shadow + 容器底色
 * - 实时进度条：复刻 tab 滑块长按效果 —— lens(depthEffect + chromaticAberration) + highlight + innerShadow
 * - 位于底部 Tab 栏上方，不被遮挡。
 */
@Composable
fun SaveProgressOverlay(
    saveState: SaveState,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val lastActiveState = remember { mutableStateOf(saveState) }

    LaunchedEffect(saveState) {
        if (saveState.isSaving) {
            lastActiveState.value = saveState
        }
    }

    val renderState = if (saveState.isSaving) saveState else lastActiveState.value
    val animatedProgress = if (renderState.indeterminate) {
        0f
    } else {
        val progress by animateFloatAsState(
            targetValue = renderState.progress.coerceIn(0f, 1f),
            animationSpec = tween(
                durationMillis = 220,
                easing = LinearEasing
            ),
            label = "save_progress"
        )
        progress
    }

    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
    val progressBackdrop = rememberCombinedBackdrop(
        backdrop,
        rememberLayerBackdrop()
    )

    AnimatedVisibility(
        visible = saveState.isSaving,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        // 胶囊宽度 = 传入的 modifier 宽度（与 tab 底栏一致，自适应结果），
        // 不在内部追加 fillMaxWidth，避免覆盖固定宽度导致撑满全屏。
        Box(
            modifier = modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(4f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = 0.75f)
                    },
                    shadow = {
                        Shadow(
                            radius = 10.dp,
                            color = Color.Black,
                            alpha = if (isLightTheme) 0.1f else 0.2f
                        )
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 文字提示：小字号，位于进度条上方
                Text(
                    text = buildString {
                        append(
                            if (renderState.total > 1) {
                                "${renderState.label} (${renderState.current}/${renderState.total})"
                            } else {
                                renderState.label
                            }
                        )
                        if (!renderState.indeterminate) {
                            append("  ${(animatedProgress * 100).roundToInt()}%")
                            // 视频保存时显示已下载/总大小
                            if (renderState.totalBytes > 0) {
                                append(
                                    "（${formatBytes(renderState.downloadedBytes)}/" +
                                        "${formatBytes(renderState.totalBytes)}）"
                                )
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))

                // 进度轨道：半透明胶囊底，居中
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .drawBackdrop(
                            backdrop = progressBackdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                blur(3f.dp.toPx())
                            },
                            onDrawSurface = {
                                drawRect(Color.Black.copy(alpha = 0.12f))
                            }
                        )
                ) {
                    // 实时进度：液态玻璃填充（复刻 tab 滑块长按效果）
                    val progressFraction = if (renderState.indeterminate) 1f else animatedProgress
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                            .drawBackdrop(
                                backdrop = progressBackdrop,
                                shape = { Capsule() },
                                effects = {
                                    lens(
                                        10f.dp.toPx(),
                                        14f.dp.toPx(),
                                        depthEffect = true,
                                        chromaticAberration = true
                                    )
                                },
                                highlight = {
                                    Highlight.Default.copy(alpha = 1f)
                                },
                                innerShadow = {
                                    InnerShadow(radius = 8f.dp, alpha = 1f)
                                },
                                onDrawSurface = {
                                    drawRect(
                                        if (isLightTheme) Color.Black.copy(alpha = 0.1f)
                                        else Color.White.copy(alpha = 0.1f)
                                    )
                                    drawRect(Color.Black.copy(alpha = 0.03f))
                                }
                            )
                    )
                }
            }
        }
    }
}

/** 字节数格式化为可读大小（B / KB / MB / GB） */
private fun formatBytes(bytes: Long): String {
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