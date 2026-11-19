package me.Integer.Mediaplayer.ui

import me.Integer.Mediaplayer.library.Track

/**
 * The songs picked in a song list, kept by URI so a pick survives sorting, searching and
 * library updates. Pure Kotlin; main thread only.
 */
class TrackSelection {

    private val picked = LinkedHashSet<String>()

    var isActive: Boolean = false
        private set

    val size: Int
        get() = picked.size

    fun start() {
        isActive = true
    }

    /** Leaves selection mode and forgets every pick. */
    fun stop() {
        isActive = false
        picked.clear()
    }

    fun clear() {
        picked.clear()
    }

    fun contains(uri: String): Boolean {
        return uri in picked
    }

    /** @return true when [uri] is picked afterwards */
    fun toggle(uri: String): Boolean {
        if (uri.isEmpty()) return false
        if (picked.remove(uri)) return false
        if (picked.size >= MAX_PICKED) return false
        picked.add(uri)
        return true
    }

    fun pickAll(uris: List<String>) {
        for (uri in uris.take(MAX_PICKED)) {
            if (picked.size >= MAX_PICKED) return
            if (uri.isNotEmpty()) picked.add(uri)
        }
    }

    /** Forgets picks that are no longer among [uris]; true when something was forgotten. */
    fun retainOnly(uris: Set<String>): Boolean {
        if (picked.isEmpty()) return false
        return picked.retainAll(uris)
    }

    /** The picked songs of [rows], in the order the list shows them. */
    fun pickedIn(rows: List<Track>): List<Track> {
        if (picked.isEmpty()) return emptyList()
        return rows.filter { it.uri in picked }
    }

    companion object {
        const val MAX_PICKED = 50000
    }
}
