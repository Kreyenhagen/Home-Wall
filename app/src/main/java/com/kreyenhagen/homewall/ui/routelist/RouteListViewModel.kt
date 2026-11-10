package com.kreyenhagen.homewall.ui.routelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.domain.GradeFilter
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteSort
import com.kreyenhagen.homewall.domain.RouteSorting
import com.kreyenhagen.homewall.led.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RouteListUiState(
    /** False until routes and settings have loaded, so the empty state doesn't flash. */
    val loaded: Boolean = false,
    /** Filtered and sorted, the same list Route Display swipes through. */
    val routes: List<Route> = emptyList(),
    val totalRoutes: Int = 0,
    val sort: RouteSort = RouteSort.NEWEST,
    val filter: GradeFilter = GradeFilter(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Shown above the list: the placeholder-layout note (§6.4) or a layout error. */
    val banner: String? = null,
    /** The controller and the layout disagree on the LED count (§10.3 step 5). */
    val ledCountWarning: String? = null,
    /** Back from Route Display: the list row of the last route I viewed, to scroll to (§8.1). */
    val scrollTo: Int? = null,
)

class RouteListViewModel(
    routes: Flow<List<Route>>,
    settings: Flow<AppSettings>,
    layout: Flow<LayoutState>,
    connection: Flow<ConnectionState>,
    private val setSort: suspend (RouteSort) -> Unit,
    private val setFilter: suspend (GradeFilter) -> Unit,
    private val onLightsOff: () -> Unit = {},
    /** Set by Route Display (AppContainer.lastViewedRouteId); cleared here once it's been used. */
    private val lastViewedRoute: MutableStateFlow<Long?> = MutableStateFlow(null),
) : ViewModel() {

    val uiState: StateFlow<RouteListUiState> =
        combine(routes, settings, layout, connection, lastViewedRoute) { all, s, layoutState, conn, lastViewed ->
            val shown = RouteSorting.apply(all, s.sort, s.gradeFilter)
            RouteListUiState(
                loaded = true,
                routes = shown,
                totalRoutes = all.size,
                sort = s.sort,
                filter = s.gradeFilter,
                connection = conn,
                banner = bannerFor(layoutState),
                ledCountWarning = ledCountWarning(conn, layoutState.layoutOrNull?.ledCount),
                scrollTo = lastViewed?.let { id -> shown.indexOfFirst { it.id == id }.takeIf { it >= 0 } },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteListUiState())

    private fun bannerFor(layout: LayoutState): String? = when {
        layout is LayoutState.Invalid -> "The wall layout file has problems, so routes can't be shown. Import a layout or a backup in Settings → Data."
        layout.layoutOrNull?.placeholder == true -> "Placeholder layout. Map your wall in Settings → Wall Setup."
        else -> null
    }

    fun selectSort(sort: RouteSort) {
        viewModelScope.launch { setSort(sort) }
    }

    fun selectFilter(filter: GradeFilter) {
        viewModelScope.launch { setFilter(filter) }
    }

    fun resetFilter() = selectFilter(GradeFilter())

    /** Lights off (§8.1): clears the wall and the remembered route. */
    fun lightsOff() = onLightsOff()

    /**
     * About to open a route or the editor. Forgets the last viewed route, so the list scrolls only
     * to the one Route Display shows last this time.
     */
    fun onLeaving() {
        lastViewedRoute.value = null
    }

    /** The list has scrolled to [RouteListUiState.scrollTo] (or it was already in view). */
    fun scrollRestored() {
        lastViewedRoute.value = null
    }

    companion object {
        fun ledCountWarning(connection: ConnectionState, layoutLedCount: Int?): String? {
            val connected = connection as? ConnectionState.Connected ?: return null
            if (layoutLedCount == null || connected.ledCount == layoutLedCount) return null
            return "The controller has ${connected.ledCount} LEDs but the wall layout has $layoutLedCount. " +
                "LEDs past the shorter count won't light."
        }
    }
}
