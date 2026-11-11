package com.kreyenhagen.homewall.ui.wallsetup

import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.LayoutJson
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.layout.RingShape
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.layoutOf
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.led.LedEntry
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.ui.wall.RingHandle
import com.kreyenhagen.homewall.ui.wall.WallPoint
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
class RingsViewModelTest {

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

    private suspend fun TestScope.rings(start: WallLayout = layout): RingsViewModel {
        val file = File(tmp.root, "wall_layout.json").apply { writeText(LayoutJson.encode(start)) }
        repo = LayoutRepository(file, readAsset = { null }, io = UnconfinedTestDispatcher(testScheduler))
        repo.load()
        val vm = RingsViewModel(repo, MutableStateFlow(WallPhotoState(loaded = true)), MutableStateFlow(AppSettings(ringSizeIn = 3f)))
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    private val RingsViewModel.state get() = uiState.value
    private fun saved(id: Int) = (repo.state.value as LayoutState.Ready).layout.holds.first { it.id == id }

    @Test
    fun everyHoldGetsARingAndTheSelectedOneIsHighlightedAndLit() = runTest {
        val vm = rings()
        assertEquals(mapOf(0 to RingsViewModel.RING, 7 to RingsViewModel.RING), vm.state.rings)
        assertEquals(LedFrame.OFF, vm.state.ledFrame)
        assertEquals(5, vm.state.unmapped) // LEDs 3–6 and 9

        vm.tapHold(7)
        assertEquals(7, vm.state.selected?.id)
        assertEquals(RingsViewModel.SELECTED, vm.state.rings[7])
        assertEquals(RingsViewModel.RING, vm.state.rings[0])
        assertEquals(RingShape(3f, 3f, 0f), vm.state.shape) // the default from Settings
        assertFalse(vm.state.hasOwnRing)
        assertEquals(LedFrame(listOf(LedEntry(7, Palette.SLOT_CURSOR), LedEntry(8, Palette.SLOT_CURSOR))), vm.state.ledFrame)

        vm.tapEmpty()
        assertNull(vm.state.selected)
        assertEquals(LedFrame.OFF, vm.state.ledFrame)
    }

    @Test
    fun theSlidersChangeWidthHeightAndRotationSeparatelyAndSaveWhenLetGo() = runTest {
        val vm = rings()
        vm.tapHold(7)
        vm.setWidth(10f)
        vm.setHeight(4.04f)
        vm.setAngle(30.4f)
        assertEquals(RingShape(5f, 2f, 30f), vm.state.shape)
        assertNull(saved(7).ringIn) // shown, not saved yet

        vm.endSliderChange()
        assertEquals(5f, saved(7).ringIn)
        assertEquals(2f, saved(7).ringYIn)
        assertEquals(30f, saved(7).ringAngle)
        assertEquals(RingShape(5f, 2f, 30f), vm.state.shape)
        assertTrue(vm.state.hasOwnRing)
        assertTrue(vm.state.canRevert)
    }

    @Test
    fun draggingAHandleSavesWhenTheFingerLifts() = runTest {
        val vm = rings()
        vm.tapHold(7)
        vm.dragHandle(RingHandle.WIDTH, WallPoint(20f + 5f, 30f))
        vm.dragHandle(RingHandle.WIDTH, WallPoint(20f + 6f, 30f))
        assertEquals(RingShape(6f, 3f, 0f), vm.state.shape)
        assertNull(saved(7).ringIn)

        vm.endHandleDrag()
        assertEquals(6f, saved(7).ringIn)
        assertEquals(3f, saved(7).ringYIn)

        vm.dragHandle(RingHandle.ROTATE, WallPoint(20f + 4f, 30f))
        assertEquals(90f, vm.state.shape?.angleDeg)
        vm.endHandleDrag()
        assertEquals(90f, saved(7).ringAngle)
    }

    @Test
    fun draggingTheCenterMovesTheHoldAndKeepsItsRing() = runTest {
        val vm = rings()
        vm.tapHold(7)
        vm.dragHandle(RingHandle.CENTER, WallPoint(24f, 31f))
        vm.dragHandle(RingHandle.CENTER, WallPoint(25f, 33f))
        assertEquals(25f, vm.state.selected?.x)
        assertEquals(33f, vm.state.selected?.y)
        assertEquals("x 25.0, y 33.0 in (Main)", vm.state.selectedPosition)
        assertEquals(20f, saved(7).x) // shown, not saved yet

        vm.endHandleDrag()
        // The hold itself moved, so its tile in Map LEDs and Review moves with the ring.
        assertEquals(25f, saved(7).x)
        assertEquals(33f, saved(7).y)
        assertEquals(listOf(7, 8), saved(7).leds)
        // Moving isn't resizing: the hold still uses the default ring.
        assertFalse(vm.state.hasOwnRing)
        assertNull(saved(7).ringIn)
        assertEquals(LedFrame(listOf(LedEntry(7, Palette.SLOT_CURSOR), LedEntry(8, Palette.SLOT_CURSOR))), vm.state.ledFrame)
        assertTrue(vm.state.canRevert)

        vm.revert()
        assertEquals(20f, saved(7).x)
        assertEquals(30f, saved(7).y)
        assertFalse(vm.state.canRevert)
    }

    @Test
    fun aMovedRingKeepsItsShapeAndIsResizedAroundItsNewCenter() = runTest {
        val vm = rings(layoutOf(Hold(7, 20f, 30f, listOf(7, 8), ringIn = 4f, ringYIn = 2f, ringAngle = 20f), ledCount = 10))
        vm.tapHold(7)
        vm.dragHandle(RingHandle.CENTER, WallPoint(30f, 40f))
        vm.endHandleDrag()
        assertEquals(RingShape(4f, 2f, 20f), vm.state.shape)
        assertEquals(30f, saved(7).x)

        vm.tapHold(7)
        vm.setAngle(0f)
        vm.endSliderChange()
        vm.dragHandle(RingHandle.WIDTH, WallPoint(30f + 6f, 40f))
        vm.endHandleDrag()
        assertEquals(6f, saved(7).ringIn)
        assertEquals(30f, saved(7).x)
    }

    @Test
    fun aRingCantBeMovedOffTheWall() = runTest {
        val vm = rings()
        val wall = layout.wall
        vm.tapHold(7)
        vm.dragHandle(RingHandle.CENTER, WallPoint(-5f, wall.heightIn + 20f))
        vm.endHandleDrag()
        assertEquals(0f, saved(7).x)
        assertEquals(wall.heightIn, saved(7).y)
    }

    @Test
    fun anInterruptedDragChangesNothing() = runTest {
        val vm = rings()
        vm.tapHold(7)
        vm.dragHandle(RingHandle.HEIGHT, WallPoint(20f, 30f + 8f))
        assertEquals(8f, vm.state.shape?.radiusYIn)
        vm.cancelHandleDrag()
        assertEquals(RingShape(3f, 3f, 0f), vm.state.shape)
        assertEquals(layout, (repo.state.value as LayoutState.Ready).layout)
    }

    @Test
    fun selectingAnotherRingKeepsAnUnfinishedChange() = runTest {
        val vm = rings()
        vm.tapHold(7)
        vm.setWidth(10f)
        vm.tapHold(0)
        assertEquals(5f, saved(7).ringIn)
        assertEquals(0, vm.state.selected?.id)
    }

    @Test
    fun makeCircleUseDefaultAndRevert() = runTest {
        val vm = rings(layoutOf(Hold(0, 10f, 5f, listOf(0)), Hold(7, 20f, 30f, listOf(7, 8), ringIn = 4f, ringYIn = 2f, ringAngle = 20f), ledCount = 10))
        vm.tapHold(7)
        assertTrue(vm.state.hasOwnRing)
        assertFalse(vm.state.canRevert)

        vm.makeCircle()
        assertEquals(RingShape(4f, 4f, 0f), vm.state.shape)
        assertEquals(4f, saved(7).ringIn)
        assertNull(saved(7).ringYIn)
        assertNull(saved(7).ringAngle)

        vm.useDefault()
        assertFalse(vm.state.hasOwnRing)
        assertEquals(RingShape(3f, 3f, 0f), vm.state.shape)
        assertEquals("Hold 7 uses the default ring size again", vm.state.message?.text)

        vm.revert()
        assertEquals(RingShape(4f, 2f, 20f), vm.state.shape)
        assertFalse(vm.state.canRevert)
    }

    @Test
    fun thePlaceholderHasNoRingsToSize() = runTest {
        val vm = rings(PlaceholderLayout.create())
        assertTrue(vm.state.needsMapping)
        assertTrue(vm.state.rings.isEmpty())
        vm.tapHold(PlaceholderLayout.create().holds[0].id)
        assertNull(vm.state.selected)
        assertEquals(LedFrame.OFF, vm.state.ledFrame)
    }
}
