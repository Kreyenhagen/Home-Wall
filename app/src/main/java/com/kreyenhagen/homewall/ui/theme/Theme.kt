package com.kreyenhagen.homewall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark: the wall photo and LED ring colors read better on dark (NORTH_STAR §3).
@Composable
fun HomeWallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
