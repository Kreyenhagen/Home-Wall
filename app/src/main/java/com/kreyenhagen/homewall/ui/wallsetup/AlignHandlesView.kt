package com.kreyenhagen.homewall.ui.wallsetup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kreyenhagen.homewall.data.photo.AlignmentEdge
import com.kreyenhagen.homewall.data.photo.NormPoint
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The raw photo with one draggable handle per panel-edge end (§8.6 Align): bottom to top, left
 * and right. Lines join them into the panel outlines, and a magnifier follows the handle being
 * dragged.
 */
@Composable
fun AlignHandlesView(
    photo: ImageBitmap,
    edges: List<AlignmentEdge>,
    edgeNames: List<String>,
    onMoveHandle: (edgeIndex: Int, left: Boolean, point: NormPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf<HandleRef?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }

    val fit = if (viewSize.width > 0 && viewSize.height > 0) PhotoFit(photo.width, photo.height, viewSize) else null
    val currentFit by rememberUpdatedState(fit)
    val currentEdges by rememberUpdatedState(edges)
    val currentOnMove by rememberUpdatedState(onMoveHandle)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                val grabRadius = 48.dp.toPx()
                detectDragGestures(
                    onDragStart = { start ->
                        val f = currentFit ?: return@detectDragGestures
                        val nearest = handleRefs(currentEdges.size).minByOrNull { ref ->
                            val p = f.toScreen(ref.pointIn(currentEdges))
                            hypot(p.x - start.x, p.y - start.y)
                        } ?: return@detectDragGestures
                        val p = f.toScreen(nearest.pointIn(currentEdges))
                        if (hypot(p.x - start.x, p.y - start.y) <= grabRadius) {
                            dragging = nearest
                            dragPosition = p
                        }
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onDrag = { change, delta ->
                        val ref = dragging ?: return@detectDragGestures
                        val f = currentFit ?: return@detectDragGestures
                        change.consume()
                        dragPosition += delta
                        currentOnMove(ref.edge, ref.left, f.toNorm(dragPosition))
                    },
                )
            },
    ) {
        val f = fit ?: return@Canvas
        drawImage(
            image = photo,
            dstOffset = IntOffset(f.rect.left.roundToInt(), f.rect.top.roundToInt()),
            dstSize = IntSize(f.rect.width.roundToInt(), f.rect.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        drawOutlines(edges, f)
        handleRefs(edges.size).forEach { ref ->
            val name = edgeNames.getOrElse(ref.edge) { "Edge ${ref.edge + 1}" } + if (ref.left) " L" else " R"
            drawHandle(f.toScreen(ref.pointIn(edges)), handleColor(ref.edge), name, ref.left, textMeasurer, active = ref == dragging)
        }
        dragging?.let { ref -> drawLoupe(photo, f, f.toScreen(ref.pointIn(edges))) }
    }
}

private data class HandleRef(val edge: Int, val left: Boolean) {
    fun pointIn(edges: List<AlignmentEdge>): NormPoint = edges[edge].let { if (left) it.left else it.right }
}

private fun handleRefs(edgeCount: Int) = (0 until edgeCount).flatMap { listOf(HandleRef(it, true), HandleRef(it, false)) }

/** Fits the photo in the view (like ContentScale.Fit) and converts between screen and normalized photo coordinates. */
private class PhotoFit(photoWidth: Int, photoHeight: Int, view: IntSize) {
    private val scale = min(view.width.toFloat() / photoWidth, view.height.toFloat() / photoHeight)
    val rect: Rect = run {
        val w = photoWidth * scale
        val h = photoHeight * scale
        val left = (view.width - w) / 2f
        val top = (view.height - h) / 2f
        Rect(left, top, left + w, top + h)
    }

    fun toScreen(p: NormPoint) = Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)

    fun toNorm(o: Offset) = NormPoint(
        ((o.x - rect.left) / rect.width).coerceIn(0f, 1f),
        ((o.y - rect.top) / rect.height).coerceIn(0f, 1f),
    )
}

