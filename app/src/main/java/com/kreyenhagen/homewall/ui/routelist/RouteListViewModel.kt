package com.kreyenhagen.homewall.ui.routelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

class RouteListViewModel(
    private val settings: SettingsRepository,
    controller: LedController,
) : ViewModel() {

    val uiState: StateFlow<RouteListUiState> =
        combine(settings.settings, controller.state) { s, connection ->
            RouteListUiState(sort = s.sort, connection = connection)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteListUiState())

    fun setSort(sort: RouteSort) {
        viewModelScope.launch { settings.setSort(sort) }
    }
}
