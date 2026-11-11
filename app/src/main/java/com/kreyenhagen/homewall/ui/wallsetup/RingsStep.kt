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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.layout.RingShape
import com.kreyenhagen.homewall.data.layout.ringShape
import com.kreyenhagen.homewall.data.layout.withHoldRing
import com.kreyenhagen.homewall.ui.components.LayoutProblems
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import com.kreyenhagen.homewall.ui.wall.RingHandle
import com.kreyenhagen.homewall.ui.wall.RingHandleActions
import com.kreyenhagen.homewall.ui.wall.WallPoint
import com.kreyenhagen.homewall.ui.wall.WallView
import java.util.Locale
import kotlin.math.roundToInt

/** Callbacks for [RingsStep]. */
class RingsActions(
    val onTapHold: (Int) -> Unit,
    val onTapEmpty: () -> Unit,
    val onDragHandle: (RingHandle, WallPoint) -> Unit,
    val onEndHandleDrag: () -> Unit,
    val onCancelHandleDrag: () -> Unit,
    val onWidth: (Float) -> Unit,
    val onHeight: (Float) -> Unit,
    val onAngle: (Float) -> Unit,
    val onSliderDone: () -> Unit,
    val onMakeCircle: () -> Unit,
    val onUseDefault: () -> Unit,
    val onRevert: () -> Unit,
    /** Goes to Map LEDs, at the first unmapped LED. */
    val onMapLeds: () -> Unit,
) {
    companion object {
        val None = RingsActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * Wall Setup → Rings (§12.4): every hold's ring on the wall, without the tiles. Tap one to select
 * it, then drag its handles or use the sliders to change its width, height and rotation. The
 * controls sit beside the wall (below it in portrait).
 */
@Composable
fun RingsStep(state: RingsUiState, actions: RingsActions, modifier: Modifier = Modifier) {
    when {
        !state.loaded -> Unit
        state.layout == null -> LayoutProblems(state.layoutProblems, modifier)
        state.needsMapping -> NeedsMapping(actions, modifier)
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val wall = @Composable { m: Modifier -> RingsWall(state, actions, m) }
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    wall(Modifier.weight(1f).fillMaxHeight())
                    RingsControls(state, actions, Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    wall(Modifier.weight(1f).fillMaxWidth())
                    RingsControls(state, actions, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun NeedsMapping(actions: RingsActions, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Map your LEDs first. Each hold gets its own ring, and the placeholder layout has no real holds.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = actions.onMapLeds) { Text("Go to Map LEDs") }
    }
}

@Composable
private fun RingsWall(state: RingsUiState, actions: RingsActions, modifier: Modifier) {
    val layout = state.layout ?: return
    val selected = state.selected
    Box(modifier.padding(8.dp)) {
        WallView(
            layout = layout,
            image = state.image,
            rings = state.rings,
            ringSizeIn = state.ringSizeIn,
            showMarkers = false,
            zoomEnabled = true,
            ringHandles = selected?.let {
                RingHandleActions(it.id, actions.onDragHandle, actions.onEndHandleDrag, actions.onCancelHandleDrag)
            },
            onTapHold = actions.onTapHold,
            onTapEmpty = { actions.onTapEmpty() },
        )
    }
}

@Composable
private fun RingsControls(state: RingsUiState, actions: RingsActions, modifier: Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (state.unmapped > 0) {
            FlowRow(verticalArrangement = Arrangement.Center) {
                Text(
                    "${MapLedsViewModel.countText(state.unmapped)} still to map. You can size rings now and map the rest later. ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                TextButton(onClick = actions.onMapLeds) { Text("Map them") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        val hold = state.selected
        val shape = state.shape
        if (hold == null || shape == null) {
            Text(
                "Tap a ring to change its size or move it. Its LEDs light up on the wall. Pinch to zoom in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Rings without their own size use Settings → Ring size (${inches(state.ringSizeIn * 2f)} across).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }

        Text("Hold ${hold.id}", style = MaterialTheme.typography.titleMedium)
        Text(
            "LED${if (hold.leds.size > 1) "s" else ""} ${hold.leds.joinToString(", ")} · " +
                if (state.hasOwnRing) "its own ring" else "default ring from Settings",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Center: ${state.selectedPosition}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        RingSlider("Width", shape.widthIn, RingShape.SIZE_RANGE_IN, inches(shape.widthIn), actions.onWidth, actions.onSliderDone)
        RingSlider("Height", shape.heightIn, RingShape.SIZE_RANGE_IN, inches(shape.heightIn), actions.onHeight, actions.onSliderDone)
        RingSlider("Rotation", shape.angleDeg, 0f..359f, "${shape.angleDeg.roundToInt()}°", actions.onAngle, actions.onSliderDone)
        Text(
            "Or drag the ring's handles: the crosshair in the middle moves the ring, and the hold's tile with it. " +
                "The white dots change the width and height, and the yellow one rotates it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = actions.onMakeCircle, enabled = !shape.isCircle) { Text("Make circle") }
            OutlinedButton(onClick = actions.onUseDefault, enabled = state.hasOwnRing) { Text("Use default size") }
            TextButton(onClick = actions.onRevert, enabled = state.canRevert) { Text("Revert") }
        }
    }
}

/** One compact slider row. It's saved when let go ([onDone]); the value comes from the view model. */
@Composable
private fun RingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(80.dp))
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(valueText, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
    }
}

private fun inches(value: Float): String = String.format(Locale.getDefault(), "%.1f in", value)

@TabletPreviews
@Composable
private fun RingsStepPreview() {
    val placeholder = PlaceholderLayout.create()
    val layout = placeholder.copy(placeholder = false)
        .withHoldRing(placeholder.holds[60].id, RingShape(5f, 2.5f, 30f))
        .withHoldRing(placeholder.holds[90].id, RingShape.circle(5f))
    val hold = layout.holds[60]
    HomeWallTheme {
        RingsStep(
            state = RingsUiState(
                loaded = true,
                layout = layout,
                unmapped = 12,
                selected = hold,
                shape = hold.ringShape(3.5f),
                hasOwnRing = true,
                selectedPosition = ReviewViewModel.positionText(layout, hold),
                rings =layout.holds.associate { it.id to if (it.id == hold.id) RingsViewModel.SELECTED else RingsViewModel.RING },
            ),
            actions = RingsActions.None,
        )
    }
}
