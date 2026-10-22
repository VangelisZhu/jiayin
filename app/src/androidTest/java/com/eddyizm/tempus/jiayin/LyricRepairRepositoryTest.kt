package com.eddyizm.tempus.jiayin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Generated text only. Exercises the real Room cache without contacting NAS or lyric services. */
@RunWith(AndroidJUnit4::class)
class LyricRepairRepositoryTest {
    private val repo = JiayinRepository(InstrumentationRegistry.getInstrumentation().targetContext)
    private var oldAccount: String? = null
    private var oldAuto: Boolean? = null
    private val track = Track("test-song", "海辺の手紙", "テスト楽団", "夕暮れ", duration = 200)
    private val broken = Lyrics(track.id, listOf(LyricLine(1000, "Let me show \uFFFD\uFFFD")), "jpn")
    private val valid = Lyrics(track.id, listOf(LyricLine(1000, "海へ続く道", "通往海边的路")), "jpn")
    private val match = LyricMatch(7, track.title, track.artist, track.album, 200, "[00:01.00]海へ続く道", null)
    @Before fun isolateAccount() { oldAccount = repo.settings.getString("account", null); oldAuto = if (repo.settings.contains("auto-repair-lyrics")) repo.settings.getBoolean("auto-repair-lyrics", true) else null; repo.settings.edit().putString("account", "lyric-test-${UUID.randomUUID()}").putBoolean("auto-repair-lyrics", true).commit() }
    @After fun restoreAccount() { repo.settings.edit().putString("account", oldAccount).apply { oldAuto?.let { putBoolean("auto-repair-lyrics", it) } ?: remove("auto-repair-lyrics") }.commit() }
    @Test fun disablingAutomaticRepairNeverSendsCorruptSongMetadata() = runBlocking {
        repo.settings.edit().putBoolean("auto-repair-lyrics", false).commit()
        var requests = 0
        val repository = LyricsRepository(repo, LyricSearch { requests++; error("unexpected") }, { broken })
        val result = repository.load(track, true)!!
        assertTrue(result.lines.isEmpty()); assertTrue(result.source.contains("手动")); assertEquals(0, requests)
        assertEquals("LRCLIB · 手动匹配", repository.select(track, match).source)
    }
    @Test fun validNasLyricsNeverRequestExternalService() = runBlocking {
        var calls = 0
        val lyrics = LyricsRepository(repo, LyricSearch { calls++; error("unexpected") }, { valid }).load(track, true)
        assertEquals(valid, lyrics); assertEquals(0, calls)
    }
    @Test fun corruptionTriggersMatchingAndRepairSurvivesRefreshAndRepositoryRestart() = runBlocking {
        var calls = 0
        val service = LyricSearch { calls++; listOf(match) }
        val repaired = LyricsRepository(repo, service, { broken }).load(track, true)!!
        assertEquals("LRCLIB · 乱码自动修复", repaired.source)
        assertFalse(LyricRepairPolicy.damaged(repaired))
        val restarted = LyricsRepository(repo, service, { error("must preserve override") })
        assertEquals(repaired, restarted.load(track, true)); assertEquals(1, calls)
        assertNull(repo.dao.get("other-account", "lyrics-override", track.id))
    }
    @Test fun failedRepairHidesCorruptionThrottlesAndAllowsExplicitRetry() = runBlocking {
        var calls = 0
        val repository = LyricsRepository(repo, LyricSearch { calls++; error("HTTP 503") }, { broken })
        val result = repository.load(track)!!
        assertTrue(result.lines.isEmpty()); assertTrue(result.source.contains("暂不可用"))
        assertEquals(result, repository.load(track)); assertEquals(1, calls)
        repository.load(track, true); assertEquals(2, calls)
        assertEquals(broken, repo.get("lyrics", track.id, Lyrics::class.java))
    }
    @Test fun manualMatchOffsetAndTranslationPersistAcrossRefresh() = runBlocking {
        val repository = LyricsRepository(repo, LyricSearch { error("unexpected") }, { broken })
        val selected = repository.select(track, match)
        val edited = selected.copy(offsetMs = 500, lines = selected.lines.map { it.copy(translation = "通往海边的路") })
        repository.save(edited)
        assertEquals(edited, repository.load(track, true))
    }
    @Test fun existingPreviewThreeManualCacheIsMigratedWithoutOverwriting() = runBlocking {
        val existing = valid.copy(source = "LRCLIB · 手动匹配")
        repo.put("lyrics", track.id, existing)
        val repository = LyricsRepository(repo, LyricSearch { error("unexpected") }, { broken })
        assertEquals(existing, repository.load(track, true))
        assertEquals(existing, repo.get("lyrics-override", track.id, Lyrics::class.java))
    }
}
