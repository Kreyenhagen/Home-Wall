package com.kreyenhagen.homewall.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.AccessibilityManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnackbarsTest {

    private val host = SnackbarHostState()
    private val showing: String? get() = host.currentSnackbarData?.visuals?.message

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun aPlainMessageGoesAwayAfterTwoSeconds() = runTest {
        val result = async { host.showBriefly("Layout exported", accessibility = null) }
        runCurrent()
        assertEquals("Layout exported", showing)
        advance(SNACKBAR_MILLIS - 1)
        assertEquals("Layout exported", showing)
        advance(1)
        assertNull(showing)
        assertEquals(SnackbarResult.Dismissed, result.await())
    }

    @Test
    fun oneWithAButtonGoesAwayAfterTwoSecondsToo() = runTest {
        // Material would keep it up until it's tapped.
        val result = async { host.showBriefly("Couldn't connect", accessibility = null, actionLabel = "Retry") }
        runCurrent()
        assertEquals("Retry", host.currentSnackbarData?.visuals?.actionLabel)
        advance(SNACKBAR_MILLIS)
        assertNull(showing)
        assertEquals(SnackbarResult.Dismissed, result.await())
    }

    @Test
    fun tappingTheButtonIsReported() = runTest {
        val result = async { host.showBriefly("Tile deleted", accessibility = null, actionLabel = "Undo") }
        advance(500)
        host.currentSnackbarData?.performAction()
        runCurrent()
        assertNull(showing)
        assertEquals(SnackbarResult.ActionPerformed, result.await())
    }

    @Test
    fun aNewerMessageReplacesTheOneShowingAndGetsItsOwnTwoSeconds() = runTest {
        val first = async { host.showBriefly("LED 37 paired with LED 36", accessibility = null, actionLabel = "Undo") }
        advance(1_500)
        val second = async { host.showBriefly("LED 38 paired with LED 36", accessibility = null, actionLabel = "Undo") }
        runCurrent()
        assertEquals("LED 38 paired with LED 36", showing)
        assertEquals(SnackbarResult.Dismissed, first.await())
        advance(SNACKBAR_MILLIS - 1)
        assertEquals("LED 38 paired with LED 36", showing)
        advance(1)
        assertNull(showing)
        assertEquals(SnackbarResult.Dismissed, second.await())
    }

    @Test
    fun accessibilitySettingsCanAskForLonger() = runTest {
        var askedWithControls: Boolean? = null
        val slower = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(
                originalTimeoutMillis: Long,
                containsIcons: Boolean,
                containsText: Boolean,
                containsControls: Boolean,
            ): Long {
                askedWithControls = containsControls
                return originalTimeoutMillis * 3
            }
        }
        val result = async { host.showBriefly("Tile deleted", slower, actionLabel = "Undo") }
        advance(SNACKBAR_MILLIS * 3 - 1)
        assertEquals("Tile deleted", showing)
        advance(1)
        assertNull(showing)
        assertEquals(SnackbarResult.Dismissed, result.await())
        assertTrue(askedWithControls == true)
    }
}
