package me.Integer.Mediaplayer.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTest {

    private val frame = 1f / 60f

    /** Runs [spring] for [seconds] and returns the lowest and highest values it reached. */
    private fun run(spring: Spring, seconds: Float): Pair<Float, Float> {
        var low = spring.value
        var high = spring.value
        val frames = (seconds / frame).toInt()
        for (i in 0 until frames) {
            spring.step(frame)
            low = minOf(low, spring.value)
            high = maxOf(high, spring.value)
        }
        return low to high
    }

    @Test
    fun criticallyDampedReachesTheTargetWithoutOvershoot() {
        val spring = Spring(400f, 1f)
        spring.reset(0f, 0f, 1f)
        val (_, high) = run(spring, 2f)
        assertTrue(high <= 1.0001f)
        assertTrue(spring.isSettled)
        assertEquals(1f, spring.value, 0.001f)
    }

    @Test
    fun underdampedOvershootsThenSettles() {
        val spring = Spring(400f, 0.3f)
        spring.reset(0f, 0f, 1f)
        val (_, high) = run(spring, 5f)
        assertTrue(high > 1.05f)
        assertTrue(spring.isSettled)
    }

    @Test
    fun anImpulseSquashesAndComesBackToRest() {
        val spring = Spring(500f, 0.35f)
        spring.reset(0f, 34f, 0f)
        val (low, high) = run(spring, 3f)
        assertTrue(high > 0.5f)
        assertTrue(low < 0f)
        assertTrue(spring.isSettled)
    }

    @Test
    fun longFramesStayStable() {
        val spring = Spring(500f, 0.35f)
        spring.reset(0f, 34f, 0f)
        for (i in 0 until 100) spring.step(0.1f)
        assertTrue(spring.value.isFinite())
        assertTrue(spring.isSettled)
    }

    @Test
    fun zeroOrBrokenTimesChangeNothing() {
        val spring = Spring(400f, 1f)
        spring.reset(0.25f, 1f, 1f)
        spring.step(0f)
        spring.step(-1f)
        spring.step(Float.NaN)
        assertEquals(0.25f, spring.value, 0f)
        assertEquals(1f, spring.velocity, 0f)
    }

    @Test
    fun settleJumpsToRest() {
        val spring = Spring(400f, 0.5f)
        spring.reset(0f, 5f, 1f)
        spring.settle()
        assertEquals(1f, spring.value, 0f)
        assertTrue(spring.isSettled)
    }
}
