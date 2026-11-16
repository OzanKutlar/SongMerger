package me.Integer.Mediaplayer.ui

/**
 * The rows picked on the queue screen. Rows are kept by queue slot id, so a selection
 * survives reordering, shuffle and songs being added. Pure Kotlin; main thread only.
 */
class QueueSelection {

    private val selected = LinkedHashSet<Long>()

    var isActive: Boolean = false
        private set

    val size: Int
        get() = selected.size

    fun start() {
        isActive = true
    }

    /** Leaves selection mode and forgets every pick. */
    fun stop() {
        isActive = false
        selected.clear()
    }

    fun clear() {
        selected.clear()
    }

    fun contains(uid: Long): Boolean {
        return uid in selected
    }

    /** @return true when [uid] is selected afterwards */
    fun toggle(uid: Long): Boolean {
        if (selected.remove(uid)) return false
        if (selected.size >= MAX_SELECTED) return false
        selected.add(uid)
        return true
    }

    fun selectAll(uids: LongArray) {
        for (uid in uids.take(MAX_SELECTED)) {
            if (selected.size >= MAX_SELECTED) return
            selected.add(uid)
        }
    }

    /**
     * Forgets picks that are no longer in the queue [uids].
     *
     * @return true when something was forgotten
     */
    fun retainOnly(uids: LongArray): Boolean {
        if (selected.isEmpty()) return false
        return selected.retainAll(uids.toHashSet())
    }

    /** Positions of the picked rows in the queue [uids], in ascending order. */
    fun positionsIn(uids: LongArray): List<Int> {
        if (selected.isEmpty()) return emptyList()
        return uids.indices.filter { uids[it] in selected }
    }

    companion object {
        const val MAX_SELECTED = 50000

        /**
         * Groups [positions] into runs of neighbouring rows, the last run first, so removing
         * them one run at a time keeps the positions of the remaining runs valid.
         * Negative and repeated positions are ignored.
         */
        fun removalRuns(positions: Collection<Int>): List<IntRange> {
            val sorted = positions.filter { it >= 0 }.distinct().sortedDescending()
            val runs = ArrayList<IntRange>()
            var high = -1
            var low = -1
            for (position in sorted) {
                if (high < 0) {
                    high = position
                    low = position
                } else if (position == low - 1) {
                    low = position
                } else {
                    runs.add(low..high)
                    high = position
                    low = position
                }
            }
            if (high >= 0) runs.add(low..high)
            return runs
        }
    }
}
