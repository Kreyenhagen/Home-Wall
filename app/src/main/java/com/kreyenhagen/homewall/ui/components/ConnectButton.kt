package com.kreyenhagen.homewall.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.kreyenhagen.homewall.R
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import com.kreyenhagen.homewall.ui.theme.StatusColors

/**
 * Bluetooth icon showing the connection state (§8.1): grey disconnected, amber busy,
 * green connected, and a tinted tile for Simulated so it can't be mistaken for the real wall.
 */
@Composable
fun ConnectButton(state: ConnectionState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = connectLook(state)
    val colors = if (state == ConnectionState.Simulated) {
        IconButtonDefaults.iconButtonColors(containerColor = StatusColors.Simulated.copy(alpha = 0.2f))
    } else {
        IconButtonDefaults.iconButtonColors()
    }
    IconButton(onClick = onClick, modifier = modifier, colors = colors) {
        Icon(painterResource(look.icon), contentDescription = look.description, tint = look.tint)
    }
}

/** Short state text for diagnostics and messages. */
val ConnectionState.label: String
    get() = when (this) {
        ConnectionState.Disconnected -> "Disconnected"
        ConnectionState.Scanning -> "Scanning"
        ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Connected -> "Connected to $name ($ledCount LEDs)"
        ConnectionState.Simulated -> "Simulated"
        is ConnectionState.Error -> "Error: $message"
    }

private data class ConnectLook(@param:DrawableRes val icon: Int, val tint: Color, val description: String)

@Composable
private fun connectLook(state: ConnectionState): ConnectLook = when (state) {
    ConnectionState.Disconnected ->
        ConnectLook(R.drawable.ic_bluetooth, MaterialTheme.colorScheme.onSurfaceVariant, "Connect to the wall")
    ConnectionState.Scanning, ConnectionState.Connecting ->
        ConnectLook(R.drawable.ic_bluetooth_searching, StatusColors.Busy, state.label)
    is ConnectionState.Connected ->
        ConnectLook(R.drawable.ic_bluetooth_connected, StatusColors.Connected, state.label)
    ConnectionState.Simulated ->
        ConnectLook(R.drawable.ic_bluetooth_connected, StatusColors.Simulated, "Simulated LED controller")
    is ConnectionState.Error ->
        ConnectLook(R.drawable.ic_bluetooth_disabled, MaterialTheme.colorScheme.error, state.label)
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun ConnectButtonPreview() {
    HomeWallTheme {
        Row {
            ConnectButton(ConnectionState.Disconnected, onClick = {})
            ConnectButton(ConnectionState.Connecting, onClick = {})
            ConnectButton(ConnectionState.Connected("HomeWall", 550), onClick = {})
            ConnectButton(ConnectionState.Simulated, onClick = {})
            ConnectButton(ConnectionState.Error("Not a Home Wall controller"), onClick = {})
        }
    }
}
