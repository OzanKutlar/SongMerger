package me.Integer.Mediaplayer.playback

import kotlin.random.Random

/**
 * Works out queue orders. Every order is a list of positions in the current queue:
 * order[i] is the current position of the song that should end up at seat i.
 */
internal object QueueOrder {

    /** The playing song first, everything else in random order. */
    fun shuffled(count: Int, activeIndex: Int, random: Random): IntArray {
        if (count <= 0) return IntArray(0)
        val active = activeIndex.coerceIn(0, count - 1)
        val others = ArrayList<Int>(count - 1)
        for (index in 0 until count) {
            if (index != active) others.add(index)
        }
        others.shuffle(random)
        val order = IntArray(count)
        order[0] = active
        for (i in others.indices) order[i + 1] = others[i]
        return order
    }

    /**
     * The pre-shuffle order. Songs removed since then are skipped, and songs added since
     * then follow at the end in their current order.
     *
     * @param original slot ids in the order to return to
     * @param current slot ids of the queue as it is now
     */
    fun restored(original: LongArray, current: LongArray): IntArray {
        val seats = HashMap<Long, ArrayDeque<Int>>(current.size * 2)
        for (index in current.indices) {
            seats.getOrPut(current[index]) { ArrayDeque() }.addLast(index)
        }
        val order = IntArray(current.size)
        val placed = BooleanArray(current.size)
        var filled = 0
        for (uid in original) {
            val index = seats[uid]?.removeFirstOrNull() ?: continue
            order[filled] = index
            placed[index] = true
            filled++
        }
        for (index in current.indices) {
            if (placed[index]) continue
            order[filled] = index
            filled++
        }
        return order
    }

    /** True when [order] names each of the [count] positions exactly once. */
    fun isPermutation(order: IntArray, count: Int): Boolean {
        if (order.size != count) return false
        val seen = BooleanArray(count)
        for (index in order) {
            if (index < 0 || index >= count || seen[index]) return false
            seen[index] = true
        }
        return true
    }

    /** True when [order] leaves every song where it is. */
    fun isIdentity(order: IntArray): Boolean {
        for (i in order.indices) {
            if (order[i] != i) return false
        }
        return true
    }
}
