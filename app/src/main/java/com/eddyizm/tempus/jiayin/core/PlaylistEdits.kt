package com.eddyizm.tempus.jiayin.core

/** A position is meaningful only in the exact ordered snapshot the user confirmed. */
data class PlaylistEntryRef(val playlistId: String, val index: Int, val songId: String, val version: String)
fun MusicList.entryRef(index: Int) = PlaylistEntryRef(id, index, tracks[index], playlistVersion(tracks))
fun playlistVersion(ids: List<String>) = digest(ids.joinToString("") { "${it.length}:$it" })

enum class PlaylistEditResult { SAVED, ALREADY_PRESENT, CHANGED, READ_ONLY, MISSING }

object PlaylistEdits {
    fun remove(ids: List<String>, ref: PlaylistEntryRef): List<String>? =
        if (playlistVersion(ids) != ref.version || ids.getOrNull(ref.index) != ref.songId) null
        else ids.filterIndexed { index, _ -> index != ref.index }

    fun appendMissing(ids: List<String>, additions: Iterable<String>): List<String> {
        val seen = ids.toMutableSet()
        return ids + additions.filter { seen.add(it) }
    }

    /** A lost reply is acknowledged only when the resulting ordered list can be verified. */
    fun removalState(remote: List<String>, before: List<String>, after: List<String>): String = when (remote) {
        after -> "done"
        before -> "apply"
        else -> "conflict"
    }
}
