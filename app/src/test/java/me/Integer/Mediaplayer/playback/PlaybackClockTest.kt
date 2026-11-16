package me.Integer.Mediaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClockTest {

    private val duration = 200000L

    @Test
    fun pausedPositionDoesNotAdvance() {
        assertEquals(5000L, PlaybackClock.estimate(5000L, 1000L, 1f, false, duration, 9000L))
    }

    @Test
    fun runningPositionAdvancesWithElapsedTime() {
        assertEquals(8000L, PlaybackClock.estimate(5000L, 1000L, 1f, true, duration, 4000L))
    }

    @Test
    fun speedScalesTheAdvance() {
        assertEquals(11000L, PlaybackClock.estimate(5000L, 1000L, 2f, true, duration, 4000L))
    }

    @Test
    fun invalidSpeedFallsBackToRealTime() {
        assertEquals(8000L, PlaybackClock.estimate(5000L, 1000L, 0f, true, duration, 4000L))
        assertEquals(8000L, PlaybackClock.estimate(5000L, 1000L, Float.NaN, true, duration, 4000L))
    }

    @Test
    fun unknownUpdateTimeDoesNotAdvance() {
        assertEquals(5000L, PlaybackClock.estimate(5000L, 0L, 1f, true, duration, 4000L))
    }

    @Test
    fun positionIsClampedToDuration() {
        assertEquals(duration, PlaybackClock.estimate(199000L, 1000L, 1f, true, duration, 10000L))
    }

    @Test
    fun unknownDurationDoesNotClamp() {
        assertEquals(8000L, PlaybackClock.estimate(5000L, 1000L, 1f, true, 0L, 4000L))
    }

    @Test
    fun unknownPositionReadsAsZero() {
        assertEquals(0L, PlaybackClock.estimate(-1L, 1000L, 1f, true, duration, 4000L))
    }

    @Test
    fun permilleRoundTrips() {
        assertEquals(250, PlaybackClock.toPermille(50000L, duration))
        assertEquals(50000L, PlaybackClock.fromPermille(250, duration))
        assertEquals(1000, PlaybackClock.toPermille(duration * 2L, duration))
        assertEquals(0, PlaybackClock.toPermille(50000L, 0L))
        assertEquals(duration, PlaybackClock.fromPermille(5000, duration))
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:00", PlaybackClock.format(0L))
        assertEquals("0:00", PlaybackClock.format(-500L))
        assertEquals("3:07", PlaybackClock.format(187000L))
        assertEquals("1:02:03", PlaybackClock.format(3723000L))
    }
}
