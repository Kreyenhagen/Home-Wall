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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kreyenhagen.homewall.data.layout.MappingSummary
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.layout.ledCountLossNote
import com.kreyenhagen.homewall.data.layout.startedMapping
import com.kreyenhagen.homewall.data.layout.withLedAddedToHold
import com.kreyenhagen.homewall.data.layout.withLedInNewHold
import com.kreyenhagen.homewall.ui.components.LayoutProblems
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import com.kreyenhagen.homewall.ui.wall.WallPoint
import com.kreyenhagen.homewall.ui.wall.WallView

/** Callbacks for [MapLedsStep]. */
class MapLedsActions(
    val onStartMapping: (count: Int) -> Unit,
    val onChangeLedCount: (count: Int) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onJumpTo: (String) -> Boolean,
    val onHide: () -> Unit,
    val onTapHold: (Int) -> Unit,
    val onTapEmpty: (WallPoint) -> Unit,
) {
    companion object {
        val None = MapLedsActions({}, {}, {}, {}, { true }, {}, {}, {})
    }
}

/**
 * In Map LEDs a tap within this distance of a hold pairs the LED with it; anything farther makes
 * a new hold. Without a cap, the few, far-apart holds early in mapping would swallow every tap.
 */
const val MAP_HIT_RADIUS_IN = 3f

/**
 * Wall Setup → Map LEDs (§12.1): the wall lights one LED; tap where it shows up. Wall on the left
 * in landscape, controls below it in portrait.
 */
@Composable
fun MapLedsStep(state: MapLedsUiState, actions: MapLedsActions, modifier: Modifier = Modifier) {
    when {
        !state.loaded -> Unit
        state.layout == null -> LayoutProblems(state.layoutProblems, modifier)
        state.needsLedCount -> StartMapping(state, actions.onStartMapping, modifier)
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val wall = @Composable { m: Modifier -> MapWall(state, actions, m) }
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    wall(Modifier.weight(1f).fillMaxHeight())
                    MapControls(state, actions, Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    wall(Modifier.weight(1f).fillMaxWidth())
                    MapControls(state, actions, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun MapWall(state: MapLedsUiState, actions: MapLedsActions, modifier: Modifier) {
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
            maxHitRadiusIn = MAP_HIT_RADIUS_IN,
            onTapHold = actions.onTapHold,
            onTapEmpty = actions.onTapEmpty,
        )
    }
}

@Composable
private fun MapControls(state: MapLedsUiState, actions: MapLedsActions, modifier: Modifier) {
    var showCountDialog by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("LED ${state.current}", style = MaterialTheme.typography.headlineSmall)
            Text(
                " of ${state.ledCount}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(state.status, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Tap where it lights up. Tap a hold's marker to pair it with that hold. Pinch to zoom.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onPrevious, enabled = state.canGoBack) { Text("Prev") }
            OutlinedButton(onClick = actions.onNext, enabled = state.canGoForward) { Text("Next") }
            FilledTonalButton(onClick = actions.onHide) { Text("Hidden") }
            JumpField(actions.onJumpTo)
        }
        state.summary?.let { summary ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${summary.text} · ${summary.unmapped} unmapped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showCountDialog = true }) { Text("LED count") }
            }
        }
    }
    if (showCountDialog) {
        LedCountDialog(
            current = state.ledCount,
            summaryOf = { count -> state.layout?.ledCountLossNote(count) },
            onConfirm = {
                showCountDialog = false
                actions.onChangeLedCount(it)
            },
            onDismiss = { showCountDialog = false },
        )
    }
}

@Composable
private fun JumpField(onJumpTo: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val go = {
        if (onJumpTo(text)) {
            text = ""
            focus.clearFocus()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { new -> text = new.filter(Char::isDigit).take(5) },
            placeholder = { Text("LED #") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.width(100.dp),
        )
        TextButton(onClick = go, enabled = text.isNotEmpty()) { Text("Go") }
    }
}

/** Shown while the placeholder is active: confirm the LED count first (§12.1). */
@Composable
private fun StartMapping(state: MapLedsUiState, onStart: (Int) -> Unit, modifier: Modifier) {
    var text by rememberSaveable(state.suggestedLedCount) { mutableStateOf(state.suggestedLedCount.toString()) }
    val count = text.toIntOrNull()?.takeIf { it in 1..MapLedsViewModel.MAX_LED_COUNT }
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Card(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("Map your wall", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "The wall lights one LED at a time, and you tap where it shows up on the picture. " +
                        "Tap an existing hold to add the LED to it, or Hidden for an LED behind the wall. " +
                        "Every tap is saved, so you can stop and carry on later.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter(Char::isDigit).take(5) },
                    label = { Text("Number of LEDs") },
                    supportingText = { Text("${state.suggestedLedCount} is ${state.suggestedLedCountSource}.") },
                    isError = count == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This replaces the placeholder layout. Routes made on it lose their holds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { count?.let(onStart) }, enabled = count != null) { Text("Start mapping") }
            }
        }
    }
}

@Composable
private fun LedCountDialog(current: Int, summaryOf: (Int) -> String?, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val count = text.toIntOrNull()?.takeIf { it in 1..MapLedsViewModel.MAX_LED_COUNT }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LED count") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter(Char::isDigit).take(5) },
                    label = { Text("Number of LEDs") },
                    isError = count == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                count?.let(summaryOf)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { count?.let(onConfirm) }, enabled = count != null && count != current) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@TabletPreviews
@Composable
private fun MapLedsStepPreview() {
    val layout = PlaceholderLayout.create().startedMapping(550)
        .withLedInNewHold(0, 8f, 4f)
        .withLedInNewHold(1, 16f, 4f)
        .withLedAddedToHold(2, 1)
        .withLedInNewHold(3, 24f, 4f)
    HomeWallTheme {
        MapLedsStep(
            state = MapLedsUiState(
                loaded = true,
                layout = layout,
                current = 2,
                status = "In hold 1 with LED 1",
                summary = MappingSummary.of(layout),
                rings = mapOf(1 to androidx.compose.ui.graphics.Color.White),
            ),
            actions = MapLedsActions.None,
        )
    }
}

@TabletPreviews
@Composable
private fun StartMappingPreview() {
    HomeWallTheme {
        MapLedsStep(
            state = MapLedsUiState(
                loaded = true,
                layout = PlaceholderLayout.create(),
                needsLedCount = true,
                suggestedLedCount = 550,
                suggestedLedCountSource = "from the controller",
            ),
            actions = MapLedsActions.None,
        )
    }
}
