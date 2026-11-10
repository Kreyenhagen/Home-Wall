package com.kreyenhagen.homewall.ui.wallsetup

import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.LayoutJson
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.layoutOf
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.led.LedEntry
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.ui.wall.WallPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MapLedsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private lateinit var repo: LayoutRepository

    /** Null [layout] means no file: the placeholder. */
    private suspend fun TestScope.mapper(layout: WallLayout? = null): MapLedsViewModel {
        val file = File(tmp.root, "wall_layout.json")
        if (layout != null) file.writeText(LayoutJson.encode(layout))
        repo = LayoutRepository(file, readAsset = { null }, io = UnconfinedTestDispatcher(testScheduler))
        repo.load()
        val vm = MapLedsViewModel(repo, MutableStateFlow(WallPhotoState(loaded = true)), MutableStateFlow(AppSettings()), connection)
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    private val MapLedsViewModel.state get() = uiState.value
    private val saved get() = (repo.state.value as LayoutState.Ready).layout
    private fun saved(id: Int) = saved.holds.firstOrNull { it.id == id }

    @Test
    fun thePlaceholderAsksForTheLedCountFirst() = runTest {
        val vm = mapper()
        assertTrue(vm.state.needsLedCount)
        assertEquals(550, vm.state.suggestedLedCount)
        assertEquals("the default", vm.state.suggestedLedCountSource)
        assertNull(vm.state.ledFrame) // the wall keeps the last route until mapping starts

        connection.value = ConnectionState.Connected("HomeWall", 548)
        assertEquals(548, vm.state.suggestedLedCount)
        assertEquals("from the controller", vm.state.suggestedLedCountSource)
    }

    @Test
    fun startingLightsLedZeroInTheCursorSlot() = runTest {
        val vm = mapper()
        vm.startMapping(550)
        assertFalse(vm.state.needsLedCount)
        assertFalse(saved.placeholder)
        assertEquals(550, saved.ledCount)
        assertEquals(0, vm.state.current)
        assertEquals("Not mapped yet", vm.state.status)
        assertEquals(LedFrame(listOf(LedEntry(0, Palette.SLOT_CURSOR))), vm.state.ledFrame)
    }

    @Test
    fun tappingAnEmptySpotMakesAHoldAndMovesOn() = runTest {
        val vm = mapper()
        vm.startMapping(550)
        vm.tapEmpty(WallPoint(10f, 5f))
        assertEquals(Hold(0, 10f, 5f, listOf(0)), saved(0))
        assertEquals(1, vm.state.current)
        assertEquals(LedFrame(listOf(LedEntry(1, Palette.SLOT_CURSOR))), vm.state.ledFrame)

        vm.tapEmpty(WallPoint(-2f, 5f)) // beside the wall
        assertEquals("Tap on the wall.", vm.state.message?.text)
        assertEquals(1, vm.state.current)
        assertEquals(1, saved.holds.size)
    }

    @Test
    fun tappingAHoldPairsTheLedAndCanBeUndone() = runTest {
        val vm = mapper()
        vm.startMapping(550)
        vm.tapEmpty(WallPoint(10f, 5f))
        vm.tapHold(0)
        assertEquals(listOf(0, 1), saved(0)?.leds)
        assertEquals(2, vm.state.current)
        assertEquals("LED 1 added to hold 0", vm.state.message?.text)
        assertNotNull(vm.state.undo)

        vm.undo()
        assertEquals(listOf(0), saved(0)?.leds)
        assertEquals(1, vm.state.current) // back on the LED that was undone
        assertEquals("Not mapped yet", vm.state.status)
    }

    @Test
    fun tappingTheHoldTheLedIsAlreadyInJustMovesOn() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0)), ledCount = 10))
        vm.jumpTo("0")
        vm.tapHold(0)
        assertEquals("LED 0 is already in hold 0.", vm.state.message?.text)
        assertEquals(1, vm.state.current)
        assertEquals(listOf(0), saved(0)?.leds)
    }

    @Test
    fun hiddenAndTheLastLed() = runTest {
        val vm = mapper(layoutOf(ledCount = 3))
        vm.hide()
        assertEquals(listOf(0), saved.hiddenLeds)
        assertEquals(1, vm.state.current)
        vm.next()
        vm.hide()
        assertEquals("That was the last LED (LED 2).", vm.state.message?.text)
        assertEquals(2, vm.state.current)
        assertEquals("Hidden", vm.state.status)
    }

    @Test
    fun resumesAtTheFirstUnmappedLed() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0, 1)), Hold(3, 20f, 5f, listOf(3)), hidden = listOf(2)))
        assertEquals(4, vm.state.current)
        assertEquals("550 LEDs · 2 holds (3 LEDs) · 1 hidden", vm.state.summary?.text)
    }

    @Test
    fun remappingAPairedLedReportsItsHold() = runTest {
        // §6.1's example: hold 37 owns [37, 38] and LED 37 is remapped.
        val vm = mapper(layoutOf(Hold(37, 10f, 20f, listOf(37, 38)), ledCount = 100))
        vm.jumpTo("37")
        assertEquals("In hold 37 with LED 38", vm.state.status)
        assertEquals(mapOf(37 to androidx.compose.ui.graphics.Color.White), vm.state.rings)

        vm.tapEmpty(WallPoint(50f, 50f))
        assertEquals(listOf(38), saved(37)?.leds) // hold 37 keeps its id with LED 38
        assertEquals(listOf(37), saved(100)?.leds) // so the new hold gets the lowest unused id ≥ ledCount
    }

    @Test
    fun prevNextAndJumpStayInRange() = runTest {
        val vm = mapper(layoutOf(ledCount = 5))
        vm.previous()
        assertEquals(0, vm.state.current)
        assertFalse(vm.jumpTo("5"))
        assertEquals("Enter an LED number from 0 to 4.", vm.state.message?.text)
        assertFalse(vm.jumpTo("abc"))
        assertTrue(vm.jumpTo(" 4 "))
        vm.next()
        assertEquals(4, vm.state.current)
        assertFalse(vm.state.canGoForward)
    }

    @Test
    fun theLedCountCanChangeLater() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0)), Hold(8, 10f, 5f, listOf(8)), ledCount = 10))
        vm.changeLedCount(5)
        assertEquals(5, saved.ledCount)
        assertNull(saved(8))
        assertEquals(5, vm.state.ledCount)
    }

    @Test
    fun goToFirstUnmappedFromReview() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0)), ledCount = 3, hidden = listOf(2)))
        vm.next()
        vm.next()
        vm.goToFirstUnmapped()
        assertEquals(1, vm.state.current)

        vm.hide()
        vm.goToFirstUnmapped()
        assertEquals("Every LED is mapped.", vm.state.message?.text)
    }
}
