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
)

class RouteListViewModel(
    routes: Flow<List<Route>>,
    settings: Flow<AppSettings>,
    layout: Flow<LayoutState>,
    connection: Flow<ConnectionState>,
    private val setSort: suspend (RouteSort) -> Unit,
    private val setFilter: suspend (GradeFilter) -> Unit,
) : ViewModel() {

    val uiState: StateFlow<RouteListUiState> =
        combine(routes, settings, layout, connection) { all, s, layoutState, conn ->
            RouteListUiState(
                loaded = true,
                routes = RouteSorting.apply(all, s.sort, s.gradeFilter),
                totalRoutes = all.size,
                sort = s.sort,
                filter = s.gradeFilter,
                connection = conn,
                banner = bannerFor(layoutState),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteListUiState())

    private fun bannerFor(layout: LayoutState): String? = when {
        layout is LayoutState.Invalid -> "The wall layout file has problems. See Settings → Wall Setup → Align."
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
}
