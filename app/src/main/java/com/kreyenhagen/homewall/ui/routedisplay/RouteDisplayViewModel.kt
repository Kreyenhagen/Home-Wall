package com.kreyenhagen.homewall.ui.routedisplay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.photo.WallPhotoState
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteSorting
import com.kreyenhagen.homewall.led.ringColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** One pager page: a route and what to draw for it. */
data class RoutePage(
    val route: Route,
    /** holdId → ring color, for the route's holds that exist in the layout. */
    val rings: Map<Int, Color>,
    /** e.g. "2 holds no longer exist" (§6.3), or null. */
    val missingNote: String?,
)

data class RouteDisplayUiState(
    val loaded: Boolean = false,
    /** The same filtered and sorted list as Route List (§8.2). */
    val pages: List<RoutePage> = emptyList(),
    val currentIndex: Int = 0,
    val layout: WallLayout? = null,
    val layoutProblems: List<String> = emptyList(),
    val image: ImageBitmap? = null,
    val ringSizeIn: Float = AppSettings().ringSizeIn,
    val showMarkers: Boolean = AppSettings().showHoldMarkers,
) {
    val currentRoute: Route? get() = pages.getOrNull(currentIndex)?.route
}

class RouteDisplayViewModel(
    openedRouteId: Long,
    routes: Flow<List<Route>>,
    settings: Flow<AppSettings>,
    layout: Flow<LayoutState>,
    photo: Flow<WallPhotoState>,
) : ViewModel() {

    /**
     * Routes shown even if the filter hides them: the one I opened (§8.2) and any I've edited
     * from here, so e.g. regrading a route to Project doesn't make it vanish under me.
     */
    private val pinned = MutableStateFlow(setOf(openedRouteId))

    /** The route on screen, by id, plus its last index as a fallback if it's deleted. */
    private val current = MutableStateFlow(Current(openedRouteId, 0))

    private data class Current(val routeId: Long, val index: Int)

    private val pages: Flow<List<RoutePage>?> = combine(routes, settings, layout, pinned) { all, s, layoutState, pins ->
        if (layoutState is LayoutState.Loading) return@combine null
        val holdIds = (layoutState as? LayoutState.Ready)?.layout?.holds?.mapTo(HashSet()) { it.id }
        RouteSorting.apply(all, s.sort, s.gradeFilter, alwaysInclude = pins).map { pageFor(it, holdIds) }
    }

    val uiState: StateFlow<RouteDisplayUiState> =
        combine(pages, current, settings, layout, photo) { pages, cur, s, layoutState, photoState ->
            if (pages == null) return@combine RouteDisplayUiState()
            RouteDisplayUiState(
                loaded = true,
                pages = pages,
                currentIndex = indexOf(pages.map { it.route.id }, cur.routeId, cur.index),
                layout = (layoutState as? LayoutState.Ready)?.layout,
                layoutProblems = (layoutState as? LayoutState.Invalid)?.problems.orEmpty(),
                image = photoState.rectified,
                ringSizeIn = s.ringSizeIn,
                showMarkers = s.showHoldMarkers,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteDisplayUiState())

    /** The pager moved (it changes halfway through a swipe, §8.2). */
    fun onPageChanged(routeId: Long, index: Int) {
        current.value = Current(routeId, index)
    }

    /** About to open the editor for [routeId]: keep it in the list when I come back. */
    fun onEdit(routeId: Long) {
        pinned.update { it + routeId }
    }

    companion object {
        /** Where [routeId] is in [ids]; if it's gone, the nearest index to where it was. */
        fun indexOf(ids: List<Long>, routeId: Long, lastIndex: Int): Int {
            val found = ids.indexOf(routeId)
            return if (found >= 0) found else lastIndex.coerceIn(0, maxOf(ids.size - 1, 0))
        }

        fun pageFor(route: Route, layoutHoldIds: Set<Int>?): RoutePage {
            val (present, missing) = route.holds.partition { layoutHoldIds == null || it.holdId in layoutHoldIds }
            return RoutePage(
                route = route,
                rings = present.associate { it.holdId to it.role.ringColor },
                missingNote = missingHoldsNote(missing.size),
            )
        }

        fun missingHoldsNote(count: Int): String? = when (count) {
            0 -> null
            1 -> "1 hold no longer exists"
            else -> "$count holds no longer exist"
        }
    }
}
