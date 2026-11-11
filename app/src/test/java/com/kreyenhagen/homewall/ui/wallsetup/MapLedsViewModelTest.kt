package com.kreyenhagen.homewall.ui.wallsetup

import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.KNOWN_HIDDEN_LEDS
import com.kreyenhagen.homewall.data.layout.LayoutJson
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.OffList
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.firstUnmappedLed
import com.kreyenhagen.homewall.data.layout.layoutOf
import com.kreyenhagen.homewall.data.layout.ledRanges
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
    fun startingMarksTheKnownHiddenLedsAndLightsTheFirstLedToMap() = runTest {
        val vm = mapper()
        vm.startMapping(550)
        assertFalse(vm.state.needsLedCount)
        assertFalse(saved.placeholder)
        assertEquals(550, saved.ledCount)
        assertEquals(KNOWN_HIDDEN_LEDS, saved.hiddenLeds)
        assertEquals(41, vm.state.current) // LEDs 0–40 are behind the wall
        assertEquals("Not mapped yet", vm.state.status)
        assertEquals(LedFrame(listOf(LedEntry(41, Palette.SLOT_CURSOR))), vm.state.ledFrame)
    }

    @Test
    fun tappingAnEmptySpotMakesAHoldAndMovesOn() = runTest {
        val vm = mapper(layoutOf())
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
        val vm = mapper(layoutOf())
        vm.tapEmpty(WallPoint(10f, 5f))
        vm.tapHold(0)
        assertEquals(listOf(0, 1), saved(0)?.leds)
        assertEquals(2, vm.state.current)
        assertEquals("LED 1 paired with LED 0", vm.state.message?.text)
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
        assertEquals("LED 0 is already in that tile.", vm.state.message?.text)
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
    fun movingOnAndPrevAndNextSkipHiddenLeds() = runTest {
        val vm = mapper(layoutOf(ledCount = 10, hidden = listOf(1, 2, 5)))
        assertEquals(0, vm.state.current)
        assertFalse(vm.state.canGoBack)
        vm.tapEmpty(WallPoint(10f, 5f))
        assertEquals(3, vm.state.current)
        vm.hide()
        assertEquals(4, vm.state.current)
        vm.tapEmpty(WallPoint(20f, 5f))
        assertEquals(6, vm.state.current)
        vm.previous()
        assertEquals(4, vm.state.current) // past Hidden LED 5
        vm.previous()
        assertEquals(0, vm.state.current) // past Hidden LEDs 3, 2 and 1
        vm.next()
        assertEquals(4, vm.state.current)
    }

    @Test
    fun hiddenLedsCantBePlacedPairedOrMarkedUnused() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0)), ledCount = 10, hidden = listOf(5)))
        vm.jumpTo("5") // the jump field can still show a Hidden LED
        assertEquals("Hidden", vm.state.status)
        val locked = "LED 5 is on the Hidden list, so it can't be used. To use it, take it out with Edit Hidden list."

        vm.tapEmpty(WallPoint(30f, 5f))
        assertEquals(locked, vm.state.message?.text)
        vm.tapHold(0)
        assertEquals(locked, vm.state.message?.text)
        vm.markUnused()
        assertEquals(locked, vm.state.message?.text)

        assertEquals(listOf(5), saved.hiddenLeds) // nothing changed
        assertEquals(listOf(Hold(0, 10f, 5f, listOf(0))), saved.holds)
        assertTrue(saved.unusedLeds.isEmpty())
        assertEquals(5, vm.state.current)

        // Taken out of the Hidden list, it can be placed again.
        assertTrue(vm.saveOffList(OffList.HIDDEN, ""))
        vm.tapEmpty(WallPoint(30f, 5f))
        assertEquals(listOf(5), saved(5)?.leds)
    }

    @Test
    fun savingTheHiddenListMovesOffACurrentLedThatsNowHidden() = runTest {
        val vm = mapper(layoutOf(ledCount = 10))
        vm.jumpTo("3")
        assertTrue(vm.saveOffList(OffList.HIDDEN, "3, 4"))
        assertEquals(5, vm.state.current)
        vm.undo()
        assertEquals(3, vm.state.current)
    }

    @Test
    fun movingOnFromTheLastLedThatIsntHiddenOrUnused() = runTest {
        val vm = mapper(layoutOf(ledCount = 5, hidden = listOf(3), unused = listOf(4)))
        vm.jumpTo("2")
        vm.hide()
        assertEquals("That was the last LED to map. The LEDs after LED 2 are Hidden or Unused.", vm.state.message?.text)
        assertEquals(2, vm.state.current)
    }

    // ---- Unused, and editing the Hidden and Unused lists ----

    @Test
    fun unusedMarksAnLedWithNoHoldAndMovingOnSkipsIt() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0)), ledCount = 10, unused = listOf(3)))
        assertEquals(1, vm.state.current)
        vm.markUnused()
        assertEquals(listOf(1, 3), saved.unusedLeds)
        assertEquals(2, vm.state.current)
        vm.tapEmpty(WallPoint(20f, 5f))
        assertEquals(4, vm.state.current) // LED 3 is Unused, so it's skipped
        vm.previous()
        assertEquals("Unused", vm.state.status)
        assertEquals("10 LEDs · 2 holds (2 LEDs) · 0 hidden · 2 unused", vm.state.summary?.text)

        vm.jumpTo("0")
        vm.markUnused() // a tile that turned out to have no hold: its LED comes out of the tile
        assertNull(saved(0))
        assertEquals(listOf(0, 1, 3), saved.unusedLeds)
    }

    @Test
    fun theHiddenListCanBeEdited() = runTest {
        val vm = mapper(layoutOf(Hold(42, 10f, 5f, listOf(42)), hidden = KNOWN_HIDDEN_LEDS, unused = listOf(50)))
        assertEquals(ledRanges(KNOWN_HIDDEN_LEDS), vm.state.offListText(OffList.HIDDEN))
        assertEquals("50", vm.state.offListText(OffList.UNUSED))

        assertEquals("Enter LED numbers separated by commas, and ranges like 0–40.", vm.offListProblem(OffList.HIDDEN, "0-"))
        assertEquals("LED 42 is in a tile. To make it Hidden, go to LED 42 and tap Hidden.", vm.offListProblem(OffList.HIDDEN, "0–42"))
        assertEquals("LED 50 is in the Unused list. Take it out of that list first.", vm.offListProblem(OffList.HIDDEN, "50"))
        assertNull(vm.offListProblem(OffList.HIDDEN, "0–41, 45"))

        assertFalse(vm.saveOffList(OffList.HIDDEN, "42")) // refused: the editor stays open and nothing changes
        assertEquals(KNOWN_HIDDEN_LEDS, saved.hiddenLeds)

        assertTrue(vm.saveOffList(OffList.HIDDEN, "0–41, 45"))
        assertEquals((0..41).toList() + 45, saved.hiddenLeds)
        assertEquals("Hidden list saved: 43 LEDs", vm.state.message?.text)
        assertEquals(43, saved.firstUnmappedLed()) // the LEDs taken out are unmapped now

        vm.undo()
        assertEquals(KNOWN_HIDDEN_LEDS, saved.hiddenLeds)

        // The behind-the-wall list can be added back to whatever is in the field.
        assertEquals(ledRanges(KNOWN_HIDDEN_LEDS + 41), vm.withKnownHidden("41"))
        assertNull(vm.withKnownHidden(ledRanges(KNOWN_HIDDEN_LEDS))) // nothing to add
        assertNull(vm.withKnownHidden("abc"))
    }

    @Test
    fun theUnusedListCanBeEmptied() = runTest {
        val vm = mapper(layoutOf(ledCount = 10, unused = listOf(2, 3)))
        assertTrue(vm.saveOffList(OffList.UNUSED, " "))
        assertEquals(emptyList<Int>(), saved.unusedLeds)
        assertEquals("Unused list saved: no LEDs", vm.state.message?.text)
        assertTrue(vm.saveOffList(OffList.UNUSED, "")) // unchanged: nothing to save, but the editor can close
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
        assertEquals("Paired with LED 38", vm.state.status)
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

    // ---- Press and hold a tile ----

    private fun cursor(vararg leds: Int) = LedFrame(leds.map { LedEntry(it, Palette.SLOT_CURSOR) })

    @Test
    fun holdingATileLightsItsLedsAndDraggingMovesIt() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0, 1)), ledCount = 10))
        assertEquals(cursor(2), vm.state.ledFrame)

        vm.pressTile(0)
        assertEquals(cursor(0, 1), vm.state.ledFrame) // the wall shows which tile is held
        assertEquals(mapOf(0 to androidx.compose.ui.graphics.Color.White), vm.state.rings)

        vm.dragTile(2f, 3f)
        vm.dragTile(1f, 1f)
        val shown = vm.state.layout?.holds?.single()
        assertEquals(13f, shown?.x)
        assertEquals(9f, shown?.y)
        assertEquals(Hold(0, 10f, 5f, listOf(0, 1)), saved(0)) // not saved until the finger lifts

        vm.endTileDrag()
        assertEquals(Hold(0, 13f, 9f, listOf(0, 1)), saved(0))
        assertNull(vm.state.tileAction)
        assertEquals(cursor(2), vm.state.ledFrame) // back to the current LED
        assertEquals(2, vm.state.current)
    }

    @Test
    fun aDraggedTileStaysOnTheWallAndAnInterruptedDragChangesNothing() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0)), ledCount = 10))
        vm.pressTile(0)
        vm.dragTile(-50f, 500f)
        assertEquals(0f, vm.state.layout?.holds?.single()?.x)
        assertEquals(114f, vm.state.layout?.holds?.single()?.y)

        vm.closeTile()
        assertEquals(Hold(0, 10f, 5f, listOf(0)), vm.state.layout?.holds?.single())
        assertEquals(Hold(0, 10f, 5f, listOf(0)), saved(0))
    }

    @Test
    fun holdingAndLettingGoOpensTheMenu() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0)), ledCount = 10))
        vm.pressTile(0)
        vm.releaseTile(0)
        assertEquals(0, vm.state.menuTile?.id)
        assertEquals(cursor(0), vm.state.ledFrame)

        // While the menu is open, a tap doesn't map the current LED.
        vm.tapEmpty(WallPoint(50f, 50f))
        assertEquals(1, saved.holds.size)

        vm.closeTile()
        assertNull(vm.state.menuTile)
        assertEquals(cursor(1), vm.state.ledFrame)
    }

    @Test
    fun deletingATileUnmapsItsLedsAndCanBeUndone() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0, 1)), Hold(2, 20f, 5f, listOf(2)), ledCount = 10))
        vm.jumpTo("5")
        vm.pressTile(0)
        vm.releaseTile(0)
        vm.deleteTile()
        assertNull(saved(0))
        assertEquals("Deleted the tile with LEDs 0, 1", vm.state.message?.text)
        assertEquals(5, vm.state.current) // mapping carries on where it was
        assertNull(vm.state.tileAction)

        vm.undo()
        assertEquals(Hold(0, 10f, 5f, listOf(0, 1)), saved(0))
        assertEquals(5, vm.state.current)
    }

    @Test
    fun changingATilesLedsRefusesLedsThatArePlacedOrHidden() = runTest {
        val vm = mapper(layoutOf(Hold(0, 10f, 5f, listOf(0)), Hold(5, 20f, 5f, listOf(5)), ledCount = 10, hidden = listOf(3)))
        vm.pressTile(0)
        vm.releaseTile(0)
        vm.editTileLeds()
        assertEquals(0, vm.state.editingTile?.id)

        assertEquals("LED 5 is already in another tile.", vm.tileLedsProblem("5"))
        assertEquals("LED 3 is on the Hidden list. To use it, take it out with Edit Hidden list first.", vm.tileLedsProblem("0, 3"))
        assertEquals("There's no LED 10. LEDs go from 0 to 9.", vm.tileLedsProblem("10"))
        assertEquals("A tile needs at least one LED. Use Delete tile to remove it.", vm.tileLedsProblem(" , "))
        assertEquals("Enter LED numbers separated by commas, e.g. 37, 38.", vm.tileLedsProblem("1-2"))
        assertNull(vm.tileLedsProblem("0"))
        assertNull(vm.tileLedsProblem("1 0"))

        vm.saveTileLeds("5") // refused: the dialog stays open and nothing changes
        assertEquals("LED 5 is already in another tile.", vm.state.message?.text)
        assertEquals(0, vm.state.editingTile?.id)
        assertEquals(listOf(5), saved(5)?.leds)

        vm.saveTileLeds("2, 1")
        assertEquals(Hold(0, 10f, 5f, listOf(1, 2)), saved(0)) // same id and spot, new LEDs
        assertEquals("Tile changed to LEDs 1, 2", vm.state.message?.text)
        assertNull(vm.state.editingTile)
        assertEquals(0, saved.firstUnmappedLed()) // LED 0 is unmapped now

        vm.undo()
        assertEquals(Hold(0, 10f, 5f, listOf(0)), saved(0))
    }
}
