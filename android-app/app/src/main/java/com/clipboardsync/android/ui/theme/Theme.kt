package com.clipboardsync.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = lightColorScheme(
    primary = Blue700,
    onPrimary = Paper50,
    primaryContainer = Paper100,
    onPrimaryContainer = Ink900,
    secondary = Green700,
    onSecondary = Paper50,
    surface = Paper50,
    surfaceVariant = Paper100,
    onSurface = Ink900,
    onSurfaceVariant = Ink700,
    background = Paper50,
    onBackground = Ink900,
    outline = Color(0xFFD3DAE2)
)

@Composable
fun ClipboardSyncTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = Typography,
        content = content
    )
}
