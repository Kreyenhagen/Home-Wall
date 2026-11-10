package com.kreyenhagen.homewall.ui.components

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kreyenhagen.homewall.ble.BlePermissions
import com.kreyenhagen.homewall.ble.FoundDevice
import com.kreyenhagen.homewall.ble.ScanState
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.ui.appViewModel

/**
 * The Connect button with everything behind it (§8.1): permission prompt, Bluetooth-on prompt,
 * the device picker, the connected menu, and error snackbars with Retry.
 */
@Composable
fun ConnectControl(snackbarHostState: SnackbarHostState) {
    val vm = appViewModel {
        ConnectViewModel(it.ledController.state, it.settingsRepository.settings, it.bleLedController, it.settingsRepository::setSavedDevice)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var menuOpen by remember { mutableStateOf(false) }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    // Set after a system prompt succeeds, to carry on with the tap that started it.
    var resumeTap by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.isNotEmpty() && results.values.all { it }) resumeTap = true else vm.permissionDenied()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (BlePermissions.bluetoothOn(context)) resumeTap = true else vm.bluetoothNotEnabled()
    }

    val onTap = {
        when (vm.stepFor(BlePermissions.granted(context), BlePermissions.bluetoothOn(context))) {
            ConnectStep.SIMULATED_NOTE -> vm.showSimulatedNote()
            ConnectStep.MENU -> menuOpen = true
            ConnectStep.PERMISSIONS -> showRationale = true
            ConnectStep.ENABLE_BLUETOOTH -> enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            ConnectStep.CONNECT_SAVED -> vm.connectSaved()
            ConnectStep.PICKER -> vm.openPicker()
        }
    }

    LaunchedEffect(resumeTap) {
        if (resumeTap) {
            resumeTap = false
            onTap()
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val actionLabel = when (message.action) {
            ConnectMessage.Action.RETRY -> "Retry"
            ConnectMessage.Action.APP_SETTINGS -> "Settings"
            null -> null
        }
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(message.message.text, actionLabel = actionLabel)
        if (result == SnackbarResult.ActionPerformed) {
            when (message.action) {
                ConnectMessage.Action.RETRY -> onTap()
                ConnectMessage.Action.APP_SETTINGS -> context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
                null -> Unit
            }
        }
        vm.messageShown(message.message.id)
    }

    Box {
        ConnectButton(state.connection, onClick = onTap)
        ConnectedMenu(
            expanded = menuOpen,
            connection = state.connection,
            deviceName = state.savedDevice?.name,
            onDismiss = { menuOpen = false },
            onDisconnect = {
                menuOpen = false
                vm.disconnect()
            },
            onForget = {
                menuOpen = false
                vm.forgetDevice()
            },
        )
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Connect to your wall") },
            text = { Text("Home Wall uses Bluetooth to light the LEDs on your wall. Android will ask you to allow Nearby devices.") },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(BlePermissions.REQUIRED)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Not now") } },
        )
    }

    if (state.pickerOpen) {
        DevicePickerSheet(scan = state.scan, onPick = vm::pick, onRescan = vm::rescan, onDismiss = vm::closePicker)
    }
}

@Composable
private fun ConnectedMenu(
    expanded: Boolean,
    connection: ConnectionState,
    deviceName: String?,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val title = when (connection) {
            is ConnectionState.Connected -> "${connection.name} · ${connection.ledCount} LEDs"
            ConnectionState.Connecting -> "Connecting to ${deviceName ?: "the wall"}…"
            else -> deviceName ?: "Not connected"
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Disconnect") }, onClick = onDisconnect)
        DropdownMenuItem(text = { Text("Forget device") }, onClick = onForget)
    }
}

/** §8.1: scans for 10 s and lists boards by name and signal strength, with Rescan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(scan: ScanState, onPick: (FoundDevice) -> Unit, onRescan: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        DevicePickerContent(scan, onPick, onRescan)
    }
}

@Composable
fun DevicePickerContent(scan: ScanState, onPick: (FoundDevice) -> Unit, onRescan: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Choose your wall", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (scan.scanning) "Looking for Home Wall boards…" else "Found ${scan.devices.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRescan, enabled = !scan.scanning) { Text("Rescan") }
        }
        Spacer(Modifier.height(12.dp))
        if (scan.scanning) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()

        scan.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
        }
        if (scan.devices.isEmpty() && !scan.scanning && scan.error == null) {
            Text(
                "No boards found. Make sure the ESP32 is powered and nearby, then tap Rescan.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        scan.devices.forEach { device ->
            ListItem(
                headlineContent = { Text(device.name) },
                supportingContent = { Text(device.address) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(signalLabel(device.rssi), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                modifier = Modifier.clickable { onPick(device) },
            )
        }
    }
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Strong"
    rssi >= -75 -> "Good"
    else -> "Weak"
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF121212, widthDp = 600)
@Composable
private fun DevicePickerPreview() {
    com.kreyenhagen.homewall.ui.theme.HomeWallTheme {
        androidx.compose.material3.Surface {
            DevicePickerContent(
                scan = ScanState(scanning = true, devices = listOf(FoundDevice("24:6F:28:AA:BB:CC", "HomeWall", -58), FoundDevice("24:6F:28:11:22:33", "HomeWall", -81))),
                onPick = {},
                onRescan = {},
            )
        }
    }
}
