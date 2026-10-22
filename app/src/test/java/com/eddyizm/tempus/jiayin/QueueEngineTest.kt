package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QueueEngineTest {
    private val tracks = (0 until 7509).associate { "$it" to Track("$it", "歌曲$it", artist = "歌手${it % 282}") }
    @Test fun fullLibraryHasEveryOccurrenceExactlyOnceAndRoundTrips() {
        val q = QueueEngine.create(tracks.keys.toList(), "全库", true, tracks, random = Random(24))
        assertEquals(7509, q.entries.size)
        assertEquals(tracks.keys, q.entries.map { it.trackId }.toSet())
        assertEquals(7509, q.entries.map { it.key }.distinct().size)
        val saved = q.copy(index = 4380, positionMs = 63452)
        assertEquals(saved, Gson().fromJson(Gson().toJson(saved), QueueState::class.java))
        assertTrue(q.entries.zipWithNext().none { tracks[it.first.trackId]!!.artist == tracks[it.second.trackId]!!.artist })
    }
    @Test fun togglePreservesCurrentHistoryManualSlotsAndPlaylistSourceOrder() {
        val q = QueueEngine.create((0 until 1000).map { "$it" }, "千首歌单", false, tracks).copy(index = 7, positionMs = 48_230, history = listOf("heard"))
        val manual = QueueEngine.insert(q, listOf("4000", "4001"), true)
        val shuffled = QueueEngine.toggle(manual, tracks, random = Random(42))
        assertEquals(manual.entries.take(10), shuffled.entries.take(10))
        assertEquals(q.positionMs, shuffled.positionMs)
        assertEquals(q.history, shuffled.history)
        assertEquals(manual.entries.map { it.key }.toSet(), shuffled.entries.map { it.key }.toSet())
        val restored = QueueEngine.toggle(shuffled, tracks)
        assertEquals(manual.entries, restored.entries)
        assertEquals((0 until 1000).map { "$it" }, q.entries.map { it.trackId })
    }
    @Test fun duplicatesAreQueueOccurrencesAndRemoveOrMoveKeepsCurrentIdentity() {
        val q = QueueEngine.create(listOf("1", "1", "2", "1"), "重复歌单", true, tracks, random = Random(1)).copy(index = 2)
        assertEquals(4, q.entries.map { it.key }.distinct().size)
        assertEquals(q.current?.key, QueueEngine.move(q, 0, 3).current?.key)
        assertEquals(q.current?.key, QueueEngine.remove(q, 0).current?.key)
        assertEquals(QueueState(), QueueEngine.remove(QueueState(), 0))
    }
    @Test fun recentSongsAreDeferredWithoutDroppingThem() {
        val recent = (0 until 10).map { "$it" }.toSet()
        val q = QueueEngine.create((0 until 50).map { "$it" }, "随机", true, tracks, recent, Random(7))
        assertTrue(q.entries.take(40).none { it.trackId in recent })
        assertEquals(50, q.entries.size)
    }
}
