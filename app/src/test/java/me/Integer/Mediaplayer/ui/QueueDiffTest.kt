package me.Integer.Mediaplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueDiffTest {

    @Test
    fun identicalOrdersAreTheSame() {
        val change = QueueDiff.compare(longArrayOf(1L, 2L, 3L), longArrayOf(1L, 2L, 3L))
        assertEquals(QueueDiff.SAME, change.kind)
    }

    @Test
    fun emptyQueuesAreTheSame() {
        assertEquals(QueueDiff.SAME, QueueDiff.compare(LongArray(0), LongArray(0)).kind)
    }

    @Test
    fun sameSongsInAnotherOrderAreReordered() {
        val change = QueueDiff.compare(longArrayOf(1L, 2L, 3L), longArrayOf(3L, 1L, 2L))
        assertEquals(QueueDiff.REORDERED, change.kind)
    }

    @Test
    fun oneMissingSongIsARemoval() {
        val middle = QueueDiff.compare(longArrayOf(1L, 2L, 3L), longArrayOf(1L, 3L))
        assertEquals(QueueDiff.REMOVED_ONE, middle.kind)
        assertEquals(1, middle.index)
        val last = QueueDiff.compare(longArrayOf(1L, 2L, 3L), longArrayOf(1L, 2L))
        assertEquals(QueueDiff.REMOVED_ONE, last.kind)
        assertEquals(2, last.index)
    }

    @Test
    fun oneExtraSongIsAnInsert() {
        val first = QueueDiff.compare(longArrayOf(2L, 3L), longArrayOf(1L, 2L, 3L))
        assertEquals(QueueDiff.INSERTED_ONE, first.kind)
        assertEquals(0, first.index)
        val last = QueueDiff.compare(longArrayOf(1L, 2L), longArrayOf(1L, 2L, 3L))
        assertEquals(QueueDiff.INSERTED_ONE, last.kind)
        assertEquals(2, last.index)
    }

    @Test
    fun differentSongsAreAReplacement() {
        val change = QueueDiff.compare(longArrayOf(1L, 2L, 3L), longArrayOf(4L, 5L, 6L))
        assertEquals(QueueDiff.REPLACED, change.kind)
        val shorter = QueueDiff.compare(longArrayOf(1L, 2L, 3L), longArrayOf(2L, 9L))
        assertEquals(QueueDiff.REPLACED, shorter.kind)
    }
}
