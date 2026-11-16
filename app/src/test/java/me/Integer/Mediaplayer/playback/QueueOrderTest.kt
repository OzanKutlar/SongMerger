package me.Integer.Mediaplayer.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueOrderTest {

    @Test
    fun shuffledKeepsThePlayingSongFirstAndEverySongOnce() {
        val order = QueueOrder.shuffled(10, 4, Random(7))
        assertEquals(4, order[0])
        assertTrue(QueueOrder.isPermutation(order, 10))
    }

    @Test
    fun shuffledHandlesEmptyAndSingleQueues() {
        assertArrayEquals(IntArray(0), QueueOrder.shuffled(0, 0, Random(1)))
        assertArrayEquals(intArrayOf(0), QueueOrder.shuffled(1, 0, Random(1)))
    }

    @Test
    fun shuffledClampsAnActiveIndexOutOfRange() {
        val order = QueueOrder.shuffled(3, 9, Random(1))
        assertEquals(2, order[0])
        assertTrue(QueueOrder.isPermutation(order, 3))
    }

    @Test
    fun restoredReturnsToTheOriginalOrder() {
        val original = longArrayOf(1L, 2L, 3L, 4L)
        val current = longArrayOf(3L, 1L, 4L, 2L)
        assertArrayEquals(intArrayOf(1, 3, 0, 2), QueueOrder.restored(original, current))
    }

    @Test
    fun restoredSkipsRemovedSongsAndAppendsAddedOnes() {
        val original = longArrayOf(1L, 2L, 3L)
        val current = longArrayOf(3L, 9L, 1L)
        assertArrayEquals(intArrayOf(2, 0, 1), QueueOrder.restored(original, current))
    }

    @Test
    fun restoredHandlesDuplicateIds() {
        val order = QueueOrder.restored(longArrayOf(5L, 5L), longArrayOf(5L, 5L))
        assertArrayEquals(intArrayOf(0, 1), order)
    }

    @Test
    fun restoredWithoutAnOriginalLeavesTheQueueAlone() {
        val order = QueueOrder.restored(LongArray(0), longArrayOf(7L, 8L, 9L))
        assertTrue(QueueOrder.isIdentity(order))
    }

    @Test
    fun permutationCheckRejectsBadOrders() {
        assertFalse(QueueOrder.isPermutation(intArrayOf(0, 0), 2))
        assertFalse(QueueOrder.isPermutation(intArrayOf(0, 2), 2))
        assertFalse(QueueOrder.isPermutation(intArrayOf(0), 2))
        assertTrue(QueueOrder.isPermutation(intArrayOf(1, 0), 2))
    }
}
