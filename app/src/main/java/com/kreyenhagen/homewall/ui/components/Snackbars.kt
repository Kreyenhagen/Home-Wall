package com.kreyenhagen.homewall.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.AccessibilityManager
import kotlinx.coroutines.withTimeoutOrNull

/** How long every snackbar in the app stays up. Android's accessibility "Time to take action" setting can make it longer. */
const val SNACKBAR_MILLIS = 2_000L

/**
 * Shows [text], with an optional [actionLabel] button such as Undo or Retry, for [SNACKBAR_MILLIS]
 * and then hides it. Every snackbar in the app goes through here: Material's own durations are 4 s,
 * 10 s, or until tapped for one with a button.
 *
 * A snackbar that's already up is dismissed first, so a newer message replaces it instead of
 * waiting its turn. [accessibility] is `LocalAccessibilityManager.current`.
 *
 * Returns [SnackbarResult.ActionPerformed] if the button was tapped, otherwise [SnackbarResult.Dismissed].
 */
suspend fun SnackbarHostState.showBriefly(
    text: String,
    accessibility: AccessibilityManager?,
    actionLabel: String? = null,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    val millis = accessibility?.calculateRecommendedTimeoutMillis(
        SNACKBAR_MILLIS,
        containsText = true,
        containsControls = actionLabel != null,
    ) ?: SNACKBAR_MILLIS
    // Timing out cancels showSnackbar, which takes the snackbar down.
    return withTimeoutOrNull(millis) {
        showSnackbar(text, actionLabel = actionLabel, duration = SnackbarDuration.Indefinite)
    } ?: SnackbarResult.Dismissed
}
