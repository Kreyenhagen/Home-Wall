package com.kreyenhagen.homewall.ui.wall

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.layout.ledsLabel
import com.kreyenhagen.homewall.data.layout.ringShape
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.led.ringColor
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The wall with its overlays (§7.2–§7.5), shared by Route Display, Route Editor and Wall Setup.
 *
 * Shows the straightened [image] (or a plain wall-colored rectangle without one), debug markers
 * (tiles, each labeled with its LED numbers), a ring around each hold in [rings], and the LED
 * preview dots. With [zoomEnabled]: pinch to zoom 1×–5×, two-finger pan, and double-tap to reset;
 * a single tap still selects. [popup] is shown anchored to the tile [popupHoldId], e.g. a menu.
 */
@Composable
fun WallView(
    layout: WallLayout,
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
    rings: Map<Int, Color> = emptyMap(),
    ringSizeIn: Float = 3.5f,
    showMarkers: Boolean = false,
    /** holdId → color: the debug LED preview (§7.3), from the last frame actually sent. */
    ledPreview: Map<Int, Color> = emptyMap(),
    showLabels: Boolean = true,
    zoomEnabled: Boolean = false,
    /** Caps the tap radius around a hold (§7.4), in inches. */
    maxHitRadiusIn: Float = Float.POSITIVE_INFINITY,
    /**
     * Taps hit a hold anywhere inside its ring, drawn or not, instead of near its center (the
     * Route Editor, §7.4). Where rings overlap, the smaller ring wins.
     */
    tapInsideRings: Boolean = false,
    /** When set, a one-finger drag reports its movement in wall inches (Review → Move, §12.2). */
    onDrag: ((dxIn: Float, dyIn: Float) -> Unit)? = null,
    /** Press and hold a tile to drag it, or hold and let go (Map LEDs, §12.1). */
    holdPress: HoldPressActions? = null,
    /** Draws handles on one hold's ring and lets them be dragged (Wall Setup → Rings, §12.4). */
    ringHandles: RingHandleActions? = null,
    popupHoldId: Int? = null,
    popup: @Composable () -> Unit = {},
    onTapHold: ((holdId: Int) -> Unit)? = null,
    onTapEmpty: ((WallPoint) -> Unit)? = null,
) {
    val wall = layout.wall
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val zoomPan = remember { ZoomPan() }
    LaunchedEffect(viewSize, wall.widthIn, wall.heightIn) { zoomPan.reset() }

    val transform = if (viewSize.width > 0 && viewSize.height > 0 && wall.widthIn > 0f && wall.heightIn > 0f) {
        WallTransform(wall.widthIn, wall.heightIn, viewSize.width.toFloat(), viewSize.height.toFloat(), zoomPan.zoom, zoomPan.pan)
    } else {
        null
    }

    val holdsById = remember(layout.holds) { layout.holds.associateBy { it.id } }
    val medianSpacingIn = remember(layout.holds) { HoldHitTest.medianNearestNeighborIn(layout.holds) }

    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    // Keyed on ids and LEDs only, so dragging a tile doesn't measure every label again.
    val labelKeys = layout.holds.map { it.id to it.leds }
    val labels: Map<Int, TextLayoutResult> = remember(labelKeys, showMarkers, showLabels, textMeasurer) {
        if (showMarkers && showLabels) {
            layout.holds.associate { it.id to textMeasurer.measure(it.ledsLabel, LABEL_STYLE) }
        } else {
            emptyMap()
        }
    }

    val currentTransform by rememberUpdatedState(transform)
    val currentHolds by rememberUpdatedState(layout.holds)
    val currentSpacing by rememberUpdatedState(medianSpacingIn)
    val currentOnTapHold by rememberUpdatedState(onTapHold)
    val currentOnTapEmpty by rememberUpdatedState(onTapEmpty)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentMaxHitRadius by rememberUpdatedState(maxHitRadiusIn)
    val currentTapInsideRings by rememberUpdatedState(tapInsideRings)
    val currentHoldPress by rememberUpdatedState(holdPress)
    val currentRingHandles by rememberUpdatedState(ringHandles)
    val currentHoldsById by rememberUpdatedState(holdsById)
    val currentRingSize by rememberUpdatedState(ringSizeIn)
    val haptics = LocalHapticFeedback.current
    val zoomedIn = zoomPan.zoom > 1f

    /** A press-and-hold just took this gesture, so the tap detector mustn't also count it as a tap. */
    val tapTaken = remember { BooleanFlag() }

    fun Density.holdAt(position: Offset): Hold? {
        val t = currentTransform ?: return null
        val point = t.screenToWall(position)
        if (currentTapInsideRings) {
            return HoldHitTest.ringAt(currentHolds, point, currentRingSize, MIN_HIT_RADIUS.toPx() / t.scale)
        }
        val radius = HoldHitTest.hitRadiusIn(currentSpacing, t.scale, MIN_HIT_RADIUS.toPx(), currentMaxHitRadius)
        return HoldHitTest.holdAt(currentHolds, point, radius)
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Double-tap only exists while zoomed in, so plain taps aren't delayed at 1×.
                .pointerInput(zoomEnabled && zoomedIn) {
                    detectTapGestures(
                        onDoubleTap = if (zoomEnabled && zoomedIn) {
                            { zoomPan.reset() }
                        } else {
                            null
                        },
                        onTap = { position ->
                            if (tapTaken.value) {
                                tapTaken.value = false
                                return@detectTapGestures
                            }
                            val t = currentTransform ?: return@detectTapGestures
                            val hold = holdAt(position)
                            if (hold != null) currentOnTapHold?.invoke(hold.id) else currentOnTapEmpty?.invoke(t.screenToWall(position))
                        },
                    )
                }
                // Later in the chain than the tap detector, so it sees each event first and the up it
                // consumes cancels the tap.
                .pointerInput(holdPress != null) {
                    if (holdPress == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tapTaken.value = false
                        val hold = holdAt(down.position) ?: return@awaitEachGesture
                        if (!awaitStillPress(down)) return@awaitEachGesture

                        tapTaken.value = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val actions = currentHoldPress ?: return@awaitEachGesture
                        actions.onPressed(hold.id)
                        try {
                            trackHeldTile(down, actions, viewConfiguration.touchSlop, { currentTransform }, hold.id)
                        } catch (e: CancellationException) {
                            actions.onCancel()
                            throw e
                        }
                    }
                }
                .pointerInput(onDrag != null) {
                    if (onDrag == null) return@pointerInput
                    detectDragGestures { change, _ ->
                        val t = currentTransform ?: return@detectDragGestures
                        val from = t.screenToWall(change.previousPosition)
                        val to = t.screenToWall(change.position)
                        change.consume()
                        currentOnDrag?.invoke(to.x - from.x, to.y - from.y)
                    }
                }
                // Sees each event before the tap detector; a press on a handle is consumed, so it never
                // selects a hold. Before the zoom handler, so a second finger still starts a pinch.
                .pointerInput(ringHandles != null) {
                    if (ringHandles == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val actions = currentRingHandles ?: return@awaitEachGesture
                        val hold = currentHoldsById[actions.holdId] ?: return@awaitEachGesture
                        val t = currentTransform ?: return@awaitEachGesture
                        val touch = HANDLE_TOUCH_RADIUS.toPx()
                        val (handle, spot) = ringHandleSpots(hold, currentRingSize, t).entries
                            .filter { (it.value - down.position).getDistance() <= touch }
                            .minByOrNull { (it.value - down.position).getDistance() }
                            ?.toPair()
                            ?: return@awaitEachGesture
                        down.consume()
                        // Keep the handle where it was grabbed relative to the finger, so it doesn't jump.
                        val grab = spot - down.position
                        try {
                            trackRingHandle(down, handle, grab, actions) { currentTransform }
                        } catch (e: CancellationException) {
                            actions.onCancel()
                            throw e
                        }
                    }
                }
                .pointerInput(zoomEnabled) {
                    if (!zoomEnabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                currentTransform?.let { t ->
                                    val next = t.transformedBy(
                                        centroid = event.calculateCentroid(useCurrent = true),
                                        zoomChange = event.calculateZoom(),
                                        panChange = event.calculatePan(),
                                    )
                                    zoomPan.zoom = next.zoom
                                    zoomPan.pan = next.pan
                                }
                                // Consuming cancels the tap detector, so a pinch never selects a hold.
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            val t = transform ?: return@Canvas
            val rect = t.wallRect

            if (image != null) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                drawRect(WallColors.PlainWall, topLeft = rect.topLeft, size = rect.size)
            }

            if (image == null || showMarkers) drawFolds(layout, t, strong = showMarkers)
            if (showMarkers) drawMarkers(layout.holds, labels, t)
            drawRings(rings, holdsById, ringSizeIn, t)
            ringHandles?.let { h -> holdsById[h.holdId]?.let { drawRingHandles(it, ringSizeIn, t) } }
            drawLedPreview(ledPreview, holdsById, t)
        }

        // An anchor spanning the tile's height, so a menu opens just below (or above) the tile.
        val anchor = popupHoldId?.let(holdsById::get)
        if (anchor != null && transform != null) {
            val center = transform.wallToScreen(anchor.x, anchor.y)
            Box(
                Modifier
                    .offset { IntOffset(center.x.roundToInt(), (center.y - MARKER_SIZE.toPx() / 2f).roundToInt()) }
                    .size(1.dp, MARKER_SIZE),
            ) { popup() }
        }
    }
}

