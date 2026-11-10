package com.kreyenhagen.homewall.ui.routelist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kreyenhagen.homewall.R
import com.kreyenhagen.homewall.domain.RouteSort
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.ui.appViewModel
import com.kreyenhagen.homewall.ui.components.ConnectButton
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import kotlinx.coroutines.launch

@Composable
fun RouteListScreen(onCreateRoute: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { RouteListViewModel(it.settingsRepository, it.layoutRepository, it.ledController) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    RouteListContent(
        state = state,
        snackbarHostState = snackbar,
        // Bluetooth arrives in M5. Until then, say how to get LED output on the emulator.
        onConnectClick = {
            val message = if (state.connection == ConnectionState.Simulated) {
                "Using the simulated LED controller. Turn it off in Settings."
            } else {
                "Bluetooth arrives in M5. To test without the wall, turn on Settings → Simulated LED controller."
            }
            scope.launch { snackbar.showSnackbar(message) }
        },
        onSortSelected = vm::setSort,
        onCreateRoute = onCreateRoute,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListContent(
    state: RouteListUiState,
    snackbarHostState: SnackbarHostState,
    onConnectClick: () -> Unit,
    onSortSelected: (RouteSort) -> Unit,
    onCreateRoute: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home Wall") },
                actions = {
                    ConnectButton(state.connection, onClick = onConnectClick)
                    // Lights off needs LedSync (M5); the filter sheet arrives with routes (M3).
                    IconButton(onClick = {}, enabled = false) {
                        Icon(painterResource(R.drawable.ic_power), contentDescription = "Lights off")
                    }
                    SortMenu(selected = state.sort, onSelect = onSortSelected)
                    IconButton(onClick = {}, enabled = false) {
                        Icon(painterResource(R.drawable.ic_filter_list), contentDescription = "Filter")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateRoute,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Create") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.banner?.let { Banner(it) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No routes yet. Tap Create to make one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SortMenu(selected: RouteSort, onSelect: (RouteSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_sort), contentDescription = "Sort")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RouteSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label) },
                    onClick = {
                        expanded = false
                        onSelect(sort)
                    },
                    leadingIcon = {
                        if (sort == selected) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected")
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

val RouteSort.label: String
    get() = when (this) {
        RouteSort.GRADE_HIGH_TO_LOW -> "Grade high → low"
        RouteSort.GRADE_LOW_TO_HIGH -> "Grade low → high"
        RouteSort.NEWEST -> "Newest"
        RouteSort.OLDEST -> "Oldest"
    }

@TabletPreviews
@Composable
private fun RouteListPreview() {
    HomeWallTheme {
        RouteListContent(
            state = RouteListUiState(
                connection = ConnectionState.Simulated,
                banner = "Placeholder layout. Map your wall in Settings → Wall Setup.",
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onConnectClick = {},
            onSortSelected = {},
            onCreateRoute = {},
            onOpenSettings = {},
        )
    }
}
