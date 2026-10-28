package com.eddyizm.tempus.jiayin.core

import kotlin.math.ln
import kotlin.random.Random

/** Exploration is separate from daily recommendations: never re-add owned favourites/playlists. */
object DiscoveryEngine {
    const val ARTIST_WINDOW = 10
    data class Batch(val picks: List<Recommendation>, val state: DiscoveryState, val window: Int, val exhausted: Boolean)

    /** The tail includes the previous batch and manual inserts. Never select a queued song again. */
    fun extend(library: Library, records: Map<String, Listening>, state: DiscoveryState,
               tail: List<String>, upcoming: Set<String>, seed: Long, limit: Int = 20, ownership: Map<String, ArtistRef> = emptyMap()): Batch {
        val owned = library.playlists.flatMap { it.tracks }.toSet()
        val eligible = library.tracks.distinctBy { it.id }.filter { !it.starred && it.id !in owned && records[it.id]?.disliked != true }
        val eligibleIds = eligible.map { it.id }.toSet()
        val round = state.roundIds.filter { it in eligibleIds }.toMutableSet()
        val recent = state.recent.takeLast(150).toMutableList()
        val loved = library.tracks.filter { it.starred || (records[it.id]?.completions ?: 0) >= 2 }
        val artists = loved.flatMap { it.recommendationArtistKeys(ownership) }.toSet()
        val genres = loved.flatMap { it.genreNames() }.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        val random = Random(seed)
        val ranked = eligible.filter { it.id !in upcoming }.map { t ->
            val weight = (1.0 + (if (t.recommendationArtistKeys(ownership).any { it in artists }) 3 else 0) +
                (if (t.genreNames().any { it.lowercase(java.util.Locale.ROOT) in genres }) 2 else 0)) /
                (1 + (records[t.id]?.skips ?: 0).coerceAtMost(5)).toDouble()
            t to -ln(random.nextDouble().coerceAtLeast(.000001)) / weight
        }.sortedWith(compareBy<Pair<Track, Double>> { it.first.id in recent }.thenBy { it.second }).map { it.first }.toMutableList()
        val map = library.tracks.associateBy { it.id }
        val ordered = tail.takeLast(ARTIST_WINDOW).mapNotNull { map[it] }.toMutableList()
        val picks = mutableListOf<Recommendation>()
        var smallest = ARTIST_WINDOW
        while (picks.size < limit && ranked.isNotEmpty()) {
            if (ranked.none { it.id !in round }) round.clear()
            val pool = ranked.filter { it.id !in round }
            var window = ARTIST_WINDOW
            var candidate: Track? = null
            while (window >= 1 && candidate == null) {
                val blocked = ordered.takeLast(window - 1).flatMap { it.recommendationArtistKeys(ownership) }.toSet()
                val choices = pool.filter { t -> t.recommendationArtistKeys(ownership).none { it in blocked } }
                candidate = choices.firstOrNull { it.id != ordered.lastOrNull()?.id } ?: choices.firstOrNull()
                if (candidate == null) window--
            }
            val t = candidate ?: break
            smallest = minOf(smallest, window)
            picks += Recommendation(t.id, when {
                t.recommendationArtistKeys(ownership).any { it in artists } -> "你喜欢的歌手，还有这些未收藏的歌"
                t.genreNames().any { it.lowercase(java.util.Locale.ROOT) in genres } -> "根据你的曲风偏好挑选"
                else -> "未收藏的曲库新发现"
            })
            ranked.remove(t); round.add(t.id); recent.add(t.id); ordered.add(t)
        }
        return Batch(picks, state.copy(roundIds = round.toList(), recent = recent.takeLast(150)), smallest, eligible.isEmpty())
    }

    fun pick(library: Library, records: Map<String, Listening>, seed: Long, recent: List<String> = emptyList(), limit: Int = 30, ownership: Map<String, ArtistRef> = emptyMap()): List<Recommendation> {
        val random = Random(seed)
        val owned = library.playlists.flatMap { it.tracks }.toSet()
        val loved = library.tracks.filter { it.starred || (records[it.id]?.completions ?: 0) >= 2 }
        val artists = loved.flatMap { it.recommendationArtistKeys(ownership) }.toSet()
        val genres = loved.flatMap { it.genreNames() }.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        val candidates = library.tracks.distinctBy { it.id }.filter { !it.starred && it.id !in owned && records[it.id]?.disliked != true }
        // Shuffle before sorting for ties; exponential weighted sampling varies every invocation.
        val recentIds = recent.toSet()
        val scored = candidates.shuffled(random).map { t ->
            val record = records[t.id]
            val weight = (1.0 + (if (t.recommendationArtistKeys(ownership).any { it in artists }) 3 else 0) + (if (t.genreNames().any { it.lowercase(java.util.Locale.ROOT) in genres }) 2 else 0)) /
                (1 + (record?.skips ?: 0).coerceAtMost(5)).toDouble()
            Triple(t, -ln(random.nextDouble().coerceAtLeast(.000001)) / weight, t.id in recentIds)
        }.sortedWith(compareBy<Triple<Track, Double, Boolean>> { it.third }.thenBy { it.second }).toMutableList()
        val selected = mutableListOf<Track>()
        while (selected.size < limit && scored.isNotEmpty()) {
            val blocked = selected.takeLast(ARTIST_WINDOW - 1).flatMap { it.recommendationArtistKeys(ownership) }.toSet()
            val index = scored.indexOfFirst { it.first.recommendationArtistKeys(ownership).none { key -> key in blocked } }
            if (index < 0) break // Keep the promise even with very few artists in the library.
            selected += scored.removeAt(index).first
        }
        return selected.map { t -> Recommendation(t.id, when {
            t.recommendationArtistKeys(ownership).any { it in artists } -> "你喜欢的歌手，还有这些未收藏的歌"
            t.genreNames().any { it.lowercase(java.util.Locale.ROOT) in genres } -> "根据你喜欢的${t.genreNames().first { it.lowercase(java.util.Locale.ROOT) in genres }}挑选"
            (records[t.id]?.heardMs ?: 0) == 0L -> "未听过的新发现"
            else -> "未收藏的曲库再发现"
        }) }
    }
}
