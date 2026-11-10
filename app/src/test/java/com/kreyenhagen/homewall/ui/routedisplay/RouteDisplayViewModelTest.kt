package com.kreyenhagen.homewall.ui.routedisplay

import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.domain.GradeFilter
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteHold
import com.kreyenhagen.homewall.domain.RouteSort
import com.kreyenhagen.homewall.led.LedEntry
import com.kreyenhagen.homewall.led.LedFrame
import com.kreyenhagen.homewall.led.Palette
import com.kreyenhagen.homewall.led.ringColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class RouteDisplayViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun route(id: Long, grade: Int?, createdAt: Long = id * 100) =
        Route(id, "R$id", grade, listOf(RouteHold(id.toInt(), HoldRole.MIDDLE)), createdAt, createdAt)

    private val routes = MutableStateFlow(listOf(route(1, 2), route(2, null), route(3, 5), route(4, 8)))
    private val settings = MutableStateFlow(AppSettings(sort = RouteSort.NEWEST, gradeFilter = GradeFilter(includeProjects = false)))
    private val sentFrame = MutableStateFlow<LedFrame?>(null)

    private fun TestScope.display(openedId: Long): RouteDisplayViewModel {
        val vm = RouteDisplayViewModel(
            openedId,
            routes,
            settings,
            MutableStateFlow(LayoutState.Ready(PlaceholderLayout.create())),
            MutableStateFlow(WallPhotoState(loaded = true)),
            sentFrame,
        )
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    private val RouteDisplayViewModel.ids get() = uiState.value.pages.map { it.route.id }
    private val RouteDisplayViewModel.current get() = uiState.value.currentRoute?.id

    @Test
    fun pagesMatchTheListOrderAndOpenAtTheTappedRoute() = runTest {
        val vm = display(openedId = 3)
        assertEquals(listOf(4L, 3, 1), vm.ids) // newest first, projects filtered out
        assertEquals(1, vm.uiState.value.currentIndex)
        assertEquals(3L, vm.current)
    }

    @Test
    fun theOpenedRouteShowsEvenIfTheFilterHidesIt() = runTest {
        val vm = display(openedId = 2) // a Project, with projects filtered out
        assertEquals(listOf(4L, 3, 2, 1), vm.ids)
        assertEquals(2L, vm.current)
    }

    @Test
    fun anEditedRouteStaysEvenIfItsNewGradeIsFilteredOut() = runTest {
        val vm = display(openedId = 4)
        vm.onPageChanged(3, 1)
        vm.onEdit(3)
        routes.update { list -> list.map { if (it.id == 3L) it.copy(grade = null) else it } }
        assertEquals(listOf(4L, 3, 1), vm.ids)
        assertEquals(3L, vm.current)
    }

    @Test
    fun theCurrentRouteIsKeptByIdWhenTheListChanges() = runTest {
        val vm = display(openedId = 3)
        routes.update { it + route(9, 4, createdAt = 10_000) } // newest, so it goes first
        assertEquals(listOf(9L, 4, 3, 1), vm.ids)
        assertEquals(3L, vm.current)
        assertEquals(2, vm.uiState.value.currentIndex)

        settings.update { it.copy(sort = RouteSort.GRADE_LOW_TO_HIGH) }
        assertEquals(listOf(1L, 9, 3, 4), vm.ids)
        assertEquals(3L, vm.current)
    }

    @Test
    fun ifTheCurrentRouteIsDeletedTheNeighborTakesItsPlace() = runTest {
        val vm = display(openedId = 3)
        vm.onPageChanged(3, 1)
        routes.update { list -> list.filterNot { it.id == 3L } }
        assertEquals(listOf(4L, 1), vm.ids)
        assertEquals(1L, vm.current)

        routes.value = emptyList()
        assertEquals(emptyList<Long>(), vm.ids)
        assertNull(vm.current)
    }


    @Test
    fun theWallFollowsTheCurrentPage() = runTest {
        val vm = display(openedId = 3)
        assertEquals(LedFrame(listOf(LedEntry(3, Palette.SLOT_MIDDLE))), vm.uiState.value.ledFrame)
        vm.onPageChanged(4, 0) // swiped halfway to the previous page
        assertEquals(LedFrame(listOf(LedEntry(4, Palette.SLOT_MIDDLE))), vm.uiState.value.ledFrame)
    }

    @Test
    fun theLedPreviewShowsTheLastSentFrameWhenTurnedOn() = runTest {
        settings.update { it.copy(showLedPreview = true) }
        val vm = display(openedId = 3)
        assertEquals(emptyMap<Int, Any>(), vm.uiState.value.ledPreview)
        sentFrame.value = LedFrame(listOf(LedEntry(3, Palette.SLOT_MIDDLE)))
        assertEquals(mapOf(3 to HoldRole.MIDDLE.ringColor), vm.uiState.value.ledPreview)
        settings.update { it.copy(showLedPreview = false) }
        assertEquals(emptyMap<Int, Any>(), vm.uiState.value.ledPreview)
    }
    @Test
    fun ringsAndTheMissingHoldsNote() {
        val route = Route(
            1, "R", 3,
            listOf(RouteHold(5, HoldRole.START), RouteHold(400, HoldRole.MIDDLE), RouteHold(401, HoldRole.MIDDLE), RouteHold(148, HoldRole.FINISH)),
            0, 0,
        )
        val page = RouteDisplayViewModel.pageFor(route, PlaceholderLayout.create().holds.mapTo(HashSet()) { it.id })
        assertEquals(mapOf(5 to HoldRole.START.ringColor, 148 to HoldRole.FINISH.ringColor), page.rings)
        assertEquals("2 holds no longer exist", page.missingNote)
        assertEquals("1 hold no longer exists", RouteDisplayViewModel.missingHoldsNote(1))
        assertNull(RouteDisplayViewModel.missingHoldsNote(0))
    }

    @Test
    fun indexFallsBackToTheNearestPage() {
        assertEquals(2, RouteDisplayViewModel.indexOf(listOf(5L, 6, 7), 7, lastIndex = 0))
        assertEquals(2, RouteDisplayViewModel.indexOf(listOf(5L, 6, 7), 99, lastIndex = 4))
        assertEquals(0, RouteDisplayViewModel.indexOf(emptyList(), 99, lastIndex = 4))
    }
}
