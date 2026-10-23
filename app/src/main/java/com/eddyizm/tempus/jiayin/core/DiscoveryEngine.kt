package com.eddyizm.tempus.jiayin.core

import kotlin.math.ln
import kotlin.random.Random

/** Exploration is separate from daily recommendations: never re-add owned favourites/playlists. */
object DiscoveryEngine {
    const val ARTIST_WINDOW = 10
    fun pick(library: Library, records: Map<String, Listening>, seed: Long, recent: List<String> = emptyList(), limit: Int = 30): List<Recommendation> {
        val random = Random(seed)
        val owned = library.playlists.flatMap { it.tracks }.toSet()
        val loved = library.tracks.filter { it.starred || (records[it.id]?.completions ?: 0) >= 2 }
        val artists = loved.map { artistKey(it) }.toSet()
        val genres = loved.map { it.genre.lowercase() }.filter { it.isNotBlank() }.toSet()
        val candidates = library.tracks.distinctBy { it.id }.filter { !it.starred && it.id !in owned && records[it.id]?.disliked != true }
        // Shuffle before sorting for ties; exponential weighted sampling varies every invocation.
        val recentIds = recent.toSet()
        val scored = candidates.shuffled(random).map { t ->
            val record = records[t.id]
            val weight = (1.0 + (if (artistKey(t) in artists) 3 else 0) + (if (t.genre.isNotBlank() && t.genre.lowercase() in genres) 2 else 0)) /
                (1 + (record?.skips ?: 0).coerceAtMost(5)).toDouble()
            Triple(t, -ln(random.nextDouble().coerceAtLeast(.000001)) / weight, t.id in recentIds)
        }.sortedWith(compareBy<Triple<Track, Double, Boolean>> { it.third }.thenBy { it.second }).toMutableList()
        val selected = mutableListOf<Track>()
        while (selected.size < limit && scored.isNotEmpty()) {
            val blocked = selected.takeLast(ARTIST_WINDOW - 1).map { artistKey(it) }.toSet()
            val index = scored.indexOfFirst { artistKey(it.first) !in blocked }
            if (index < 0) break // Keep the promise even with very few artists in the library.
            selected += scored.removeAt(index).first
        }
        return selected.map { t -> Recommendation(t.id, when {
            artistKey(t) in artists -> "你喜欢的歌手，还有这些未收藏的歌"
            t.genre.isNotBlank() && t.genre.lowercase() in genres -> "根据你喜欢的${t.genre}挑选"
            (records[t.id]?.heardMs ?: 0) == 0L -> "未听过的新发现"
            else -> "未收藏的曲库再发现"
        }) }
    }
}
