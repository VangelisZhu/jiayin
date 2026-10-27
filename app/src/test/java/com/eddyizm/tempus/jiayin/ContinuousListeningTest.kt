package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ContinuousListeningTest {
    private val songs = (0 until 500).map { Track("$it", "Song $it", artistId = "artist-${it % 25}", artist = "Artist ${it % 25}") }
    @Test fun tenBatchesRespectCrossBatchArtistsOwnershipAndRound() {
        val library = Library(songs.map { it.copy(starred = it.id == "0") }, listOf(MusicList("p", "Saved", listOf("1"))))
        val records = mapOf("2" to Listening("2", disliked = true))
        var state = DiscoveryState()
        val ordered = mutableListOf<String>()
        repeat(12) { batch ->
            val result = DiscoveryEngine.extend(library, records, state, ordered.takeLast(10), emptySet(), batch.toLong())
            assertEquals(20, result.picks.size)
            assertEquals(10, result.window)
            ordered.addAll(result.picks.map { it.id }); state = result.state
        }
        assertEquals(240, ordered.toSet().size)
        assertTrue(ordered.none { it in setOf("0", "1", "2") })
        val byId = songs.associateBy { it.id }
        ordered.windowed(10).forEach { window -> assertEquals(10, window.map { byId.getValue(it).artistId }.toSet().size) }
    }
    @Test fun smallCatalogRelaxesThenStartsNewRoundAndNeverDuplicatesUpcoming() {
        val library = Library(songs.take(3).map { it.copy(artistId = "one", artist = "One") })
        val first = DiscoveryEngine.extend(library, emptyMap(), DiscoveryState(), emptyList(), emptySet(), 1)
        assertEquals(3, first.picks.size); assertEquals(1, first.window)
        val ids = first.picks.map { it.id }
        val next = DiscoveryEngine.extend(library, emptyMap(), first.state, ids, ids.takeLast(2).toSet(), 2)
        assertEquals(listOf(ids.first()), next.picks.map { it.id })
        val restarted = DiscoveryEngine.extend(library, emptyMap(), first.state, ids, emptySet(), 3)
        assertNotEquals(ids.last(), restarted.picks.first().id)
        assertFalse(restarted.exhausted)
    }
    @Test fun unavailableAndDislikedCandidatesDoNotLeakThroughRelaxation() {
        val lib = Library(songs.take(3).map { it.copy(starred = true) })
        assertTrue(DiscoveryEngine.extend(lib, emptyMap(), DiscoveryState(), emptyList(), emptySet(), 1).exhausted)
    }
    @Test fun restoredOldQueueDoesNotEnableDiscovery() {
        assertNull(Gson().fromJson("{\"entries\":[],\"index\":0}", QueueState::class.java).discovery)
        val original = QueueState(discovery = DiscoveryState(roundIds = listOf("a"), recent = listOf("a")))
        assertEquals(original, Gson().fromJson(Gson().toJson(original), QueueState::class.java))
    }
    @Test fun syncUsesSuccessAgeManualAndFailureCooldown() {
        val day = 86_400_000L; val start = day * 100
        assertTrue(LibrarySyncPolicy().due(start))
        assertFalse(LibrarySyncPolicy(30, start).due(start + 29 * day))
        assertTrue(LibrarySyncPolicy(30, start).due(start + 30 * day))
        assertFalse(LibrarySyncPolicy(0, start).due(start + 365 * day))
        assertTrue(LibrarySyncPolicy(0).due(start))
        assertFalse(LibrarySyncPolicy(14, start, start + 15 * day).due(start + 15 * day + 1000))
        assertTrue(LibrarySyncPolicy(14, start, start + 15 * day).due(start + 15 * day + 6 * 3_600_000))
    }
    @Test fun KoreanJapaneseAndMixedLyricsRemainReadableWithPartialFailures() {
        val text = "안녕하세요 한글 カタカナ かな 中文 hello 🎵"
        val value = Lyrics("x", listOf(LyricLine(0, text, "译文"), LyricLine(1000, "정상", "坏\uFFFD"), LyricLine(2000, "坏\uFFFD", "保留译文")))
        val result = LyricRepairPolicy.display(value)
        assertEquals(text, result.lines[0].original)
        assertEquals("정상", result.lines[1].original)
        assertEquals("此行译文异常", result.lines[1].translation)
        assertEquals(2000L, result.lines[2].timeMs)
        assertEquals("保留译文", result.lines[2].translation)
        assertTrue(result.lines[2].originalInvalid)
        assertEquals(result, LyricRepairPolicy.display(result))
    }
    @Test fun jsonUnicodeDecodingHonoursBomAndDoesNotGuessDamagedText() {
        val text = "{\"lyric\":\"한글 日本語 🎵\"}"
        assertEquals(text, JsonText.decode(text.toByteArray()))
        assertEquals(text, JsonText.decode(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray()))
        assertEquals(text, JsonText.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertThrows(IllegalStateException::class.java) { JsonText.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertEquals("\uFFFD", JsonText.decode("\uFFFD".toByteArray())) // Already lost upstream: not recoverable by guessing.
    }
}
