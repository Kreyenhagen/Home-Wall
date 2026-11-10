package com.kreyenhagen.homewall.ui.routedisplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kreyenhagen.homewall.R
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteHold
import com.kreyenhagen.homewall.ui.appViewModel
import com.kreyenhagen.homewall.ui.components.BackButton
import com.kreyenhagen.homewall.ui.components.DriveLeds
import com.kreyenhagen.homewall.ui.components.WallLeds
import com.kreyenhagen.homewall.ui.components.GradeChip
import com.kreyenhagen.homewall.ui.components.LayoutProblems
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import com.kreyenhagen.homewall.ui.wall.WallView

@Composable
fun RouteDisplayScreen(routeId: Long, onBack: () -> Unit, onEdit: (routeId: Long) -> Unit) {
    val vm = appViewModel {
        RouteDisplayViewModel(
            openedRouteId = routeId,
            routes = it.routeRepository.routes,
            settings = it.settingsRepository.settings,
            layout = it.layoutRepository.state,
            photo = it.wallPhotoRepository.state,
            sentFrame = it.ledSync.lastSent,
        )
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    // The current page's route, live: currentPage flips halfway through a swipe (§8.2). LedSync
    // drops stale frames when I swipe faster than the wall can keep up (§9.1).
    DriveLeds(state.ledFrame?.let(WallLeds::Route))
    RouteDisplayContent(
        state = state,
        onBack = onBack,
        onEdit = { id ->
            vm.onEdit(id)
            onEdit(id)
        },
        onPageChanged = vm::onPageChanged,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDisplayContent(
    state: RouteDisplayUiState,
    onBack: () -> Unit,
    onEdit: (routeId: Long) -> Unit,
    onPageChanged: (routeId: Long, index: Int) -> Unit,
) {
    val current = state.currentRoute
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { current?.let { onEdit(it.id) } }, enabled = current != null) {
                        Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit route")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val layout = state.layout
            when {
                !state.loaded -> Unit
                layout == null -> LayoutProblems(state.layoutProblems)
                state.pages.isEmpty() -> Text(
                    "This route no longer exists.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> RoutePager(state, layout, onPageChanged)
            }
        }
    }
}

@Composable
private fun RoutePager(state: RouteDisplayUiState, layout: WallLayout, onPageChanged: (Long, Int) -> Unit) {
    val pagerState = rememberPagerState(initialPage = state.currentIndex) { state.pages.size }
    val pages by rememberUpdatedState(state.pages)
    val reportPage by rememberUpdatedState(onPageChanged)

    // currentPage flips halfway through a swipe, which is when the wall should change (§8.2).
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            pages.getOrNull(page)?.let { reportPage(it.route.id, page) }
        }
    }
    // Pages are keyed by route id, so the pager normally stays on the same route when the list
    // changes. This catches the rest, e.g. the current route being deleted.
    LaunchedEffect(state.pages, state.currentIndex, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != state.currentIndex) {
            pagerState.scrollToPage(state.currentIndex)
        }
    }

    HorizontalPager(
        state = pagerState,
        key = { index -> state.pages.getOrNull(index)?.route?.id ?: index },
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        val page = state.pages.getOrNull(index) ?: return@HorizontalPager
        RoutePageContent(
            page = page,
            position = "${index + 1} / ${state.pages.size}",
            layout = layout,
            state = state,
        )
    }
}

/** Portrait: the wall above the route info. Landscape: the wall on the left, info on the right (§3). */
@Composable
private fun RoutePageContent(page: RoutePage, position: String, layout: WallLayout, state: RouteDisplayUiState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val wall = @Composable { modifier: Modifier ->
            WallView(
                layout = layout,
                image = state.image,
                modifier = modifier,
                rings = page.rings,
                ringSizeIn = state.ringSizeIn,
                showMarkers = state.showMarkers,
                ledPreview = state.ledPreview,
            )
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                wall(Modifier.weight(1f).fillMaxHeight())
                RouteInfo(page, position, Modifier.width(360.dp).fillMaxHeight().padding(24.dp))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                wall(Modifier.weight(1f).fillMaxWidth())
                RouteInfo(page, position, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun RouteInfo(page: RoutePage, position: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(page.route.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradeChip(page.route.grade, large = true)
            Spacer(Modifier.weight(1f))
            Text(position, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        page.missingNote?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@TabletPreviews
@Composable
private fun RouteDisplayPreview() {
    val layout = PlaceholderLayout.create()
    val route = Route(
        id = 1,
        name = "Pinch and pray",
        grade = 5,
        holds = listOf(
            RouteHold(5, HoldRole.START),
            RouteHold(27, HoldRole.MIDDLE),
            RouteHold(60, HoldRole.MIDDLE),
            RouteHold(97, HoldRole.MIDDLE),
            RouteHold(148, HoldRole.FINISH),
            RouteHold(999, HoldRole.MIDDLE),
        ),
        createdAt = 0,
        updatedAt = 0,
    )
    val page = RouteDisplayViewModel.pageFor(route, layout.holds.mapTo(HashSet()) { it.id })
    HomeWallTheme {
        RouteDisplayContent(
            state = RouteDisplayUiState(loaded = true, pages = listOf(page), layout = layout, showMarkers = true),
            onBack = {},
            onEdit = {},
            onPageChanged = { _, _ -> },
        )
    }
}
