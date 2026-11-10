package com.kreyenhagen.homewall.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.ui.LocalAppContainer

/** What a screen wants on the wall (§9.2). */
sealed interface WallLeds {
    /** Screens without LED content of their own keep the last displayed route lit. */
    data object LastRoute : WallLeds

    /** Route Display: shown, and remembered as the last route. */
    data class Route(val frame: LedFrame) : WallLeds

    /** Anything else: the editor's draft, Wall Setup's cursor. */
    data class Frame(val frame: LedFrame) : WallLeds
}

/**
 * Drives the wall from a screen (§9.2). Pushes only while the screen's back-stack entry is
 * RESUMED, again whenever [leds] changes, and again when the screen resumes. Null leaves the wall
 * alone (e.g. while the screen is still loading).
 *
 * It never clears the LEDs when the screen goes away: during a navigation transition that could
 * run after the next screen has already set its frame.
 */
@Composable
fun DriveLeds(leds: WallLeds?) {
    val sync = LocalAppContainer.current.ledSync
    LifecycleResumeEffect(leds, sync) {
        when (leds) {
            WallLeds.LastRoute -> sync.showLastRoute()
            is WallLeds.Route -> sync.showRoute(leds.frame)
            is WallLeds.Frame -> sync.show(leds.frame)
            null -> Unit
        }
        onPauseOrDispose { }
    }
}
