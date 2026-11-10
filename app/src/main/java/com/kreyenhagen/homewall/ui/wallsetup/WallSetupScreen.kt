package com.kreyenhagen.homewall.ui.wallsetup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kreyenhagen.homewall.ui.components.BackButton
import com.kreyenhagen.homewall.ui.components.StubBody
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme

private enum class WallSetupStep(val title: String, val description: String) {
    PHOTO("Photo", "Take or choose a photo of the wall. Arrives in M2."),
    ALIGN("Align", "Drag six handles onto the panel edges to straighten the photo. Arrives in M2."),
    MAP_LEDS("Map LEDs", "Light one LED at a time and tap where it shows up. Arrives in M6."),
    REVIEW("Review", "Check every hold against the real wall. Arrives in M6."),
}

/** Stub (M1). Steps are filled in by M2 (Photo, Align) and M6 (Map LEDs, Review) (§8.6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallSetupScreen(onBack: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(WallSetupStep.PHOTO) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wall Setup") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = step.ordinal) {
                WallSetupStep.entries.forEach {
                    Tab(selected = it == step, onClick = { step = it }, text = { Text(it.title) })
                }
            }
            StubBody(title = step.title, description = step.description)
        }
    }
}

@TabletPreviews
@Composable
private fun WallSetupPreview() {
    HomeWallTheme { WallSetupScreen(onBack = {}) }
}
