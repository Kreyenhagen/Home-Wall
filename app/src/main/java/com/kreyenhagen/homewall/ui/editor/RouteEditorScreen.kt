package com.kreyenhagen.homewall.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kreyenhagen.homewall.R
import com.kreyenhagen.homewall.ui.components.BackButton
import com.kreyenhagen.homewall.ui.components.StubBody
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme

/**
 * Stub (M1). The real editor arrives in M3 (§8.3, §8.4). Save and Delete already navigate the
 * way the real ones will, so the whole flow can be clicked through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteEditorScreen(
    isNew: Boolean,
    onBack: () -> Unit,
    onSaved: (routeId: Long) -> Unit,
    onDeleted: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New route" else "Edit route") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (!isNew) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete route") },
                                    onClick = {
                                        menuOpen = false
                                        onDeleted()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSaved(STUB_ROUTE_ID) }, modifier = Modifier.padding(end = 16.dp)) {
                    Text("Save")
                }
            }
        },
    ) { padding ->
        StubBody(
            title = "Route Editor",
            description = "Tapping holds, roles, undo/redo and the Save dialog arrive in M3.",
            modifier = Modifier.padding(padding),
        )
    }
}

/** Stand-in id until routes are stored (M3). */
private const val STUB_ROUTE_ID = 0L

@TabletPreviews
@Composable
private fun RouteEditorPreview() {
    HomeWallTheme { RouteEditorScreen(isNew = false, onBack = {}, onSaved = {}, onDeleted = {}) }
}
