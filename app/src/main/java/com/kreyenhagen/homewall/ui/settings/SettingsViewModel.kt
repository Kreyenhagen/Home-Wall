package com.kreyenhagen.homewall.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.data.settings.SettingsRepository
import com.kreyenhagen.homewall.led.LedController
import com.kreyenhagen.homewall.ui.components.label
import com.kreyenhagen.homewall.util.LogBuffer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class SettingsUiState(
    val settings: AppSettings,
    val connection: String,
    /** Newest first. */
    val log: List<String>,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    controller: LedController,
    logBuffer: LogBuffer,
    /** Forgetting the board also disconnects from it (§8.1). */
    private val disconnect: () -> Unit = {},
) : ViewModel() {

    /** Null until settings have loaded, so controls don't flash their defaults. */
    val uiState: StateFlow<SettingsUiState?> =
        combine(repo.settings, controller.state, logBuffer.lines) { settings, connection, lines ->
            SettingsUiState(
                settings = settings,
                connection = connection.label,
                log = lines.asReversed().map { it.format() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setShowHoldMarkers(show: Boolean) = launch { repo.setShowHoldMarkers(show) }
    fun setShowLedPreview(show: Boolean) = launch { repo.setShowLedPreview(show) }
    fun setKeepScreenOn(on: Boolean) = launch { repo.setKeepScreenOn(on) }
    fun setSimulatedLeds(on: Boolean) = launch { repo.setSimulatedLeds(on) }
    fun forgetDevice() {
        disconnect()
        launch { repo.setSavedDevice(null) }
    }

    /** LedSync resends the palette as soon as the new value is stored (§9.3). */
    fun setBrightness(percent: Float) = launch { repo.setLedBrightness(percent.roundToInt()) }

    fun setRingSize(inches: Float) = launch {
        val step = AppSettings.RING_SIZE_STEP
        repo.setRingSize((inches / step).roundToInt() * step)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun LogBuffer.Line.format(): String {
    val time = TIME_FORMAT.format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))
    return "$time  $tag  $message"
}
