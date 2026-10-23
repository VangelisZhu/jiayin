package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryEngineTest {
    private val tracks = (0 until 600).map { Track("$it", "Song $it", "Artist ${it % 30}", artistId = "a${it % 30}", genre = if (it % 2 == 0) "Pop" else "Jazz", starred = it < 30) }
    @Test fun excludesFavouritesEveryPlaylistAndDislikes() {
        val library = Library(tracks, listOf(MusicList("one", "One", (30..45).map { "$it" }), MusicList("pending", "Pending", listOf("46"), pending = true)))
        val result = DiscoveryEngine.pick(library, mapOf("47" to Listening("47", disliked = true)), 1)
        assertEquals(30, result.size)
        assertTrue(result.none { it.id.toInt() < 48 })
        assertEquals(30, result.map { it.id }.distinct().size)
    }
    @Test fun everySlidingWindowHasDistinctArtists() {
        repeat(50) { seed ->
            val result = DiscoveryEngine.pick(Library(tracks), emptyMap(), seed.toLong())
            val map = tracks.associateBy { it.id }
            result.windowed(10).forEach { window -> assertEquals(10, window.map { artistKey(map.getValue(it.id)) }.distinct().size) }
        }
    }
    @Test fun newBatchAvoidsRecentSongsWhenCandidatesExist() {
        val first = DiscoveryEngine.pick(Library(tracks), emptyMap(), 1)
        val second = DiscoveryEngine.pick(Library(tracks), emptyMap(), 2, first.map { it.id })
        assertTrue(first.map { it.id }.toSet().intersect(second.map { it.id }.toSet()).isEmpty())
    }
    @Test fun fewArtistsYieldShortQueueWithoutBreakingSpacingPromise() {
        val few = tracks.take(2).map { it.copy(starred = false, artistId = "same") }
        assertEquals(1, DiscoveryEngine.pick(Library(few), emptyMap(), 1).size)
        assertTrue(DiscoveryEngine.pick(Library(tracks.map { it.copy(starred = true) }), emptyMap(), 1).isEmpty())
    }
    @Test fun preferencesIncreaseRelevantExplorationWithoutLockingToTwoArtists() {
        val library = Library(tracks.map { it.copy(starred = it.id == "0" || it.id == "1") })
        val picks = (0..99).flatMap { DiscoveryEngine.pick(library, emptyMap(), it.toLong()) }
        val byId = tracks.associateBy { it.id }
        val likedArtistCount = picks.count { byId.getValue(it.id).artistId in setOf("a0", "a1") }
        assertTrue(likedArtistCount > picks.size * 2 / 30)
        assertTrue(picks.map { byId.getValue(it.id).artistId }.distinct().size >= 20)
    }
    @Test fun noIdArtistsUseNormalizedIdentityAndNewFieldsRemainOptional() {
        assertEquals(artistKey(Track("x", "X", " Artist ")), artistKey(Track("y", "Y", "artist")))
        assertEquals(0L, Track("x", "X").releaseDate)
    }
}
