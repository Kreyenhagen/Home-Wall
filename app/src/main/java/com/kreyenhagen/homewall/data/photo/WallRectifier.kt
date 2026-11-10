package com.kreyenhagen.homewall.data.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.kreyenhagen.homewall.data.layout.WallGeometry
import kotlin.math.roundToInt

/**
 * Straightens the wall photo into a flat, front-on image of the unfolded wall (§7.1), one panel
 * at a time. This is the only perspective math in the app: afterwards, wall inches map to image
 * pixels by plain scaling with y flipped.
 */
object WallRectifier {
    const val PX_PER_IN = 20f

    /** How panel [panelIndex] gets drawn: its photo quad, its band in the output, and the transform between them. */
    class PanelPlan(
        val panelIndex: Int,
        /** Photo pixels: bottom-left, bottom-right, top-right, top-left. */
        val src: List<Vec2>,
        /** Output pixels, same corner order. */
        val dst: List<Vec2>,
        /** Rows to draw, overlapping each neighbor by 1 px so there's no seam. */
        val clipTop: Float,
        val clipBottom: Float,
        val homography: Homography,
    )

    class Plan(val width: Int, val height: Int, val panels: List<PanelPlan>)

    sealed interface PlanResult {
        data class Ok(val plan: Plan) : PlanResult
        data class Failed(val message: String) : PlanResult
    }

    fun outputWidth(geometry: WallGeometry): Int = (geometry.widthIn * PX_PER_IN).roundToInt()
    fun outputHeight(geometry: WallGeometry): Int = (geometry.heightIn * PX_PER_IN).roundToInt()

    /** Works out the per-panel transforms. Pure math, so it's unit-tested. */
    fun plan(geometry: WallGeometry, alignment: PhotoAlignment, photoWidth: Int, photoHeight: Int): PlanResult {
        if (alignment.edges.size != geometry.panels.size + 1) {
            return PlanResult.Failed(
                "The alignment has ${alignment.edges.size} edges, but the wall needs ${geometry.panels.size + 1}.",
            )
        }
        val width = outputWidth(geometry)
        val height = outputHeight(geometry)
        val sx = width / geometry.widthIn.toDouble()
        val sy = height / geometry.heightIn.toDouble()

        fun photoPx(p: NormPoint) = Vec2(p.x.toDouble() * photoWidth, p.y.toDouble() * photoHeight)

        val panels = geometry.panels.indices.map { i ->
            val bottomEdge = alignment.edges[i]
            val topEdge = alignment.edges[i + 1]
            val src = listOf(photoPx(bottomEdge.left), photoPx(bottomEdge.right), photoPx(topEdge.right), photoPx(topEdge.left))

            // Wall y runs up from the floor; image y runs down from the top.
            val bandBottom = height - geometry.panelBottom(i) * sy
            val bandTop = height - geometry.panelBottom(i + 1) * sy
            val dst = listOf(Vec2(0.0, bandBottom), Vec2(width.toDouble(), bandBottom), Vec2(width.toDouble(), bandTop), Vec2(0.0, bandTop))

            val name = geometry.panels[i].name
            val homography = if (isConvexQuad(src)) Homography.fromQuads(src, dst) else null
            homography ?: return PlanResult.Failed(
                "The $name panel's points don't outline a panel (two sides cross or overlap). Drag them onto its corners.",
            )
            PanelPlan(
                panelIndex = i,
                src = src,
                dst = dst,
                clipTop = (bandTop - 1).coerceAtLeast(0.0).toFloat(),
                clipBottom = (bandBottom + 1).coerceAtMost(height.toDouble()).toFloat(),
                homography = homography,
            )
        }
        return PlanResult.Ok(Plan(width, height, panels))
    }

    /** Draws [photo] through each panel's transform. [photo] must be the size the plan was made for. */
    fun render(photo: Bitmap, plan: Plan): Bitmap {
        val out = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (panel in plan.panels) {
            val matrix = Matrix().apply { setValues(FloatArray(9) { panel.homography.values[it].toFloat() }) }
            canvas.save()
            canvas.clipRect(0f, panel.clipTop, plan.width.toFloat(), panel.clipBottom)
            canvas.drawBitmap(photo, matrix, paint)
            canvas.restore()
        }
        return out
    }
}