/**
 * Press and hold a tile (Map LEDs, §12.1). [onPressed] fires once the press has been held still
 * long enough. Dragging then moves the tile: [onDrag] with the movement in wall inches, then
 * [onDragEnd]. Letting go without dragging calls [onReleased] instead, e.g. to open a menu.
 * [onCancel] means the gesture ended some other way, such as a second finger starting a pinch.
 */
class HoldPressActions(
    val onPressed: (holdId: Int) -> Unit,
    val onDrag: (dxIn: Float, dyIn: Float) -> Unit,
    val onDragEnd: () -> Unit,
    val onReleased: (holdId: Int) -> Unit,
    val onCancel: () -> Unit,
)

/** A plain flag shared by two gesture handlers; nothing draws from it. */
private class BooleanFlag(var value: Boolean = false)

/**
 * Waits out the long-press timeout. True if [down] was still held there without moving more than
 * the touch slop, and no second finger or other handler took the gesture.
 */
private suspend fun AwaitPointerEventScope.awaitStillPress(down: PointerInputChange): Boolean {
    val slop = viewConfiguration.touchSlop
    val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed || change.isConsumed) break
            if (event.changes.count { it.pressed } > 1) break
            if ((change.position - down.position).getDistance() > slop) break
        }
        true
    }
    return ended == null
}

