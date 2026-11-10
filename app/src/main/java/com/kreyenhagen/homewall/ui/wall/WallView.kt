package com.kreyenhagen.homewall.ui.wall

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.PlaceholderLayout
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.led.ringColor
import com.kreyenhagen.homewall.ui.components.TabletPreviews
import com.kreyenhagen.homewall.ui.theme.HomeWallTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The wall with its overlays (§7.2–§7.5), shared by Route Display, Route Editor and Wall Setup.
 *
 * Shows the straightened [image] (or a plain wall-colored rectangle without one), debug markers,
 * a ring around each hold in [rings], and the LED preview dots. With [zoomEnabled]: pinch to zoom
 * 1×–5×, two-finger pan,
 * and double-tap to reset; a single tap still selects.
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
    val labels: Map<Int, TextLayoutResult> = remember(layout.holds, showMarkers, showLabels, textMeasurer) {
        if (showMarkers && showLabels) {
            layout.holds.associate { it.id to textMeasurer.measure(it.id.toString(), LABEL_STYLE) }
        } else {
            emptyMap()
        }
    }

    val currentTransform by rememberUpdatedState(transform)
    val currentHolds by rememberUpdatedState(layout.holds)
    val currentSpacing by rememberUpdatedState(medianSpacingIn)
    val currentOnTapHold by rememberUpdatedState(onTapHold)
    val currentOnTapEmpty by rememberUpdatedState(onTapEmpty)
    val zoomedIn = zoomPan.zoom > 1f

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            // Double-tap only exists while zoomed in, so plain taps aren't delayed at 1×.
            .pointerInput(zoomEnabled && zoomedIn) {
                detectTapGestures(
                    onDoubleTap = if (zoomEnabled && zoomedIn) {
                        { zoomPan.reset() }
                    } else {
                        null
                    },
                    onTap = { position ->
                        val t = currentTransform ?: return@detectTapGestures
                        val point = t.screenToWall(position)
                        val radius = HoldHitTest.hitRadiusIn(currentSpacing, t.scale, MIN_HIT_RADIUS.toPx())
                        val hold = HoldHitTest.holdAt(currentHolds, point, radius)
                        if (hold != null) currentOnTapHold?.invoke(hold.id) else currentOnTapEmpty?.invoke(point)
                    },
                )
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
        drawLedPreview(ledPreview, holdsById, t)
    }
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

    /** Debug-marker color for a hold's [group]: stable per name, neutral without one. */
    fun groupColor(group: String?): Color {
        if (group.isNullOrBlank()) return Color(0xFFB0BEC5)
        val hue = abs(group.hashCode() % 360).toFloat()
        return Color.hsv(hue, 0.65f, 0.95f)
    }
}

private val MIN_HIT_RADIUS = 24.dp
private val MIN_RING_RADIUS = 12.dp
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
        val width = maxOf(minSide, (label?.size?.width ?: 0) + 2 * padding)
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
    val outerStroke = Stroke(1.5.dp.toPx())
    val outerGap = RING_STROKE.toPx() / 2f + 1.dp.toPx()
    for ((holdId, color) in rings) {
        val hold = holdsById[holdId] ?: continue
        val center = t.wallToScreen(hold.x, hold.y)
        val radius = maxOf((hold.ringIn ?: ringSizeIn) * t.scale, MIN_RING_RADIUS.toPx())
        // A thin dark outline outside the colored ring keeps it visible on any photo (§7.3).
        drawCircle(Color.Black.copy(alpha = 0.75f), radius + outerGap, center, style = outerStroke)
        drawCircle(color, radius, center, style = stroke)
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
