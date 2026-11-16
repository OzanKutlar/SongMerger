package me.Integer.Mediaplayer.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrubMathTest {

    private val eps = 1e-3f
    private val duration = 200000L

    @Test
    fun unwrapsAcrossTheSeam() {
        assertEquals(20f, ScrubMath.unwrapDelta(170f, -170f), eps)
        assertEquals(-20f, ScrubMath.unwrapDelta(-170f, 170f), eps)
        assertEquals(10f, ScrubMath.unwrapDelta(0f, 10f), eps)
        assertEquals(-10f, ScrubMath.unwrapDelta(10f, 0f), eps)
    }

    @Test
    fun invalidAnglesGiveNoMovement() {
        assertEquals(0f, ScrubMath.unwrapDelta(Float.NaN, 10f), 0f)
        assertEquals(0f, ScrubMath.unwrapDelta(0f, Float.POSITIVE_INFINITY), 0f)
        assertEquals(0L, ScrubMath.degreesToMs(Float.NaN))
    }

    @Test
    fun oneTurnIsThirtySeconds() {
        assertEquals(30000L, ScrubMath.degreesToMs(360f))
        assertEquals(-15000L, ScrubMath.degreesToMs(-180f))
        assertEquals(360f, ScrubMath.msToDegrees(30000L), eps)
    }

    @Test
    fun targetIsClampedToTheTrack() {
        assertEquals(90000L, ScrubMath.target(60000L, 360f, duration))
        assertEquals(0L, ScrubMath.target(10000L, -720f, duration))
        assertEquals(duration, ScrubMath.target(190000L, 720f, duration))
    }

    @Test
    fun accumulatorStaysBoundedAndReversesAtOnce() {
        var degrees = 0f
        for (i in 0 until 10000) {
            degrees = ScrubMath.clampDegrees(60000L, degrees + 90f, duration)
        }
        assertEquals(ScrubMath.msToDegrees(140000L), degrees, eps)
        assertEquals(duration, ScrubMath.target(60000L, degrees, duration))
        degrees = ScrubMath.clampDegrees(60000L, degrees - 36f, duration)
        assertEquals(197000L, ScrubMath.target(60000L, degrees, duration))
    }

    @Test
    fun unknownDurationDisablesScrubbing() {
        assertEquals(0f, ScrubMath.clampDegrees(1000L, 90f, 0L), 0f)
        assertEquals(1000L, ScrubMath.target(1000L, 90f, 0L))
    }

    @Test
    fun detentsAreFiveSecondSteps() {
        assertEquals(0L, ScrubMath.detent(-5L, 5000L))
        assertEquals(2L, ScrubMath.detent(14999L, 5000L))
        assertEquals(3L, ScrubMath.detent(15000L, 5000L))
        assertEquals(0L, ScrubMath.detent(15000L, 0L))
    }
}