/** After the long press: drag the tile until the finger lifts. Every event is consumed. */
private suspend fun AwaitPointerEventScope.trackHeldTile(
    down: PointerInputChange,
    actions: HoldPressActions,
    slop: Float,
    transform: () -> WallTransform?,
    holdId: Int,
) {
    var dragging = false
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
            actions.onCancel()
            return
        }
        if (!change.pressed) {
            change.consume()
            if (dragging) actions.onDragEnd() else actions.onReleased(holdId)
            return
        }
        val t = transform()
        if (t != null) {
            // Small wobble while holding still isn't a drag; past the slop the tile follows the finger.
            val from = when {
                dragging -> change.previousPosition
                (change.position - down.position).getDistance() > slop -> down.position.also { dragging = true }
                else -> null
            }
            if (from != null) {
                val a = t.screenToWall(from)
                val b = t.screenToWall(change.position)
                actions.onDrag(b.x - a.x, b.y - a.y)
            }
        }
        change.consume()
    }
}

/** Drag a ring handle until the finger lifts. Every event is consumed. */
private suspend fun AwaitPointerEventScope.trackRingHandle(
    down: PointerInputChange,
    handle: RingHandle,
    grab: Offset,
    actions: RingHandleActions,
    transform: () -> WallTransform?,
) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
            actions.onCancel()
            return
        }
        if (!change.pressed) {
            change.consume()
            actions.onDragEnd()
            return
        }
        if (change.position != change.previousPosition) {
            transform()?.let { actions.onDrag(handle, it.screenToWall(change.position + grab)) }
        }
        change.consume()
    }
}

