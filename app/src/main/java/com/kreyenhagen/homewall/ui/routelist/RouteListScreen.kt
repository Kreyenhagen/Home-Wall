package com.kreyenhagen.homewall.ui.routelist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kreyenhagen.homewall.R
import com.kreyenhagen.homewall.domain.GradeFilter
import com.kreyenhagen.homewall.domain.Grades
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteSort
import com.kreyenhagen.homewall.led.ConnectionState
import com.kreyenhagen.homewall.ui.appViewModel
import com.kreyenhagen.homewall.ui.components.ConnectButton
import com.kreyenhagen.homewall.ui.components.ConnectControl
import com.kreyenhagen.homewall.ui.components.DriveLeds
import com.kreyenhagen.homewall.ui.components.WallLeds
import com.kreyenhagen.homewall.ui.components.GradeChip
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import kotlin.math.roundToInt

@Composable
fun RouteListScreen(onOpenRoute: (routeId: Long) -> Unit, onCreateRoute: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel {
        RouteListViewModel(
            routes = it.routeRepository.routes,
            settings = it.settingsRepository.settings,
            layout = it.layoutRepository.state,
            connection = it.ledController.state,
            setSort = it.settingsRepository::setSort,
            setFilter = it.settingsRepository::setGradeFilter,
            onLightsOff = it.ledSync::lightsOff,
        )
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    DriveLeds(WallLeds.LastRoute)
    val snackbar = remember { SnackbarHostState() }

    RouteListContent(
        state = state,
        snackbarHostState = snackbar,
        connectControl = { ConnectControl(snackbar) },
        onLightsOff = vm::lightsOff,
        onSortSelected = vm::selectSort,
        onFilterChange = vm::selectFilter,
        onResetFilter = vm::resetFilter,
        onOpenRoute = onOpenRoute,
        onCreateRoute = onCreateRoute,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListContent(
    state: RouteListUiState,
    snackbarHostState: SnackbarHostState,
    connectControl: @Composable () -> Unit,
    onLightsOff: () -> Unit,
    onSortSelected: (RouteSort) -> Unit,
    onFilterChange: (GradeFilter) -> Unit,
    onResetFilter: () -> Unit,
    onOpenRoute: (Long) -> Unit,
    onCreateRoute: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showFilter by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home Wall") },
                actions = {
                    connectControl()
                    IconButton(onClick = onLightsOff) {
                        Icon(painterResource(R.drawable.ic_power), contentDescription = "Lights off")
                    }
                    SortMenu(selected = state.sort, onSelect = onSortSelected)
                    IconButton(onClick = { showFilter = true }) {
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
            state.ledCountWarning?.let { Banner(it) }
            if (state.filter.isActive) {
                FilterChip(
                    selected = true,
                    onClick = { showFilter = true },
                    label = { Text(state.filter.summary) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_filter_list), contentDescription = null, Modifier.size(18.dp)) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                when {
                    !state.loaded -> Unit
                    state.totalRoutes == 0 -> EmptyState("No routes yet. Tap Create to make one.")
                    state.routes.isEmpty() -> EmptyState("No routes match this filter") {
                        Button(onClick = onResetFilter) { Text("Reset filter") }
                    }
                    else -> RouteRows(state.routes, onOpenRoute)
                }
            }
        }
    }

    if (showFilter) {
        FilterSheet(
            filter = state.filter,
            onChange = onFilterChange,
            onReset = onResetFilter,
            onDismiss = { showFilter = false },
        )
    }
}

@Composable
private fun RouteRows(routes: List<Route>, onOpenRoute: (Long) -> Unit) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
        // Room under the last row for the Create button.
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(routes, key = { it.id }) { route ->
            ListItem(
                headlineContent = { Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { GradeChip(route.grade) },
                modifier = Modifier.clickable { onOpenRoute(route.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyState(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
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

/** §8.1: grade range, include projects, and Reset. Every change is saved straight away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(filter: GradeFilter, onChange: (GradeFilter) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    // The slider's position while dragging; it's saved when the drag ends.
    var range by remember(filter.minGrade, filter.maxGrade) {
        mutableStateOf(filter.minGrade.toFloat()..filter.maxGrade.toFloat())
    }
    val min = range.start.roundToInt()
    val max = range.endInclusive.roundToInt()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Filter", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Grades", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (min == max) Grades.label(min) else "${Grades.label(min)} – ${Grades.label(max)}", style = MaterialTheme.typography.titleMedium)
            }
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = Grades.MIN.toFloat()..Grades.MAX.toFloat(),
                steps = Grades.MAX - Grades.MIN - 1,
                onValueChangeFinished = { onChange(filter.copy(minGrade = min, maxGrade = max)) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Grades.label(Grades.MIN), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Grades.label(Grades.MAX), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onChange(filter.copy(includeProjects = !filter.includeProjects)) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Include projects", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = filter.includeProjects, onCheckedChange = { onChange(filter.copy(includeProjects = it)) })
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onReset, enabled = filter.isActive) { Text("Reset") }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
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

internal val previewRoutes: List<Route> = listOf(
    Route(1, "Warm-up traverse", 1, emptyList(), 0, 0),
    Route(2, "The kicker crimp problem with a very long name that has to be cut off", 5, emptyList(), 0, 0),
    Route(3, "Pinch and pray", 7, emptyList(), 0, 0),
    Route(4, "Big move project", null, emptyList(), 0, 0),
)

@TabletPreviews
@Composable
private fun RouteListPreview() {
    HomeWallTheme {
        RouteListContent(
            state = RouteListUiState(
                loaded = true,
                routes = previewRoutes,
                totalRoutes = 6,
                filter = GradeFilter(minGrade = 1, maxGrade = 9),
                connection = ConnectionState.Simulated,
                banner = "Placeholder layout. Map your wall in Settings → Wall Setup.",
            ),
            snackbarHostState = remember { SnackbarHostState() },
            connectControl = { ConnectButton(ConnectionState.Simulated, onClick = {}) },
            onLightsOff = {},
            onSortSelected = {},
            onFilterChange = {},
            onResetFilter = {},
            onOpenRoute = {},
            onCreateRoute = {},
            onOpenSettings = {},
        )
    }
}

@TabletPreviews
@Composable
private fun RouteListFilteredOutPreview() {
    HomeWallTheme {
        RouteListContent(
            state = RouteListUiState(loaded = true, totalRoutes = 3, filter = GradeFilter(includeProjects = false, minGrade = 12)),
            snackbarHostState = remember { SnackbarHostState() },
            connectControl = { ConnectButton(ConnectionState.Simulated, onClick = {}) },
            onLightsOff = {},
            onSortSelected = {},
            onFilterChange = {},
            onResetFilter = {},
            onOpenRoute = {},
            onCreateRoute = {},
            onOpenSettings = {},
        )
    }
}
