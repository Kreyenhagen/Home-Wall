package com.kreyenhagen.homewall.ui.wall

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.min

/** A point on the unfolded wall, in inches (§5). */
data class WallPoint(val x: Float, val y: Float)

/**
 * The only wall ↔ screen conversion at runtime (§7.2): fit the wall in the view like
 * `ContentScale.Fit` with y flipped, then zoom about the view's top-left corner, then pan.
 */
data class WallTransform(
    val wallWidthIn: Float,
    val wallHeightIn: Float,
    val viewWidth: Float,
    val viewHeight: Float,
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
) {
    init {
        require(wallWidthIn > 0f && wallHeightIn > 0f && viewWidth > 0f && viewHeight > 0f) { "Sizes must be positive" }
    }

    /** Screen pixels per inch at zoom 1. */
    val fitScale: Float = min(viewWidth / wallWidthIn, viewHeight / wallHeightIn)
    private val fitLeft = (viewWidth - wallWidthIn * fitScale) / 2f
    private val fitTop = (viewHeight - wallHeightIn * fitScale) / 2f

    /** Screen pixels per inch right now. */
    val scale: Float get() = fitScale * zoom

    fun wallToScreen(x: Float, y: Float): Offset = Offset(
        (fitLeft + x * fitScale) * zoom + pan.x,
        (fitTop + (wallHeightIn - y) * fitScale) * zoom + pan.y,
    )

    fun screenToWall(p: Offset): WallPoint = WallPoint(
        ((p.x - pan.x) / zoom - fitLeft) / fitScale,
        wallHeightIn - ((p.y - pan.y) / zoom - fitTop) / fitScale,
    )

    /** Where the whole wall sits on screen. */
    val wallRect: Rect get() = Rect(wallToScreen(0f, wallHeightIn), wallToScreen(wallWidthIn, 0f))

    /**
     * One step of a pinch: zoom by [zoomChange] about [centroid], then move by [panChange].
     * Zoom stays within [MIN_ZOOM]–[MAX_ZOOM] and the wall can't be dragged out of view (§7.5).
     */
    fun transformedBy(centroid: Offset, zoomChange: Float, panChange: Offset): WallTransform {
        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val applied = newZoom / zoom
        return copy(zoom = newZoom, pan = (pan - centroid) * applied + centroid + panChange).clamped()
    }

    /** Keeps the wall covering the view along any axis where it's bigger than the view, and centered where it's smaller. */
    fun clamped(): WallTransform = copy(
        pan = Offset(
            clampAxis(pan.x, fitLeft, wallWidthIn * fitScale, viewWidth),
            clampAxis(pan.y, fitTop, wallHeightIn * fitScale, viewHeight),
        ),
    )

    private fun clampAxis(pan: Float, fitStart: Float, fitSize: Float, view: Float): Float {
        val contentSize = fitSize * zoom
        val startAtZeroPan = fitStart * zoom
        return if (contentSize <= view) {
            (view - contentSize) / 2f - startAtZeroPan
        } else {
            pan.coerceIn(view - contentSize - startAtZeroPan, -startAtZeroPan)
        }
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
    }
}
