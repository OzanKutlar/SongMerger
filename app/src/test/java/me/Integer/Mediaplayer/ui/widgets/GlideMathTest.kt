package me.Integer.Mediaplayer.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideMathTest {

    @Test
    fun easeStartsAndEndsAtRest() {
        assertEquals(0f, GlideMath.ease(0f), 1e-6f)
        assertEquals(1f, GlideMath.ease(1f), 1e-6f)
        assertEquals(0.5f, GlideMath.ease(0.5f), 1e-6f)
    }

    @Test
    fun easeIsSlowAtBothEnds() {
        assertTrue(GlideMath.ease(0.1f) < 0.1f)
        assertTrue(GlideMath.ease(0.9f) > 0.9f)
    }

    @Test
    fun easeNeverGoesBackwards() {
        var last = 0f
        for (step in 1..100) {
            val value = GlideMath.ease(step / 100f)
            assertTrue(value >= last)
            last = value
        }
    }

    @Test
    fun easeClampsOutOfRangeTimes() {
        assertEquals(0f, GlideMath.ease(-1f), 1e-6f)
        assertEquals(1f, GlideMath.ease(2f), 1e-6f)
    }

    @Test
    fun positionMovesFromStartToTarget() {
        assertEquals(10f, GlideMath.position(10f, 50f, 0f), 1e-4f)
        assertEquals(50f, GlideMath.position(10f, 50f, 1f), 1e-4f)
        assertEquals(30f, GlideMath.position(10f, 50f, 0.5f), 1e-4f)
    }

    @Test
    fun centresARowInTheMiddleOfTheList() {
        // 10 rows fit; row 100 is centred when the list starts 4.5 rows above it.
        assertEquals(95.5f, GlideMath.centredPosition(100, 80, 800, 1000), 1e-4f)
    }

    @Test
    fun rowsNearTheTopStopAtTheTop() {
        assertEquals(0f, GlideMath.centredPosition(2, 80, 800, 1000), 1e-4f)
    }

    @Test
    fun rowsNearTheBottomStopAtTheBottom() {
        assertEquals(990f, GlideMath.centredPosition(998, 80, 800, 1000), 1e-4f)
    }

    @Test
    fun shortListsNeverScroll() {
        assertEquals(0f, GlideMath.centredPosition(3, 80, 800, 5), 1e-4f)
    }

    @Test
    fun currentPositionCountsTheHiddenPartOfTheFirstRow() {
        assertEquals(12.5f, GlideMath.currentPosition(12, -40, 80), 1e-4f)
    }

    @Test
    fun longerGlidesTakeLongerWithinBounds() {
        assertEquals(GlideMath.MIN_MS, GlideMath.durationMs(0f))
        assertTrue(GlideMath.durationMs(5f) < GlideMath.durationMs(500f))
        assertEquals(GlideMath.MAX_MS, GlideMath.durationMs(1000000f))
        assertEquals(GlideMath.MIN_MS, GlideMath.durationMs(Float.NaN))
    }
}
