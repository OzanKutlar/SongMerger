package me.Integer.Mediaplayer.playback

import androidx.media3.common.Timeline

/**
 * The queue as a read-only list over the player's immutable timeline. Creating it costs
 * nothing; a row is only read from the timeline the first time it is asked for.
 * Main thread only.
 */
internal class TimelineQueue private constructor(
    val timeline: Timeline,
    override val size: Int
) : AbstractList<QueueItem>() {

    private val window = Timeline.Window()
    private val rows = arrayOfNulls<QueueItem>(size)

    override fun get(index: Int): QueueItem {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("index $index, size $size")
        }
        return rows[index] ?: read(index).also { rows[index] = it }
    }

    private fun read(index: Int): QueueItem {
        val item = timeline.getWindow(index, window).mediaItem
        val meta = item.mediaMetadata
        val uid = QueueCommands.uidOf(item).takeIf { it != 0L } ?: -(index.toLong() + 1L)
        return QueueItem(
            uid = uid,
            trackUri = item.mediaId,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString().orEmpty()
        )
    }

    /** Two queues are equal when they show the same timeline; rows are never compared. */
    override fun equals(other: Any?): Boolean {
        return other === this || (other is TimelineQueue && other.timeline === timeline)
    }

    override fun hashCode(): Int {
        return System.identityHashCode(timeline)
    }

    companion object {
        val EMPTY = TimelineQueue(Timeline.EMPTY, 0)

        /** At most [limit] rows of [timeline]. */
        fun of(timeline: Timeline, limit: Int): TimelineQueue {
            if (timeline.isEmpty || limit <= 0) return EMPTY
            return TimelineQueue(timeline, minOf(timeline.windowCount, limit))
        }
    }
}
