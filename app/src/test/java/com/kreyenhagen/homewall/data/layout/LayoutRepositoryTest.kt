package com.kreyenhagen.homewall.data.layout

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler
class LayoutRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "wall_layout.json")

    private fun TestScope.repo(asset: String? = null) =
        LayoutRepository(file, readAsset = { asset }, io = UnconfinedTestDispatcher(testScheduler))

    private val LayoutRepository.layout get() = (state.value as LayoutState.Ready).layout

    @Test
    fun firstLaunchWithoutAssetUsesThePlaceholderWithoutSavingIt() = runTest {
        val repo = repo()
        repo.load()
        assertEquals(PlaceholderLayout.create(), repo.layout)
        assertFalse(file.exists())
    }

    @Test
    fun firstLaunchCopiesTheAsset() = runTest {
        val repo = repo(asset = DOC_EXAMPLE_JSON)
        repo.load()
        assertEquals(550, repo.layout.ledCount)
        assertTrue(file.exists())
        assertEquals(repo.layout, (LayoutJson.parse(file.readText()) as LayoutJson.ParseResult.Ok).layout)
    }

    @Test
    fun theSavedFileWinsOverTheAsset() = runTest {
        file.writeText(LayoutJson.encode(PlaceholderLayout.create().copy(placeholder = false)))
        val repo = repo(asset = DOC_EXAMPLE_JSON)
        repo.load()
        assertEquals(154, repo.layout.ledCount)
    }

    @Test
    fun updatesAreSavedAndReloadIdentically() = runTest {
        val repo = repo()
        repo.load()
        val result = repo.update { it.resized(95f, listOf(16f, 98f)) }
        assertEquals(LayoutRepository.UpdateResult.Saved, result)

        val reloaded = repo()
        reloaded.load()
        assertEquals(repo.layout, reloaded.layout)
        assertEquals(95f, reloaded.layout.wall.widthIn)
        assertFalse(File(tmp.root, "wall_layout.json.tmp").exists())
    }

    @Test
    fun invalidUpdatesAreRejectedAndNothingChanges() = runTest {
        val repo = repo()
        repo.load()
        val before = repo.layout
        val result = repo.update { it.copy(hiddenLeds = listOf(0)) } // LED 0 belongs to hold 0
        assertTrue(result is LayoutRepository.UpdateResult.Rejected)
        assertEquals(before, repo.layout)
        assertFalse(file.exists())
    }

    @Test
    fun aBrokenFileIsReportedAndNeverOverwritten() = runTest {
        val broken = DOC_EXAMPLE_JSON.replace("\"leds\": [40]", "\"leds\": [7]")
        file.writeText(broken)
        val repo = repo()
        repo.load()

        val state = repo.state.value as LayoutState.Invalid
        assertEquals(listOf("wall_layout.json: LED 7 is in both hold 7 and hold 40."), state.problems)

        val result = repo.update { it }
        assertTrue(result is LayoutRepository.UpdateResult.Rejected)
        assertEquals(broken, file.readText())
    }
}
