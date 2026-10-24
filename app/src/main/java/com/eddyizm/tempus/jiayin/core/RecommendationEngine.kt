package com.eddyizm.tempus.jiayin.core

import kotlin.random.Random

object RecommendationEngine {
    fun recommend(tracks: List<Track>, records: Map<String, Listening>, seed: Long, limit: Int = 30, from: Track? = null): List<Recommendation> {
        val random = Random(seed)
        val candidates = tracks.filter { records[it.id]?.disliked != true }
        val loved = tracks.filter { it.starred || (records[it.id]?.completions ?: 0) >= 2 }
        val artists = (loved.flatMap { it.artistKeys() } + from?.artistKeys().orEmpty()).toSet()
        val genres = (loved.flatMap { it.genreNames() } + from?.genreNames().orEmpty()).map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        fun artistMatch(t: Track) = t.artistKeys().any { it in artists }
        fun genreMatch(t: Track) = t.genreNames().firstOrNull { it.lowercase(java.util.Locale.ROOT) in genres }
        val taste = candidates.shuffled(random).sortedByDescending {
            (if (artistMatch(it)) 6 else 0) + (if (genreMatch(it) != null) 2 else 0) + (if (it.starred) 3 else 0) - (records[it.id]?.skips ?: 0).coerceAtMost(5)
        }
        val explore = candidates.shuffled(random).sortedBy { records[it.id]?.lastHeard ?: 0 }
        val fresh = candidates.filter { it.created > 0 }.sortedByDescending { it.created }
        val selected = linkedMapOf<String, Recommendation>()
        val counts = mutableMapOf<String, Int>()
        fun take(list: List<Track>, count: Int, relaxed: Boolean = false, reason: (Track) -> String) {
            var added = 0
            for (t in list) {
                if (selected.size >= limit || added >= count) break
                if (t.id in selected || (!relaxed && t.artistKeys().any { (counts[it] ?: 0) >= 3 })) continue
                selected[t.id] = Recommendation(t.id, reason(t)); t.artistKeys().forEach { counts[it] = (counts[it] ?: 0) + 1 }; added++
            }
        }
        val tasteReason: (Track) -> String = { when {
            from != null && it.artistKeys().any { key -> key in from.artistKeys() } -> "和这首歌有共同的演唱者"
            artistMatch(it) -> "来自你常听或收藏的歌手"
            it.starred -> "你收藏过这首歌"
            genreMatch(it) != null -> "你喜欢的${genreMatch(it)}类型"
            else -> "从你的曲库均衡挑选"
        } }
        take(taste, (limit * .5).toInt(), reason = tasteReason)
        take(explore, (limit * .3).toInt()) { if ((records[it.id]?.lastHeard ?: 0) == 0L) "还没有听过" else "收听次数较少" }
        take(fresh, limit - selected.size) { "曲库中最近加入的歌曲" }
        take(explore, limit - selected.size) { "曲库再发现" }
        take(taste, limit - selected.size, relaxed = true, reason = tasteReason)
        return selected.values.toList().shuffled(random)
    }
}
