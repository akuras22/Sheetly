package com.akuras.sheetly.scan

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** A 3x3 projective transform, stored row by row. */
internal class Homography(private val m: FloatArray) {

    fun map(x: Float, y: Float): Pair<Float, Float> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    fun inverse(): Homography {
        val a = m[4] * m[8] - m[5] * m[7]
        val b = m[2] * m[7] - m[1] * m[8]
        val c = m[1] * m[5] - m[2] * m[4]
        val d = m[5] * m[6] - m[3] * m[8]
        val e = m[0] * m[8] - m[2] * m[6]
        val f = m[2] * m[3] - m[0] * m[5]
        val g = m[3] * m[7] - m[4] * m[6]
        val h = m[1] * m[6] - m[0] * m[7]
        val i = m[0] * m[4] - m[1] * m[3]
        val determinant = m[0] * a + m[1] * d + m[2] * g
        return Homography(floatArrayOf(a, b, c, d, e, f, g, h, i).also { values ->
            for (index in values.indices) values[index] /= determinant
        })
    }

    companion object {
        /** Maps the unit square to [quad], corners in the same clockwise order. */
        fun fromUnitSquare(quad: DocumentQuad): Homography {
            val (p0, p1, p2, p3) = quad.corners
            val dx1 = p1.x - p2.x
            val dx2 = p3.x - p2.x
            val sumX = p0.x - p1.x + p2.x - p3.x
            val dy1 = p1.y - p2.y
            val dy2 = p3.y - p2.y
            val sumY = p0.y - p1.y + p2.y - p3.y
            val determinant = dx1 * dy2 - dx2 * dy1
            val g = (sumX * dy2 - dx2 * sumY) / determinant
            val h = (dx1 * sumY - sumX * dy1) / determinant
            return Homography(
                floatArrayOf(
                    p1.x - p0.x + g * p1.x, p3.x - p0.x + h * p3.x, p0.x,
                    p1.y - p0.y + g * p1.y, p3.y - p0.y + h * p3.y, p0.y,
                    g, h, 1f,
                )
            )
        }
    }
}

/**
 * Estimates the real width to height ratio of a photographed rectangle from its perspective
 * (Zhang and He, "Whiteboard scanning"). Falls back to the mean side lengths when the quad is too
 * close to parallel for the estimate to be stable.
 */
internal fun estimateAspectRatio(quad: DocumentQuad, imageWidth: Int, imageHeight: Int): Float {
    val centerX = imageWidth / 2.0
    val centerY = imageHeight / 2.0
    val tl = doubleArrayOf(quad.topLeft.x - centerX, quad.topLeft.y - centerY, 1.0)
    val tr = doubleArrayOf(quad.topRight.x - centerX, quad.topRight.y - centerY, 1.0)
    val bl = doubleArrayOf(quad.bottomLeft.x - centerX, quad.bottomLeft.y - centerY, 1.0)
    val br = doubleArrayOf(quad.bottomRight.x - centerX, quad.bottomRight.y - centerY, 1.0)

    val k2 = dot(cross(tl, br), bl) / dot(cross(tr, br), bl)
    val k3 = dot(cross(tl, br), tr) / dot(cross(bl, br), tr)
    val n2 = DoubleArray(3) { k2 * tr[it] - tl[it] }
    val n3 = DoubleArray(3) { k3 * bl[it] - tl[it] }

    val fallback = meanSideRatio(quad)
    val focalSquared = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
    if (focalSquared.isNaN() || focalSquared <= 0.0) return fallback

    val weight = doubleArrayOf(1.0 / focalSquared, 1.0 / focalSquared, 1.0)
    val widthTerm = n2[0] * n2[0] * weight[0] + n2[1] * n2[1] * weight[1] + n2[2] * n2[2]
    val heightTerm = n3[0] * n3[0] * weight[0] + n3[1] * n3[1] * weight[1] + n3[2] * n3[2]
    val ratio = sqrt(widthTerm / heightTerm).toFloat()
    return if (ratio.isFinite() && ratio in 0.2f..5f) ratio else fallback
}

private fun meanSideRatio(quad: DocumentQuad): Float {
    val width = (hypot(quad.topRight.x - quad.topLeft.x, quad.topRight.y - quad.topLeft.y) +
        hypot(quad.bottomRight.x - quad.bottomLeft.x, quad.bottomRight.y - quad.bottomLeft.y)) / 2
    val height = (hypot(quad.bottomLeft.x - quad.topLeft.x, quad.bottomLeft.y - quad.topLeft.y) +
        hypot(quad.bottomRight.x - quad.topRight.x, quad.bottomRight.y - quad.topRight.y)) / 2
    return width / max(height, 1f)
}

private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
)

private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
