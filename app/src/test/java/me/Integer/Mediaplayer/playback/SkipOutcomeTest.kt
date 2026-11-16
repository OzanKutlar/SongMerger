package me.Integer.Mediaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipOutcomeTest {

    @Test
    fun restartIsExpectedOnlyPastTheThreshold() {
        assertFalse(SkipOutcome.expectsRestart(0L))
        assertFalse(SkipOutcome.expectsRestart(3000L))
        assertTrue(SkipOutcome.expectsRestart(3001L))
        assertTrue(SkipOutcome.expectsRestart(60000L))
    }

    @Test
    fun rewindIsBackwardsAndBounded() {
        assertEquals(-360f, SkipOutcome.rewindDegrees(0L), 0.01f)
        assertEquals(-720f, SkipOutcome.rewindDegrees(60000L), 0.01f)
        assertEquals(-1080f, SkipOutcome.rewindDegrees(600000L), 0.01f)
        assertEquals(-360f, SkipOutcome.rewindDegrees(-5000L), 0.01f)
    }
}
