package com.jn.dyparse.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Padding needed by content that scrolls underneath the floating navigation bar.
 *
 * The navigation bar is intentionally kept as an overlay, so pages must reserve
 * its occupied area in their own scroll container. Keep these values in sync
 * with the existing bar placement in MainScreen without changing the bar itself.
 */
@Composable
fun floatingBottomBarContentPadding(
    horizontal: Dp = 0.dp,
    top: Dp = 0.dp,
    bottom: Dp = 16.dp
): PaddingValues {
    val navigationBottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    // LiquidBottomTabs is 64.dp high and sits 12.dp above the navigation bar.
    // Keep a small breathing room so the last row/button can be tapped easily.
    val safeBottom = navigationBottom + 64.dp + 12.dp + bottom
    return remember(horizontal, top, bottom, navigationBottom) {
        PaddingValues(
            start = horizontal,
            top = top,
            end = horizontal,
            bottom = safeBottom
        )
    }
}