private val HANDLE_COLORS = listOf(Color(0xFFFF7043), Color(0xFFFFEE58), Color(0xFF4FC3F7), Color(0xFFBA68C8))

private fun handleColor(edge: Int) = HANDLE_COLORS[edge % HANDLE_COLORS.size]

private val LABEL_STYLE = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

private const val LOUPE_ZOOM = 3f

/** Each edge left↔right, plus each side between neighboring edges, so the panels show as quads. */
private fun DrawScope.drawOutlines(edges: List<AlignmentEdge>, f: PhotoFit) {
    fun line(a: NormPoint, b: NormPoint, color: Color) {
        drawLine(Color.Black.copy(alpha = 0.6f), f.toScreen(a), f.toScreen(b), strokeWidth = 4.dp.toPx())
        drawLine(color, f.toScreen(a), f.toScreen(b), strokeWidth = 2.dp.toPx())
    }
    edges.forEachIndexed { i, edge ->
        line(edge.left, edge.right, handleColor(i))
        edges.getOrNull(i + 1)?.let { next ->
            line(edge.left, next.left, Color.White)
            line(edge.right, next.right, Color.White)
        }
    }
}

private fun DrawScope.drawHandle(
    center: Offset,
    color: Color,
    label: String,
    left: Boolean,
    textMeasurer: TextMeasurer,
    active: Boolean,
) {
    val radius = (if (active) 16.dp else 12.dp).toPx()
    drawCircle(Color.Black.copy(alpha = 0.35f), radius, center)
    drawCircle(Color.Black, radius + 1.dp.toPx(), center, style = Stroke(4.dp.toPx()))
    drawCircle(color, radius, center, style = Stroke(2.5.dp.toPx()))
    drawCircle(color, 2.dp.toPx(), center)

    val text = textMeasurer.measure(label, LABEL_STYLE)
    val gap = radius + 6.dp.toPx()
    // Labels sit outside the wall: left handles label to the left, right handles to the right.
    val x = if (left) center.x - gap - text.size.width else center.x + gap
    val topLeft = Offset(x, center.y - text.size.height / 2f)
    drawRect(Color.Black.copy(alpha = 0.55f), topLeft - Offset(4.dp.toPx(), 2.dp.toPx()),
        androidx.compose.ui.geometry.Size(text.size.width + 8.dp.toPx(), text.size.height + 4.dp.toPx()))
    drawText(text, topLeft = topLeft)
}

/** A 3× magnifier above the finger (or below it near the top edge), with a crosshair on the handle. */
private fun DrawScope.drawLoupe(photo: ImageBitmap, f: PhotoFit, handle: Offset) {
    val radius = 72.dp.toPx()
    val lift = radius + 56.dp.toPx()
    var center = Offset(handle.x, handle.y - lift)
    if (center.y - radius < 0f) center = Offset(handle.x, handle.y + lift)
    center = Offset(center.x.coerceIn(radius, size.width - radius), center.y)

    val circle = Path().apply { addOval(Rect(center, radius)) }
    clipPath(circle) {
        drawRect(Color.Black)
        withTransform({
            translate(center.x - handle.x, center.y - handle.y)
            scale(LOUPE_ZOOM, LOUPE_ZOOM, pivot = handle)
        }) {
            drawImage(
                image = photo,
                dstOffset = IntOffset(f.rect.left.roundToInt(), f.rect.top.roundToInt()),
                dstSize = IntSize(f.rect.width.roundToInt(), f.rect.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
        val arm = 14.dp.toPx()
        val stroke = 1.5.dp.toPx()
        drawLine(Color.White, center - Offset(arm, 0f), center + Offset(arm, 0f), stroke)
        drawLine(Color.White, center - Offset(0f, arm), center + Offset(0f, arm), stroke)
    }
    drawCircle(Color.Black, radius + 1.dp.toPx(), center, style = Stroke(3.dp.toPx()))
    drawCircle(Color.White, radius, center, style = Stroke(2.dp.toPx()))
}
