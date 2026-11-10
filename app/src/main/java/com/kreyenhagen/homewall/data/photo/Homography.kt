package com.kreyenhagen.homewall.data.photo

import kotlin.math.abs

data class Vec2(val x: Double, val y: Double) {
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
}

/**
 * A 3×3 perspective transform, row-major with the last value 1. That's the same layout as
 * `android.graphics.Matrix.setValues`, so the rectifier hands it straight to a Matrix. It's
 * computed here in plain Kotlin (rather than with `Matrix.setPolyToPoly`) so the corner mapping
 * can be unit-tested on the JVM.
 */
class Homography(val values: DoubleArray) {
    init {
        require(values.size == 9)
    }

    fun map(p: Vec2): Vec2 {
        val v = values
        val w = v[6] * p.x + v[7] * p.y + v[8]
        return Vec2((v[0] * p.x + v[1] * p.y + v[2]) / w, (v[3] * p.x + v[4] * p.y + v[5]) / w)
    }

    companion object {
        /**
         * The transform that maps each of the 4 [src] points onto the matching [dst] point,
         * or null if the points are degenerate (e.g. three in a line).
         */
        fun fromQuads(src: List<Vec2>, dst: List<Vec2>): Homography? {
            require(src.size == 4 && dst.size == 4) { "Need exactly 4 point pairs" }
            // Solve A·h = b for h0..h7, two rows per point pair:
            //   u = (h0 x + h1 y + h2) / (h6 x + h7 y + 1)
            //   v = (h3 x + h4 y + h5) / (h6 x + h7 y + 1)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i]
                val (u, v) = dst[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val h = solve(a) ?: return null
            return Homography(h + 1.0)
        }

        /** Gaussian elimination with partial pivoting on an n×(n+1) augmented matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = a.size
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < 1e-12) return null
                a[col] = a[pivot].also { a[pivot] = a[col] }
                for (row in 0 until n) {
                    if (row == col) continue
                    val f = a[row][col] / a[col][col]
                    if (f == 0.0) continue
                    for (k in col..n) a[row][k] -= f * a[col][k]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }
    }
}

/** True if [q] (4 points in order) is a convex quad with non-zero area, i.e. its sides don't cross. */
fun isConvexQuad(q: List<Vec2>): Boolean {
    require(q.size == 4)
    var sign = 0
    for (i in 0 until 4) {
        val a = q[(i + 1) % 4] - q[i]
        val b = q[(i + 2) % 4] - q[(i + 1) % 4]
        val cross = a.x * b.y - a.y * b.x
        if (abs(cross) < 1e-9) return false
        val s = if (cross > 0) 1 else -1
        if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
}