/** Where the selected ring's handles are on screen, matching how [drawRings] draws the ring. */
private fun Density.ringHandleSpots(hold: Hold, ringSizeIn: Float, t: WallTransform): Map<RingHandle, Offset> {
    val shape = hold.ringShape(ringSizeIn)
    val minRadius = MIN_RING_RADIUS.toPx()
    return RingGeometry.handlePositions(
        center = t.wallToScreen(hold.x, hold.y),
        rxPx = max(shape.radiusXIn * t.scale, minRadius),
        ryPx = max(shape.radiusYIn * t.scale, minRadius),
        angleDeg = shape.angleDeg,
        rotateGapPx = ROTATE_HANDLE_GAP.toPx(),
    )
}

/** Zoom and pan, kept in one stable object so gesture handlers always write to the current state. */
@Stable
private class ZoomPan {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }
}

object WallColors {
    /** Stand-in for the wall when there's no photo. */
    val PlainWall = Color(0xFF4A443D)

    /** The rotate handle on a selected ring (Wall Setup → Rings). The size handles are white. */
    val RotateHandle = Color(0xFFFFC400)

    /** Debug-marker color for a hold's [group]: stable per name, neutral without one. */
    fun groupColor(group: String?): Color {
        if (group.isNullOrBlank()) return Color(0xFFB0BEC5)
        val hue = abs(group.hashCode() % 360).toFloat()
        return Color.hsv(hue, 0.65f, 0.95f)
    }
}

private val MIN_HIT_RADIUS = 24.dp
private val MIN_RING_RADIUS = 12.dp
private val HANDLE_RADIUS = 8.dp
private val HANDLE_TOUCH_RADIUS = 24.dp
private val ROTATE_HANDLE_GAP = 40.dp
private val CENTER_HANDLE_ARM = 10.dp
private val RING_STROKE = 3.dp
private val MARKER_SIZE = 22.dp
private val LED_GLOW_RADIUS = 9.dp
private val LED_CORE_RADIUS = 3.dp
private val LABEL_STYLE = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)

private fun DrawScope.drawFolds(layout: WallLayout, t: WallTransform, strong: Boolean) {
    val edges = layout.wall.edgeYs
    if (edges.size < 3) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))
    val color = Color.White.copy(alpha = if (strong) 0.6f else 0.25f)
    for (y in edges.subList(1, edges.size - 1)) {
        drawLine(color, t.wallToScreen(0f, y), t.wallToScreen(layout.wall.widthIn, y), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
    }
}

private fun DrawScope.drawMarkers(holds: List<Hold>, labels: Map<Int, TextLayoutResult>, t: WallTransform) {
    val minSide = MARKER_SIZE.toPx()
    val padding = 3.dp.toPx()
    val outline = Stroke(1.dp.toPx())
    for (hold in holds) {
        val label = labels[hold.id]
        val width = max(minSide, (label?.size?.width ?: 0) + 2 * padding)
        val size = Size(width, minSide)
        val center = t.wallToScreen(hold.x, hold.y)
        val topLeft = Offset(center.x - width / 2f, center.y - minSide / 2f)
        val color = WallColors.groupColor(hold.group)
        drawRect(color.copy(alpha = 0.45f), topLeft, size)
        drawRect(color.copy(alpha = 0.9f), topLeft, size, style = outline)
        if (label != null) {
            drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f))
        }
    }
}

