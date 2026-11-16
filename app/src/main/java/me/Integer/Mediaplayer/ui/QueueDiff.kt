package me.Integer.Mediaplayer.ui

/** Tells what kind of change turned one queue order into the next, from the slot ids alone. */
internal object QueueDiff {

    const val SAME = 0

    /** The same songs in a different order (shuffle on or off). */
    const val REORDERED = 1
    const val REMOVED_ONE = 2
    const val INSERTED_ONE = 3

    /** Anything else, such as a new queue. */
    const val REPLACED = 4

    /** [index] is the removed or inserted position, or -1 for the other kinds. */
    class Change(val kind: Int, val index: Int)

    fun compare(previous: LongArray, next: LongArray): Change {
        if (previous.contentEquals(next)) return Change(SAME, -1)
        if (next.size == previous.size - 1) {
            val gap = singleGap(previous, next)
            if (gap >= 0) return Change(REMOVED_ONE, gap)
        }
        if (next.size == previous.size + 1) {
            val gap = singleGap(next, previous)
            if (gap >= 0) return Change(INSERTED_ONE, gap)
        }
        if (next.size == previous.size && sameMembers(previous, next)) return Change(REORDERED, -1)
        return Change(REPLACED, -1)
    }

    /**
     * @param longer exactly one element longer than [shorter]
     * @return the position in [longer] whose removal gives [shorter], or -1 when there is none
     */
    private fun singleGap(longer: LongArray, shorter: LongArray): Int {
        var gap = shorter.size
        for (i in shorter.indices) {
            if (longer[i] != shorter[i]) {
                gap = i
                break
            }
        }
        for (i in gap until shorter.size) {
            if (longer[i + 1] != shorter[i]) return -1
        }
        return gap
    }

    private fun sameMembers(first: LongArray, second: LongArray): Boolean {
        return first.sortedArray().contentEquals(second.sortedArray())
    }
}
