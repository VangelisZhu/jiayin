package com.eddyizm.tempus.jiayin.core

import androidx.annotation.Keep
import java.security.MessageDigest

@Keep data class Track(
    val id: String, val title: String, val artist: String = "未知歌手",
    val album: String = "", val albumId: String = "", val artistId: String = "",
    val cover: String = "", val duration: Int = 0, val genre: String = "",
    val created: Long = 0, val starred: Boolean = false, val path: String = "",
    val number: Int = 0, val disc: Int = 0, val releaseDate: Long = 0,
    // Nullable additions keep Gson's pre-preview.10 persisted rows compatible (Unsafe bypasses defaults).
    val artists: List<ArtistRef>? = null, val albumArtists: List<ArtistRef>? = null,
    val genres: List<String>? = null, val recordingId: String? = null, val releaseId: String? = null,
    val albumVersion: String? = null, val language: String? = null, val sharedRegion: String? = null,
    val compilation: Boolean = false,
)
@Keep data class ArtistRef(val id: String = "", val name: String = "") {
    val key: String get() = id.ifBlank { "name:${name.trim().lowercase(java.util.Locale.ROOT)}" }
}
fun Track.performers(): List<ArtistRef> = artists.orEmpty().filter { it.name.isNotBlank() }.distinctBy { it.key }
    .ifEmpty { listOf(ArtistRef(artistId, artist)) }
fun Track.artistKeys() = performers().map { it.key }.toSet()
fun Track.genreNames() = genres.orEmpty().filter { it.isNotBlank() }.ifEmpty { listOfNotNull(genre.takeIf { it.isNotBlank() }) }.distinct()
fun Track.albumCredits() = albumArtists.orEmpty().filter { it.name.isNotBlank() }.ifEmpty {
    if (compilation) listOf(ArtistRef(name = "Various Artists")) else listOf(performers().first())
}
fun albumKey(t: Track): String = t.albumId.ifBlank { t.releaseId?.takeIf { it.isNotBlank() } ?: "fallback:" + digest(
    listOf(t.albumCredits().map { it.key }.sorted().joinToString("\n"), t.album, t.releaseDate.toString(), t.albumVersion.orEmpty()).joinToString("\u0000")) }
/** Only an explicit recording ID and compatible performance labels may fold alternate releases. */
fun recordingGroups(tracks: List<Track>): List<List<Track>> = tracks.groupBy {
    it.recordingId?.takeIf(String::isNotBlank)?.let { id -> "$id:${Regex("live|instrumental|remix|acoustic|伴奏|现场|演唱会|ライブ", RegexOption.IGNORE_CASE).findAll(it.title).map { m -> m.value.lowercase() }.toList().sorted()}" } ?: "file:${it.id}"
}.values.flatMap { candidates ->
    val groups = mutableListOf<MutableList<Track>>()
    candidates.sortedBy { it.duration }.forEach { track ->
        val group = groups.lastOrNull()
        if (group != null && track.duration > 0 && group.first().duration > 0 && track.duration - group.first().duration <= 3) group.add(track)
        else groups.add(mutableListOf(track))
    }
    groups
}
@Keep data class ArtistProfile(val id: String, val name: String, val starred: Boolean = false, val region: String = "未分类", val type: String = "未分类", val sortName: String? = null)
fun artistKey(t: Track) = t.performers().first().key
fun artistCollection(t: Track, tracks: List<Track>) = artistCollection(t.performers().first(), tracks, t.cover)
fun artistCollection(artist: ArtistRef, tracks: List<Track>, cover: String = "") = MusicList("artist:${artist.key}", artist.name, tracks.filter { artist.key in it.artistKeys() }.map { it.id }, cover)
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
