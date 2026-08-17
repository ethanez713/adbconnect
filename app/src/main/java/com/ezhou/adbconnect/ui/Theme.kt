package com.ezhou.adbconnect.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF3B7DD8)
private val AccentOnDark = Color(0xFF8AB4F8)

private val Dark = darkColorScheme(
    primary = AccentOnDark,
    onPrimary = Color(0xFF0B1E33),
    background = Color(0xFF101014),
    onBackground = Color(0xFFECEEF1),
    surface = Color(0xFF101014),
    onSurface = Color(0xFFECEEF1),
    surfaceVariant = Color(0xFF1B1E24),
    onSurfaceVariant = Color(0xFFAEB4BE),
)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFFAFAFB),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F1F3),
    onSurfaceVariant = Color(0xFF5C6069),
)

@Composable
fun AdbConnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content,
    )
}
