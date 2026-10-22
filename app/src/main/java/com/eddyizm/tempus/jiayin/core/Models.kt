package com.eddyizm.tempus.jiayin.core

import androidx.annotation.Keep
import java.security.MessageDigest

@Keep data class Track(
    val id: String, val title: String, val artist: String = "未知歌手",
    val album: String = "", val albumId: String = "", val artistId: String = "",
    val cover: String = "", val duration: Int = 0, val genre: String = "",
    val created: Long = 0, val starred: Boolean = false, val path: String = "",
    val number: Int = 0, val disc: Int = 0,
)
@Keep data class MusicList(val id: String, val name: String, val tracks: List<String> = emptyList(), val cover: String = "", val pending: Boolean = false)
@Keep data class Library(val tracks: List<Track> = emptyList(), val playlists: List<MusicList> = emptyList(), val syncedAt: Long = 0)
@Keep data class Listening(val id: String, val heardMs: Long = 0, val completions: Int = 0, val skips: Int = 0, val lastHeard: Long = 0, val disliked: Boolean = false)
@Keep data class Recommendation(val id: String, val reason: String)
@Keep data class QueueEntry(val key: String, val trackId: String, val sourceOrder: Int, val manual: Boolean = false)
@Keep data class QueueState(
    val entries: List<QueueEntry> = emptyList(), val index: Int = 0, val positionMs: Long = 0,
    val shuffled: Boolean = false, val repeat: Int = 0, val source: String = "播放队列", val history: List<String> = emptyList(),
) { val current: QueueEntry? get() = entries.getOrNull(index) }

enum class PauseCause { NONE, USER, FOCUS, HEADSET, SLEEP, NETWORK, RESTORED }
@Keep data class ResumePolicy(val automatic: Boolean = false) {
    fun mayResume(cause: PauseCause) = automatic && cause == PauseCause.FOCUS
}
@Keep data class LyricLine(val timeMs: Long? = null, val original: String, val translation: String? = null)
@Keep data class Lyrics(val songId: String, val lines: List<LyricLine>, val language: String = "", val machine: Boolean = false, val source: String = "NAS", val offsetMs: Long = 0)
@Keep data class LyricTrack(val language: String = "", val kind: String = "", val offsetMs: Long = 0, val lines: List<LyricLine> = emptyList())
@Keep data class PendingOperation(val id: String, val method: String, val params: Map<String, List<String>>, val playlistId: String? = null, val localPlaylistId: String? = null)

fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
