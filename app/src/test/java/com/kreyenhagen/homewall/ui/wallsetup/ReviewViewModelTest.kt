package com.kreyenhagen.homewall.ui.wallsetup

import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.LayoutJson
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.layoutOf
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.led.LedEntry
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val layout = layoutOf(
        Hold(0, 10f, 5f, listOf(0)),
        Hold(7, 20f, 30f, listOf(7, 8)),
        ledCount = 10,
        hidden = listOf(1, 2),
    )
    private lateinit var repo: LayoutRepository

    private suspend fun TestScope.review(): ReviewViewModel {
        val file = File(tmp.root, "wall_layout.json").apply { writeText(LayoutJson.encode(layout)) }
        repo = LayoutRepository(file, readAsset = { null }, io = UnconfinedTestDispatcher(testScheduler))
        repo.load()
        val vm = ReviewViewModel(
            repo,
            MutableStateFlow(WallPhotoState(loaded = true)),
            MutableStateFlow(AppSettings()),
        )
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    private val ReviewViewModel.state get() = uiState.value
    private fun saved(id: Int) = (repo.state.value as LayoutState.Ready).layout.holds.firstOrNull { it.id == id }

    @Test
    fun whatTheWallShows() {
        val pair = layout.holds[1]
        assertEquals(LedFrame.OFF, ReviewViewModel.frameFor(layout, null, lightAll = false))
        assertEquals(
            LedFrame(listOf(LedEntry(7, Palette.SLOT_CURSOR), LedEntry(8, Palette.SLOT_CURSOR))),
            ReviewViewModel.frameFor(layout, pair, lightAll = false),
        )
        // Light all holds: every hold dim, the identified one still bright.
        assertEquals(
            LedFrame(listOf(LedEntry(0, Palette.SLOT_ALL_HOLDS), LedEntry(7, Palette.SLOT_CURSOR), LedEntry(8, Palette.SLOT_CURSOR))),
            ReviewViewModel.frameFor(layout, pair, lightAll = true),
        )
    }

    @Test
    fun tappingAHoldIdentifiesIt() = runTest {
        val vm = review()
        assertEquals("10 LEDs · 2 holds (3 LEDs) · 2 hidden", vm.state.summary?.text)
        assertEquals(5, vm.state.summary?.unmapped)

        vm.tapHold(7)
        assertEquals(7, vm.state.selected?.id)
        assertEquals("x 20.0, y 30.0 in (Main)", vm.state.selectedPosition)
        assertEquals(LedFrame(listOf(LedEntry(7, Palette.SLOT_CURSOR), LedEntry(8, Palette.SLOT_CURSOR))), vm.state.ledFrame)

        vm.setLightAll(true)
        assertEquals(3, vm.state.ledFrame.entries.size)
        vm.tapEmpty()
        assertNull(vm.state.selected)
        assertEquals(LedFrame(listOf(LedEntry(0, 5), LedEntry(7, 5), LedEntry(8, 5))), vm.state.ledFrame)
    }

    @Test
    fun movingAHoldIsShownWhileDraggingAndSavedOnSave() = runTest {
        val vm = review()
        vm.tapHold(0)
        vm.startMove()
        assertTrue(vm.state.moving)
        vm.dragBy(2f, 3f)
        vm.dragBy(0.5f, 0f)
        assertEquals(12.5f, vm.state.selected?.x)
        assertEquals(8f, vm.state.selected?.y)
        assertEquals(10f, saved(0)?.x) // not saved yet

        vm.tapHold(7) // taps don't change the selection while moving
        assertEquals(0, vm.state.selected?.id)

        vm.saveMove()
        assertFalse(vm.state.moving)
        assertEquals(Hold(0, 12.5f, 8f, listOf(0)), saved(0))
        assertEquals("Hold 0 moved", vm.state.message?.text)
    }

    @Test
    fun cancellingAMoveLeavesTheHoldWhereItWas() = runTest {
        val vm = review()
        vm.tapHold(0)
        vm.startMove()
        vm.dragBy(-50f, 0f) // stops at the wall's edge
        assertEquals(0f, vm.state.selected?.x)
        vm.cancelMove()
        assertEquals(10f, vm.state.selected?.x)
        assertEquals(10f, saved(0)?.x)
    }

    @Test
    fun removingAnLedAndDeletingAHold() = runTest {
        val vm = review()
        vm.tapHold(7)
        vm.removeLed(8)
        assertEquals(listOf(7), saved(7)?.leds)
        assertEquals("LED 8 removed from hold 7. It's unmapped now.", vm.state.message?.text)
        vm.removeLed(7) // the last LED can't be removed this way; that's Delete hold
        assertEquals(listOf(7), saved(7)?.leds)

        vm.requestDelete()
        assertTrue(vm.state.confirmDelete)
        vm.confirmDelete()
        assertNull(saved(7))
        assertNull(vm.state.selected)
        assertEquals("Hold 7 deleted. LED 7 is unmapped now.", vm.state.message?.text)
    }

}
