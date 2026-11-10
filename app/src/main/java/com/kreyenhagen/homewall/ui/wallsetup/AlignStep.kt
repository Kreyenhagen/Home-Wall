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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.photo.NormPoint
import com.kreyenhagen.homewall.data.photo.PhotoAlignment
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import com.kreyenhagen.homewall.ui.wall.WallPoint
import com.kreyenhagen.homewall.ui.wall.WallView

/** Callbacks for [AlignStep], grouped to keep the parameter list readable. */
class AlignActions(
    val onMoveHandle: (edgeIndex: Int, left: Boolean, point: NormPoint) -> Unit,
    val onWidthChange: (String) -> Unit,
    val onPanelChange: (index: Int, text: String) -> Unit,
    val onSave: () -> Unit,
    val onAdjustPoints: () -> Unit,
    val onCancelAdjust: () -> Unit,
    val onResetPoints: () -> Unit,
    val onTapHold: (Int) -> Unit,
    val onTapEmpty: (WallPoint) -> Unit,
)

/**
 * Wall Setup → Align (§8.6): drag six handles onto the panel edges, set the wall's size, then
 * check the straightened result with hold markers on it. Wall on the left in landscape.
 */
@Composable
fun AlignStep(state: WallSetupUiState, actions: AlignActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wall = @Composable { m: Modifier -> AlignWall(state, actions, m) }
        val controls = @Composable { m: Modifier -> AlignControls(state, actions, m) }
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                wall(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width(400.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                wall(Modifier.weight(1f).fillMaxWidth())
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AlignWall(state: WallSetupUiState, actions: AlignActions, modifier: Modifier) {
    Box(modifier.padding(8.dp), contentAlignment = Alignment.Center) {
        val layout = state.layout
        val draft = state.draft
        when {
            layout == null -> LayoutProblems(state.layoutProblems)
            state.alignMode == AlignMode.EDIT_POINTS && state.photo != null && draft != null ->
                AlignHandlesView(
                    photo = state.photo,
                    edges = draft.edges,
                    edgeNames = state.edgeNames,
                    onMoveHandle = actions.onMoveHandle,
                )
            else -> WallView(
                layout = layout,
                image = state.rectified,
                rings = state.selectedHoldId?.let { mapOf(it to Color.White) }.orEmpty(),
                ringSizeIn = state.ringSizeIn,
                showMarkers = true,
                zoomEnabled = true,
                onTapHold = actions.onTapHold,
                onTapEmpty = actions.onTapEmpty,
            )
        }
        if (state.working) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text("Straightening the photo…")
                }
            }
        }
    }
}

@Composable
private fun LayoutProblems(problems: List<String>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("wall_layout.json has problems", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        if (problems.isEmpty()) Text("Loading…")
        problems.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun AlignControls(state: WallSetupUiState, actions: AlignActions, modifier: Modifier) {
    val editing = state.alignMode == AlignMode.EDIT_POINTS
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            text = when {
                editing -> "Drag the six handles onto the wall: both ends of the floor line, of the fold, and of the top edge. Then Save."
                !state.hasPhoto -> "No photo yet, so the wall is shown plain. Take or choose one in the Photo step."
                else -> "Check that the markers sit on the holds. Pinch to zoom, double-tap to zoom back out, and tap a hold to see its details."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        val draft = state.draft
        val layout = state.layout
        if (draft != null && layout != null) {
            Spacer(Modifier.height(16.dp))
            Text("Wall size (inches along the surface)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                InchesField("Width", draft.widthText, actions.onWidthChange)
                layout.wall.panels.forEachIndexed { i, panel ->
                    InchesField(panel.name, draft.panelTexts.getOrElse(i) { "" }) { actions.onPanelChange(i, it) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) {
                Button(onClick = actions.onSave, enabled = !state.working) { Text("Save") }
                OutlinedButton(onClick = actions.onResetPoints, enabled = !state.working) { Text("Reset points") }
                if (state.hasSavedAlignment) TextButton(onClick = actions.onCancelAdjust) { Text("Cancel") }
            } else {
                Button(onClick = actions.onSave, enabled = state.sizesChanged && !state.working && layout != null) { Text("Save sizes") }
                if (state.photo != null) OutlinedButton(onClick = actions.onAdjustPoints, enabled = !state.working) { Text("Adjust points") }
            }
        }

        if (!editing) {
            state.tapInfo?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun InchesField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text("in") },
        singleLine = true,
        isError = WallSetupViewModel.parseInches(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.width(110.dp),
    )
}

@TabletPreviews
@Composable
private fun AlignStepPreview() {
    val layout = PlaceholderLayout.create()
    HomeWallTheme {
        AlignStep(
            state = WallSetupUiState(
                step = WallSetupStep.ALIGN,
                layout = layout,
                draft = AlignDraft(PhotoAlignment.defaultGuess(3).edges, "96", listOf("15", "99")),
                edgeNames = WallSetupViewModel.edgeNames(2),
                selectedHoldId = 37,
                tapInfo = "Hold 37 · LED 37 · x 56.0, y 27.0 in (Main)",
            ),
            actions = AlignActions({ _, _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}),
        )
    }
}
