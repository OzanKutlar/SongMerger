package me.Integer.Mediaplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSelectionTest {

    @Test
    fun toggle_selectsAndDeselects() {
        val selection = QueueSelection()
        selection.start()
        assertTrue(selection.toggle(5L))
        assertTrue(selection.contains(5L))
        assertFalse(selection.toggle(5L))
        assertFalse(selection.contains(5L))
        assertEquals(0, selection.size)
    }

    @Test
    fun stop_leavesModeAndForgetsPicks() {
        val selection = QueueSelection()
        selection.start()
        selection.selectAll(longArrayOf(1L, 2L, 3L))
        selection.stop()
        assertFalse(selection.isActive)
        assertEquals(0, selection.size)
    }

    @Test
    fun retainOnly_dropsSongsThatLeftTheQueue() {
        val selection = QueueSelection()
        selection.selectAll(longArrayOf(1L, 2L, 3L))
        assertTrue(selection.retainOnly(longArrayOf(1L, 3L, 9L)))
        assertEquals(2, selection.size)
        assertFalse(selection.contains(2L))
        assertFalse(selection.retainOnly(longArrayOf(1L, 3L)))
    }

    @Test
    fun positionsIn_followsTheCurrentOrder() {
        val selection = QueueSelection()
        selection.toggle(10L)
        selection.toggle(30L)
        assertEquals(listOf(0, 2), selection.positionsIn(longArrayOf(10L, 20L, 30L)))
        assertEquals(listOf(0, 1), selection.positionsIn(longArrayOf(30L, 10L, 20L)))
        assertEquals(emptyList<Int>(), selection.positionsIn(longArrayOf(20L)))
    }

    @Test
    fun removalRuns_emptyAndSingle() {
        assertEquals(emptyList<IntRange>(), QueueSelection.removalRuns(emptyList()))
        assertEquals(listOf(5..5), QueueSelection.removalRuns(listOf(5)))
    }

    @Test
    fun removalRuns_groupsNeighboursLastFirst() {
        assertEquals(listOf(7..7, 2..4), QueueSelection.removalRuns(listOf(2, 3, 4, 7)))
    }

    @Test
    fun removalRuns_handlesUnsortedRepeatsAndNegatives() {
        assertEquals(
            listOf(8..9, 4..4, 1..2),
            QueueSelection.removalRuns(listOf(4, 1, 2, 4, 9, 8, -3))
        )
    }
}
