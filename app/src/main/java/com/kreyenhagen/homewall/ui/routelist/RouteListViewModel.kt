package com.kreyenhagen.homewall.ui.routelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.LayoutState
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.settings.SettingsRepository
import com.kreyenhagen.homewall.domain.RouteSort
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.led.LedController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RouteListUiState(
    val sort: RouteSort = RouteSort.NEWEST,
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Shown above the list: the placeholder-layout note (§6.4) or a layout error. */
    val banner: String? = null,
)

class RouteListViewModel(
    private val settings: SettingsRepository,
    layoutRepository: LayoutRepository,
    controller: LedController,
) : ViewModel() {

    val uiState: StateFlow<RouteListUiState> =
        combine(settings.settings, layoutRepository.state, controller.state) { s, layout, connection ->
            RouteListUiState(sort = s.sort, connection = connection, banner = bannerFor(layout))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteListUiState())

    private fun bannerFor(layout: LayoutState): String? = when {
        layout is LayoutState.Invalid -> "The wall layout file has problems. See Settings → Wall Setup → Align."
        layout.layoutOrNull?.placeholder == true -> "Placeholder layout. Map your wall in Settings → Wall Setup."
        else -> null
    }

    fun setSort(sort: RouteSort) {
        viewModelScope.launch { settings.setSort(sort) }
    }
}
