package com.kreyenhagen.homewall.ui.routedisplay

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.kreyenhagen.homewall.R
import com.kreyenhagen.homewall.ui.components.BackButton
import com.kreyenhagen.homewall.ui.components.StubBody
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme

/** Stub (M1). The route pager arrives in M3 (§8.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDisplayScreen(routeId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Route") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit route")
                    }
                },
            )
        },
    ) { padding ->
        StubBody(
            title = "Route Display",
            description = "Route $routeId. Swiping between routes arrives in M3, and lighting them on the wall in M5.",
            modifier = Modifier.padding(padding),
        )
    }
}

@TabletPreviews
@Composable
private fun RouteDisplayPreview() {
    HomeWallTheme { RouteDisplayScreen(routeId = 1, onBack = {}, onEdit = {}) }
}
