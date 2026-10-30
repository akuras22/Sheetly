package com.akuras.sheetly.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerStabilizerTest {

    private fun detection(shift: Float = 0f) = Detection(
        DocumentQuad(
            Point(0.1f + shift, 0.1f),
            Point(0.9f + shift, 0.1f),
            Point(0.9f + shift, 0.9f),
            Point(0.1f + shift, 0.9f),
        ),
        confidence = 0.6f,
    )

    @Test
    fun becomesStableOnlyAfterHoldingStill() {
        val stabilizer = CornerStabilizer()
        assertFalse(stabilizer.update(detection())!!.isStable)
        val results = List(10) { stabilizer.update(detection()) }
        assertFalse(results.first()!!.isStable)
        assertTrue(results.last()!!.isStable)
    }

    @Test
    fun movementResetsStability() {
        val stabilizer = CornerStabilizer()
        repeat(10) { stabilizer.update(detection()) }
        assertFalse(stabilizer.update(detection(shift = 0.2f))!!.isStable)
    }

    @Test
    fun survivesShortDropoutsButNotLongOnes() {
        val stabilizer = CornerStabilizer()
        stabilizer.update(detection())
        assertNotNull(stabilizer.update(null))
        repeat(10) { stabilizer.update(null) }
        assertNull(stabilizer.update(null))
    }

    @Test
    fun keepsConfidenceOfLatestDetection() {
        val stabilizer = CornerStabilizer()
        assertEquals(0.6f, stabilizer.update(detection())!!.confidence, 0.001f)
    }

    private fun stableQuad(shift: Float = 0f, confidence: Float = 0.6f) =
        TrackedQuad(detection(shift).quad, isStable = true, confidence = confidence)

    @Test
    fun capturesOnlyOnceWhileThePageStaysPut() {
        val gate = AutoCaptureGate()
        assertTrue(gate.shouldCapture(stableQuad()))
        repeat(20) { assertFalse(gate.shouldCapture(stableQuad())) }
    }

    @Test
    fun capturesAgainAfterThePageWasRemoved() {
        val gate = AutoCaptureGate()
        assertTrue(gate.shouldCapture(stableQuad()))
        repeat(40) { assertFalse(gate.shouldCapture(null)) }
        assertTrue(gate.shouldCapture(stableQuad()))
    }

    @Test
    fun aFlickeringDetectionDoesNotCaptureAgain() {
        val gate = AutoCaptureGate()
        assertTrue(gate.shouldCapture(stableQuad()))
        repeat(30) {
            assertFalse(gate.shouldCapture(null))
            assertFalse(gate.shouldCapture(stableQuad()))
        }
    }

    @Test
    fun capturesAgainAfterThePageMoved() {
        val gate = AutoCaptureGate()
        assertTrue(gate.shouldCapture(stableQuad()))
        repeat(29) { assertFalse(gate.shouldCapture(stableQuad(shift = 0.3f))) }
        assertTrue(gate.shouldCapture(stableQuad(shift = 0.3f)))
    }

    @Test
    fun aDetectionJumpingBetweenSimilarOutlinesDoesNotCaptureAgain() {
        val gate = AutoCaptureGate()
        assertTrue(gate.shouldCapture(stableQuad()))
        repeat(100) { frame ->
            assertFalse(gate.shouldCapture(stableQuad(shift = if (frame % 2 == 0) 0.12f else 0f)))
        }
    }

    @Test
    fun ignoresUnsureDetections() {
        assertFalse(AutoCaptureGate().shouldCapture(stableQuad(confidence = 0.22f)))
    }
}
