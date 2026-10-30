package com.akuras.sheetly.scan

import kotlin.math.hypot
import kotlin.math.max

internal data class TrackedQuad(val quad: DocumentQuad, val isStable: Boolean, val confidence: Float = 0f)

/**
 * Turns per-frame detections into a calm outline: small movements are smoothed, a single missed
 * frame keeps the outline, and a quad only counts as stable after it held still for a while.
 * Coordinates are normalized to the frame.
 */
internal class CornerStabilizer {
    private var current: DocumentQuad? = null
    private var stableFrames = 0
    private var missedFrames = 0

    fun update(detection: Detection?): TrackedQuad? {
        val previous = current
        val detected = detection?.quad
        val confidence = detection?.confidence ?: 0f
        if (detected == null) {
            missedFrames++
            stableFrames = 0
            if (missedFrames > MAX_MISSED_FRAMES) current = null
            return current?.let { TrackedQuad(it, isStable = false) }
        }
        missedFrames = 0
        if (previous == null) {
            current = detected
            stableFrames = 0
            return TrackedQuad(detected, isStable = false, confidence)
        }

        val movement = cornerDistance(previous, detected)
        if (movement > JUMP_DISTANCE) {
            current = detected
            stableFrames = 0
            return TrackedQuad(detected, isStable = false, confidence)
        }
        val smoothed = blend(previous, detected)
        current = smoothed
        stableFrames = if (movement < STILL_DISTANCE) stableFrames + 1 else 0
        return TrackedQuad(smoothed, isStable = stableFrames >= FRAMES_UNTIL_STABLE, confidence)
    }

    private fun blend(from: DocumentQuad, to: DocumentQuad): DocumentQuad {
        fun mix(a: Point, b: Point) = Point(a.x + (b.x - a.x) * BLEND, a.y + (b.y - a.y) * BLEND)
        return DocumentQuad(
            mix(from.topLeft, to.topLeft),
            mix(from.topRight, to.topRight),
            mix(from.bottomRight, to.bottomRight),
            mix(from.bottomLeft, to.bottomLeft),
        )
    }

    private companion object {
        const val MAX_MISSED_FRAMES = 6
        const val JUMP_DISTANCE = 0.07f
        const val STILL_DISTANCE = 0.015f
        const val FRAMES_UNTIL_STABLE = 7
        const val BLEND = 0.4f
    }
}

/** The largest distance between matching corners, in normalized coordinates. */
internal fun cornerDistance(first: DocumentQuad, second: DocumentQuad): Float {
    var largest = 0f
    val a = first.corners
    val b = second.corners
    for (corner in a.indices) {
        largest = max(largest, hypot(a[corner].x - b[corner].x, a[corner].y - b[corner].y))
    }
    return largest
}

/**
 * Decides when to take a photo by itself. After a photo it stays quiet until the document moved a
 * lot or has been gone for a while, otherwise a page lying still would be captured again and again.
 * Detection flickers and can jump between similar outlines, so a short pause applies as well and a
 * few frames without a quad say nothing. A duplicate page costs more than one tap on the shutter.
 * Call it once per camera frame.
 */
internal class AutoCaptureGate {
    private var capturedQuad: DocumentQuad? = null
    private var framesSinceCapture = 0
    private var framesWithoutDocument = 0

    fun shouldCapture(tracked: TrackedQuad?): Boolean {
        val captured = capturedQuad
        if (captured != null) {
            framesSinceCapture++
            framesWithoutDocument = if (tracked == null) framesWithoutDocument + 1 else 0
            if (framesSinceCapture < MIN_FRAMES_BETWEEN_PHOTOS) return false
            val isGone = framesWithoutDocument >= REARM_MISSING_FRAMES
            val hasMoved = tracked != null && cornerDistance(tracked.quad, captured) >= REARM_DISTANCE
            if (!isGone && !hasMoved) return false
            capturedQuad = null
        }
        if (tracked == null || !tracked.isStable || tracked.confidence < MIN_CONFIDENCE) return false
        capturedQuad = tracked.quad
        framesSinceCapture = 0
        framesWithoutDocument = 0
        return true
    }

    private companion object {
        /** Clear of the confidence of ordinary rectangles in a room, like a window. */
        const val MIN_CONFIDENCE = 0.4f
        const val REARM_DISTANCE = 0.2f
        const val REARM_MISSING_FRAMES = 12
        const val MIN_FRAMES_BETWEEN_PHOTOS = 30
    }
}
