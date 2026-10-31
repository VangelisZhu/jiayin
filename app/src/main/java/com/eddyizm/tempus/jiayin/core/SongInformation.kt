package com.eddyizm.tempus.jiayin.core

data class SongInformation(val track: Track, val owner: ArtistRef?, val source: String, val personal: Boolean = false)
object SongInformationTargets {
    fun album(track: Track, library: List<Track>): MusicList? {
        if (track.album.isBlank() && track.albumId.isBlank() && track.releaseId.isNullOrBlank()) return null
        val tracks = library.filter { albumKey(it) == albumKey(track) }.sortedWith(compareBy<Track> { it.disc }.thenBy { it.number }.thenBy { it.title })
        return tracks.takeIf { it.isNotEmpty() }?.let { MusicList("album:${albumKey(track)}", track.album.ifBlank { "未命名专辑" }, it.map { t -> t.id }, track.cover) }
    }
    fun performer(ref: ArtistRef, library: List<Track>, cover: String): MusicList? {
        if (ref.name.isBlank() || ref.name == "未知歌手") return null
        return artistCollection(ref, library, cover).takeIf { it.tracks.isNotEmpty() }
    }
}
