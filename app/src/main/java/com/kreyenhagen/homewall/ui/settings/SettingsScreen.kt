package com.kreyenhagen.homewall.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kreyenhagen.homewall.R
import com.kreyenhagen.homewall.data.settings.AppSettings
import com.kreyenhagen.homewall.data.settings.SavedDevice
import com.kreyenhagen.homewall.ui.appViewModel
import com.kreyenhagen.homewall.ui.components.BackButton
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenWallSetup: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it.settingsRepository, it.ledController, it.logBuffer) }
    val state by vm.uiState.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        onBack = onBack,
        onOpenWallSetup = onOpenWallSetup,
        onShowHoldMarkers = vm::setShowHoldMarkers,
        onShowLedPreview = vm::setShowLedPreview,
        onRingSize = vm::setRingSize,
        onKeepScreenOn = vm::setKeepScreenOn,
        onBrightness = vm::setBrightness,
        onSimulatedLeds = vm::setSimulatedLeds,
        onForgetDevice = vm::forgetDevice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState?,
    onBack: () -> Unit,
    onOpenWallSetup: () -> Unit,
    onShowHoldMarkers: (Boolean) -> Unit,
    onShowLedPreview: (Boolean) -> Unit,
    onRingSize: (Float) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onBrightness: (Float) -> Unit,
    onSimulatedLeds: (Boolean) -> Unit,
    onForgetDevice: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        if (state == null) return@Scaffold
        val s = state.settings

        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    ListItem(
                        headlineContent = { Text("Wall Setup") },
                        supportingContent = { Text("Photo, alignment and LED mapping") },
                        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onOpenWallSetup),
                    )
                }

                item { SectionHeader("Display") }
                item { SwitchRow("Show hold markers", "Debug tiles with hold ids", s.showHoldMarkers, onShowHoldMarkers) }
                item { SwitchRow("Show LED preview", "Dots on the holds whose LEDs are lit", s.showLedPreview, onShowLedPreview) }
                item {
                    SliderRow(
                        title = "Ring size",
                        value = s.ringSizeIn,
                        range = AppSettings.RING_SIZE_RANGE,
                        step = AppSettings.RING_SIZE_STEP,
                        format = { String.format(Locale.getDefault(), "%.1f in", it) },
                        onCommit = onRingSize,
                    )
                }
                item { SwitchRow("Keep screen on", null, s.keepScreenOn, onKeepScreenOn) }

                item { SectionHeader("LEDs") }
                item {
                    SliderRow(
                        title = "Brightness",
                        value = s.ledBrightnessPercent.toFloat(),
                        range = AppSettings.BRIGHTNESS_RANGE.first.toFloat()..AppSettings.BRIGHTNESS_RANGE.last.toFloat(),
                        step = AppSettings.BRIGHTNESS_STEP.toFloat(),
                        format = { "${it.roundToInt()}%" },
                        onCommit = onBrightness,
                    )
                }
                item {
                    SwitchRow(
                        title = "Simulated LED controller",
                        summary = "Use a fake controller instead of Bluetooth, e.g. on the emulator. Its packets show under Diagnostics.",
                        checked = s.simulatedLeds,
                        onCheckedChange = onSimulatedLeds,
                    )
                }

                item { SectionHeader("Bluetooth") }
                item {
                    ListItem(
                        headlineContent = { Text("Saved device") },
                        supportingContent = { Text(s.savedDevice?.let { "${it.name} · ${it.address}" } ?: "None") },
                        trailingContent = {
                            TextButton(onClick = onForgetDevice, enabled = s.savedDevice != null) { Text("Forget") }
                        },
                    )
                }

                item { SectionHeader("Data") }
                item {
                    ListItem(
                        headlineContent = { Text("Backup and layout import/export") },
                        supportingContent = { Text("Layout import/export arrives in M6, backups in M7.") },
                    )
                }

                item { SectionHeader("Diagnostics") }
                item { ListItem(headlineContent = { Text("Connection") }, supportingContent = { Text(state.connection) }) }
                if (state.log.isEmpty()) {
                    item { LogText("No log lines yet.") }
                } else {
                    items(state.log) { LogText(it) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/**
 * Follows the drag locally and saves once on release, so dragging doesn't write settings on
 * every step. Resets to [value] whenever the stored value changes.
 */
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Slider(
                value = local,
                onValueChange = { local = it },
                onValueChangeFinished = { onCommit(local) },
                valueRange = range,
                steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
            )
        },
        trailingContent = { Text(format(local)) },
    )
}

@Composable
private fun LogText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@TabletPreviews
@Composable
private fun SettingsPreview() {
    HomeWallTheme {
        SettingsContent(
            state = SettingsUiState(
                settings = AppSettings(showLedPreview = true, savedDevice = SavedDevice("24:6F:28:AA:BB:CC", "HomeWall")),
                connection = "Simulated",
                log = listOf(
                    "10:42:01.120  LED  TX 02 08 03",
                    "10:42:00.020  LED  TX 01 00 01 00 80 00 02 00 00 80 03 4B 00 80 04 80 80 80 05 20 20 20",
                    "10:42:00.000  LED  Simulated controller connected (MTU 517)",
                ),
            ),
            onBack = {},
            onOpenWallSetup = {},
            onShowHoldMarkers = {},
            onShowLedPreview = {},
            onRingSize = {},
            onKeepScreenOn = {},
            onBrightness = {},
            onSimulatedLeds = {},
            onForgetDevice = {},
        )
    }
}
