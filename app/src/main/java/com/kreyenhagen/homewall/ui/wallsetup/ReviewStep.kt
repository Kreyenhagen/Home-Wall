package com.kreyenhagen.homewall.ui.wallsetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kreyenhagen.homewall.data.layout.MappingSummary
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.ui.components.LayoutProblems
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import com.kreyenhagen.homewall.ui.wall.WallView

/** Callbacks for [ReviewStep]. */
class ReviewActions(
    val onTapHold: (Int) -> Unit,
    val onTapEmpty: () -> Unit,
    val onLightAll: (Boolean) -> Unit,
    val onShowUnmapped: () -> Unit,
    val onStartMove: () -> Unit,
    val onDrag: (dxIn: Float, dyIn: Float) -> Unit,
    val onSaveMove: () -> Unit,
    val onCancelMove: () -> Unit,
    val onRemoveLed: (Int) -> Unit,
    val onRequestDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onDismissDelete: () -> Unit,
    val onExport: () -> Unit,
) {
    companion object {
        val None = ReviewActions({}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * Wall Setup → Review (§12.2): the summary, tap a hold to light it and edit it, "Light all holds",
 * and export. The hold's details sit beside the wall (below it in portrait), so the wall stays
 * visible and other holds can still be tapped.
 */
@Composable
fun ReviewStep(state: ReviewUiState, actions: ReviewActions, modifier: Modifier = Modifier) {
    when {
        !state.loaded -> Unit
        state.layout == null -> LayoutProblems(state.layoutProblems, modifier)
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val wall = @Composable { m: Modifier -> ReviewWall(state, actions, m) }
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    wall(Modifier.weight(1f).fillMaxHeight())
                    ReviewControls(state, actions, Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    wall(Modifier.weight(1f).fillMaxWidth())
                    ReviewControls(state, actions, Modifier.fillMaxWidth())
                }
            }
        }
    }

    val selected = state.selected
    if (state.confirmDelete && selected != null) {
        AlertDialog(
            onDismissRequest = actions.onDismissDelete,
            title = { Text("Delete hold ${selected.id}?") },
            text = { Text("Its LEDs become unmapped. Routes that use this hold lose it.") },
            confirmButton = { TextButton(onClick = actions.onConfirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = actions.onDismissDelete) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReviewWall(state: ReviewUiState, actions: ReviewActions, modifier: Modifier) {
    val layout = state.layout ?: return
    Box(modifier.padding(8.dp)) {
        WallView(
            layout = layout,
            image = state.image,
            rings = state.rings,
            ringSizeIn = state.ringSizeIn,
            showMarkers = true,
            ledPreview = state.ledPreview,
            zoomEnabled = true,
            onTapHold = actions.onTapHold,
            onTapEmpty = { actions.onTapEmpty() },
            onDrag = if (state.moving) actions.onDrag else null,
        )
    }
}

@Composable
private fun ReviewControls(state: ReviewUiState, actions: ReviewActions, modifier: Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        state.summary?.let { summary ->
            FlowRow(verticalArrangement = Arrangement.Center) {
                Text(summary.text + " · ", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(onClick = actions.onShowUnmapped, enabled = summary.unmapped > 0) {
                    Text("${summary.unmapped} unmapped")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Light all holds", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.lightAll, onCheckedChange = actions.onLightAll)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        val hold = state.selected
        when {
            hold == null -> Text(
                "Tap a hold to light its LEDs and see its details.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.moving -> {
                Text("Moving hold ${hold.id}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Drag anywhere on the wall to move it; pinch to zoom for precision. ${state.selectedPosition}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.onSaveMove) { Text("Save position") }
                    TextButton(onClick = actions.onCancelMove) { Text("Cancel") }
                }
            }
            else -> {
                Text("Hold ${hold.id}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "LED${if (hold.leds.size > 1) "s" else ""} ${hold.leds.joinToString(", ")} · ${state.selectedPosition}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = actions.onStartMove) { Text("Move") }
                    OutlinedButton(onClick = actions.onRequestDelete) { Text("Delete hold") }
                    if (hold.leds.size > 1) {
                        hold.leds.forEach { led ->
                            AssistChip(onClick = { actions.onRemoveLed(led) }, label = { Text("Remove LED $led") })
                        }
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        OutlinedButton(onClick = actions.onExport) { Text("Export layout (JSON)") }
    }
}

@TabletPreviews
@Composable
private fun ReviewStepPreview() {
    val layout = PlaceholderLayout.create().copy(placeholder = false, ledCount = 200, hiddenLeds = (154 until 170).toList())
    val hold = layout.holds[37]
    HomeWallTheme {
        ReviewStep(
            state = ReviewUiState(
                loaded = true,
                layout = layout,
                summary = MappingSummary.of(layout),
                selected = hold,
                selectedPosition = ReviewViewModel.positionText(layout, hold),
                rings = mapOf(hold.id to Color.White),
            ),
            actions = ReviewActions.None,
        )
    }
}
