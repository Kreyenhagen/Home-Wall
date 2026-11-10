package com.kreyenhagen.homewall

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.kreyenhagen.homewall.ui.HomeWallNavHost
import com.kreyenhagen.homewall.ui.LocalAppContainer
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so keep light system-bar icons even when the system is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as App).container
        setContent {
            HomeWallTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    HomeWallNavHost()
                }
            }
        }
    }
}
