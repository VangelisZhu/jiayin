package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class LyricRepairPolicyTest {
    private val track = Track("song", "海辺の手紙 (Letters)", "テスト楽団", "夕暮れ", duration = 200)
    private val match = LyricMatch(1, "海辺の手紙", "テスト楽団", "夕暮れ", 201, "[00:01.00]海へ続く道\n[00:03.00]風が歌う", null)
    @Test fun validJapaneseKoreanChineseAccentsAndEmojiAreNotCorrupt() {
        assertFalse(LyricRepairPolicy.damaged(Lyrics("s", listOf(LyricLine(original = "日本語 한국어 简繁體中文 été 😀", translation = "译文")))))
    }
    @Test fun lostCharactersControlsAndUnpairedSurrogatesAreCorrupt() {
        listOf("Let me show\uFFFD", "\u0000日本語", "\uD83D", "\uDE00").forEach {
            assertTrue(LyricRepairPolicy.damaged(Lyrics("s", listOf(LyricLine(original = it)))))
        }
        assertTrue(LyricRepairPolicy.damaged(Lyrics("s", listOf(LyricLine(original = "valid", translation = "\uFFFD")))))
    }
    @Test fun onlyUniqueTitleArtistAndDurationMatchIsAutomatic() {
        assertEquals("海辺の手紙 テスト楽団", LyricRepairPolicy.query(track))
        assertEquals("海辺の手紙 (Live) テスト楽団", LyricRepairPolicy.query(track.copy(title = "海辺の手紙 (Live)")))
        assertEquals(match, LyricRepairPolicy.choose(track, listOf(match)))
        assertNull(LyricRepairPolicy.choose(track, listOf(match.copy(artist = "別の歌手"))))
        assertNull(LyricRepairPolicy.choose(track, listOf(match.copy(duration = 204))))
        assertNull(LyricRepairPolicy.choose(track.copy(duration = 0), listOf(match)))
        assertNull(LyricRepairPolicy.choose(track, listOf(match.copy(title = "another song"))))
    }
    @Test fun performanceVariantsEmptyCorruptAndAmbiguousResultsAreRejected() {
        assertNull(LyricRepairPolicy.choose(track, listOf(match.copy(title = "海辺の手紙 (Live)"))))
        assertNull(LyricRepairPolicy.choose(track, listOf(match.copy(album = "Live in Tokyo"))))
        assertNull(LyricRepairPolicy.choose(track.copy(title = "海辺の手紙 (Inst.)"), listOf(match)))
        assertNull(LyricRepairPolicy.choose(track, listOf(match.copy(synced = null))))
        assertNull(LyricRepairPolicy.choose(track, listOf(match.copy(synced = "[00:01.00]\uFFFD"))))
        assertNull(LyricRepairPolicy.choose(track, listOf(match, match.copy(id = 2))))
        assertEquals(match, LyricRepairPolicy.choose(track, listOf(match, match.copy(id = 2, album = "別の盤"))))
    }
}