private fun DrawScope.drawRings(rings: Map<Int, Color>, holdsById: Map<Int, Hold>, ringSizeIn: Float, t: WallTransform) {
    if (rings.isEmpty()) return
    val stroke = Stroke(RING_STROKE.toPx())
    val minRadius = MIN_RING_RADIUS.toPx()
    for ((holdId, color) in rings) {
        val hold = holdsById[holdId] ?: continue
        val center = t.wallToScreen(hold.x, hold.y)
        val shape = hold.ringShape(ringSizeIn)
        val rx = max(shape.radiusXIn * t.scale, minRadius)
        val ry = max(shape.radiusYIn * t.scale, minRadius)
        // Just the colored stroke, with no dark outline (§7.3).
        if (abs(rx - ry) < 0.5f) {
            drawCircle(color, rx, center, style = stroke)
        } else {
            rotate(shape.angleDeg, pivot = center) {
                drawOval(color, Offset(center.x - rx, center.y - ry), Size(2 * rx, 2 * ry), style = stroke)
            }
        }
    }
}

/**
 * The selected ring's handles (Wall Setup → Rings): a crosshair on the center to move it, white
 * dots for width and height, and a yellow dot to rotate.
 */
private fun DrawScope.drawRingHandles(hold: Hold, ringSizeIn: Float, t: WallTransform) {
    val spots = ringHandleSpots(hold, ringSizeIn, t)
    val dark = Color.Black.copy(alpha = 0.8f)
    val height = spots.getValue(RingHandle.HEIGHT)
    val rotate = spots.getValue(RingHandle.ROTATE)
    drawLine(dark, height, rotate, strokeWidth = 3.5.dp.toPx())
    drawLine(Color.White, height, rotate, strokeWidth = 1.5.dp.toPx())

    val center = spots.getValue(RingHandle.CENTER)
    val arm = CENTER_HANDLE_ARM.toPx()
    for ((width, color) in listOf(4.dp.toPx() to dark, 2.dp.toPx() to Color.White)) {
        drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = width)
        drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = width)
    }
    drawCircle(dark, 4.5.dp.toPx(), center)
    drawCircle(Color.White, 3.dp.toPx(), center)

    val radius = HANDLE_RADIUS.toPx()
    for ((handle, spot) in spots) {
        if (handle == RingHandle.CENTER) continue
        drawCircle(dark, radius + 1.5.dp.toPx(), spot)
        drawCircle(if (handle == RingHandle.ROTATE) WallColors.RotateHandle else Color.White, radius, spot)
    }
}

/** Small glowing dots, one per hold whose LEDs are lit (§7.3). Sits inside the ring, so both show. */
private fun DrawScope.drawLedPreview(dots: Map<Int, Color>, holdsById: Map<Int, Hold>, t: WallTransform) {
    if (dots.isEmpty()) return
    val glow = LED_GLOW_RADIUS.toPx()
    val core = LED_CORE_RADIUS.toPx()
    for ((holdId, color) in dots) {
        val hold = holdsById[holdId] ?: continue
        val center = t.wallToScreen(hold.x, hold.y)
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0.35f), Color.Transparent), center, glow),
            glow,
            center,
        )
        drawCircle(Color.Black.copy(alpha = 0.6f), core + 1.dp.toPx(), center)
        drawCircle(lerpToWhite(color), core, center)
    }
}

/** A lit LED's center looks almost white. */
private fun lerpToWhite(color: Color): Color =
    Color(red = 0.5f + color.red / 2f, green = 0.5f + color.green / 2f, blue = 0.5f + color.blue / 2f)

@TabletPreviews
@Composable
private fun WallViewPreview() {
    val layout = PlaceholderLayout.create()
    HomeWallTheme {
        WallView(
            layout = layout,
            image = null,
            showMarkers = true,
            rings = mapOf(5 to HoldRole.START.ringColor, 60 to HoldRole.MIDDLE.ringColor, 61 to HoldRole.MIDDLE.ringColor, 148 to HoldRole.FINISH.ringColor),
            ledPreview = mapOf(5 to HoldRole.START.ringColor, 60 to HoldRole.MIDDLE.ringColor, 61 to HoldRole.MIDDLE.ringColor, 148 to HoldRole.FINISH.ringColor),
        )
    }
}
