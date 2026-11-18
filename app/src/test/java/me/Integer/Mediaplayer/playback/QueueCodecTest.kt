package me.Integer.Mediaplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueCodecTest {

    private val song = SavedItem("content://media/1", "Poisons", "Whales, Misdom", "Uncaged Vol. 11", uid = 7L)
    private val tricky = SavedItem("content://media/2", "Tab\there", "Line\nbreak", "Back\\slash\r", uid = 8L)
    private val step = SavedItem(
        "content://media/3", "Dies Irae", "Apashe", "Requiem", uid = 9L,
        clipStartMs = 30000L, clipEndMs = 95000L, crossfadeMs = 8000L, tempoRatio = 1.05f
    )
    private val queue = SavedQueue(listOf(song, tricky, step), false, emptyList())

    @Test
    fun roundTripKeepsEverySongAndTheShuffleOrder() {
        val shuffled = SavedQueue(listOf(song, tricky, step), true, listOf(9L, 7L, 8L))
        assertEquals(shuffled, QueueCodec.decode(QueueCodec.encode(shuffled)))
    }

    @Test
    fun orderIsDroppedWhenShuffleIsOff() {
        val plain = SavedQueue(listOf(song), false, listOf(7L))
        assertEquals(emptyList<Long>(), QueueCodec.decode(QueueCodec.encode(plain))?.originalOrder)
    }

    @Test
    fun rejectsUnknownOrEmptyData() {
        assertNull(QueueCodec.decode(""))
        assertNull(QueueCodec.decode("garbage\nitem\tx"))
        assertNull(QueueCodec.decode(QueueCodec.encode(SavedQueue(emptyList(), false, emptyList()))))
    }

    @Test
    fun skipsBrokenLines() {
        val text = QueueCodec.encode(SavedQueue(listOf(song), false, emptyList())) + "item\tcontent://x\tonly three\n"
        assertEquals(listOf(song), QueueCodec.decode(text)?.items)
    }

    @Test
    fun invalidClipPlaysTheWholeSong() {
        val broken = song.copy(clipStartMs = 5000L, clipEndMs = 1000L)
        val decoded = QueueCodec.decode(QueueCodec.encode(SavedQueue(listOf(broken), false, emptyList())))
        assertEquals(SavedItem.TO_END, decoded?.items?.single()?.clipEndMs)
    }

    @Test
    fun unescapeKeepsATrailingBackslash() {
        assertEquals("a\\", QueueCodec.unescape("a\\"))
    }

    @Test
    fun resolveUsesTheSavedIndexWhenItStillMatches() {
        val restored = QueueCodec.resolve(queue, SavedPosition(1, 8L, tricky.uri, 4200L, 2))
        assertEquals(1, restored.startIndex)
        assertEquals(4200L, restored.startPositionMs)
        assertEquals(2, restored.repeatMode)
    }

    @Test
    fun resolveFindsAMovedSongByItsSlotId() {
        val restored = QueueCodec.resolve(queue, SavedPosition(0, 9L, step.uri, 1000L, 0))
        assertEquals(2, restored.startIndex)
        assertEquals(1000L, restored.startPositionMs)
    }

    @Test
    fun resolveFallsBackToTheFile() {
        val restored = QueueCodec.resolve(queue, SavedPosition(5, 99L, song.uri, 300L, 0))
        assertEquals(0, restored.startIndex)
        assertEquals(300L, restored.startPositionMs)
    }

    @Test
    fun resolveStartsOverForAnUnknownSong() {
        val restored = QueueCodec.resolve(queue, SavedPosition(1, 99L, "content://gone", 5000L, 7))
        assertEquals(0, restored.startIndex)
        assertEquals(0L, restored.startPositionMs)
        assertEquals(0, restored.repeatMode)
    }

    @Test
    fun resolveWithoutAPositionStartsAtTheTop() {
        val restored = QueueCodec.resolve(queue, null)
        assertEquals(0, restored.startIndex)
        assertEquals(0L, restored.startPositionMs)
    }
}
