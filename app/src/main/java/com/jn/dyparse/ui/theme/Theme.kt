package com.jn.dyparse.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9DCCFF),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF164A70),
    onPrimaryContainer = Color(0xFFD1E8FF),
    secondary = Color(0xFFB8C8DA),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceContainer = Color(0xFF1D252D),
    surfaceContainerLow = Color(0xFF171D23),
    surfaceVariant = Color(0xFF41484F),
    onSurfaceVariant = Color(0xFFC1C7CE)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00639A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBE6FF),
    onPrimaryContainer = Color(0xFF001D32),
    secondary = Color(0xFF4F6072),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFEFF3F8),
    surfaceContainerLow = Color(0xFFF2F5F9),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484F)
)

@Composable
fun DyparseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            // Only the status bar is edge-to-edge. Keep the navigation bar
            // opaque so the existing bottom content remains in its position.
            window.navigationBarColor = colorScheme.background.toArgb()
            
            WindowCompat.setDecorFitsSystemWindows(window, false)
            
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
