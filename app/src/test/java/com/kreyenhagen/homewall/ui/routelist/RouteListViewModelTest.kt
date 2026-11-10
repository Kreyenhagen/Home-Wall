package com.kreyenhagen.homewall.ui.routelist

import com.kreyenhagen.homewall.MainDispatcherRule
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.domain.GradeFilter
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.led.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteListViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val routes = MutableStateFlow((1L..5).map { Route(it, "R$it", it.toInt(), emptyList(), it * 100, it * 100) })
    private val settings = MutableStateFlow(AppSettings())
    private val lastViewed = MutableStateFlow<Long?>(null)

    private fun TestScope.list(): RouteListViewModel {
        val vm = RouteListViewModel(
            routes = routes,
            settings = settings,
            layout = MutableStateFlow(LayoutState.Ready(PlaceholderLayout.create())),
            connection = MutableStateFlow(ConnectionState.Disconnected),
            setSort = {},
            setFilter = {},
            lastViewedRoute = lastViewed,
        )
        backgroundScope.launch(main.dispatcher) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun ledCountMismatchWarnsOnlyWhenConnected() {
        // §10.3 step 5: warn on a mismatch but keep going.
        assertEquals(
            "The controller has 550 LEDs but the wall layout has 154. LEDs past the shorter count won't light.",
            RouteListViewModel.ledCountWarning(ConnectionState.Connected("HomeWall", 550), 154),
        )
        assertNull(RouteListViewModel.ledCountWarning(ConnectionState.Connected("HomeWall", 550), 550))
        assertNull(RouteListViewModel.ledCountWarning(ConnectionState.Simulated, 154))
        assertNull(RouteListViewModel.ledCountWarning(ConnectionState.Connected("HomeWall", 550), null))
    }

    @Test
    fun backFromRouteDisplayScrollsToTheLastRouteViewedOnce() = runTest {
        val vm = list()
        assertNull(vm.uiState.value.scrollTo) // a fresh start doesn't scroll

        vm.onLeaving()
        lastViewed.value = 2 // Route Display showed route 2 last
        assertEquals(3, vm.uiState.value.scrollTo) // newest first: 5, 4, 3, 2, 1

        vm.scrollRestored()
        assertNull(vm.uiState.value.scrollTo) // e.g. rotating afterwards doesn't scroll again
    }

    @Test
    fun leavingForgetsTheRouteFromLastTime() = runTest {
        val vm = list()
        lastViewed.value = 2
        vm.onLeaving() // tapped another route: don't jump to route 2 while Route Display opens
        assertNull(vm.uiState.value.scrollTo)
    }

    @Test
    fun aRouteTheFilterHidesOrThatWasDeletedIsNotScrolledTo() = runTest {
        val vm = list()
        settings.value = AppSettings(gradeFilter = GradeFilter(minGrade = 3))
        lastViewed.value = 2
        assertNull(vm.uiState.value.scrollTo)
        lastViewed.value = 99
        assertNull(vm.uiState.value.scrollTo)
    }
}
