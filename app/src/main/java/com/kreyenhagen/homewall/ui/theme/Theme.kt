package com.kreyenhagen.homewall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Connect button states (§8.1). */
object StatusColors {
    val Connected = Color(0xFF00E676)
    val Busy = Color(0xFFFFB300)
    val Simulated = Color(0xFF26C6DA)
}

// Always dark: the wall photo and LED ring colors read better on dark (§3).
@Composable
fun HomeWallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
