package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class RecommendationEngineTest {
    private val tracks = (0 until 500).map { Track("$it", "歌$it", "歌手${it % 40}", genre = "类型${it % 4}", created = it * 1000L, starred = it % 31 == 0) }
    @Test fun noDislikesNoDuplicateIdsDiverseArtistsAndDeterministicDailySeed() {
        val records = (0 until 50).associate { "$it" to Listening("$it", disliked = true) }
        val a = RecommendationEngine.recommend(tracks, records, 20261022)
        assertEquals(30, a.size)
        assertEquals(a, RecommendationEngine.recommend(tracks, records, 20261022))
        assertEquals(30, a.map { it.id }.distinct().size)
        assertTrue(a.none { records[it.id]?.disliked == true })
        assertTrue(a.groupBy { r -> tracks.first { it.id == r.id }.artist }.all { it.value.size <= 3 })
        assertTrue(a.all { it.reason.isNotBlank() })
    }
    @Test fun smallLibraryFillsAndRelaxesArtistLimitWithoutInventingSongs() {
        val small = (0 until 8).map { Track("$it", "$it", "同一歌手") }
        assertEquals(small.map { it.id }.toSet(), RecommendationEngine.recommend(small, emptyMap(), 1).map { it.id }.toSet())
        assertTrue(RecommendationEngine.recommend(emptyList(), emptyMap(), 1).isEmpty())
    }
}
