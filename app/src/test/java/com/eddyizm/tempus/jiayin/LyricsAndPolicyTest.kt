package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class LyricsAndPolicyTest {
    @Test fun existingTranslationsAlignByTimeNotIndexAndPreserveUnmatchedText() {
        val main = LyricTrack("eng", lines = listOf(LyricLine(1000, "First"), LyricLine(5000, "Second"), LyricLine(9000, "Third")))
        val translation = LyricTrack("zho", "translation", lines = listOf(LyricLine(1100, "第一句"), LyricLine(2500, "第一句的后半"), LyricLine(9100, "第三句"), LyricLine(null, "无时间的译文")))
        val paired = LyricsEngine.pair("song", listOf(main, translation))!!
        assertEquals("第一句\n第一句的后半", paired.lines[0].translation)
        assertNull(paired.lines[1].translation)
        assertEquals("第三句", paired.lines[2].translation)
        assertEquals("无时间的译文", paired.lines.last().translation)
    }
    @Test fun multilingualLrcKeepsRepeatedTagsFractionalTimesAndOffsets() {
        val result = LyricsEngine.parseLrc("[offset:-100]\n[00:01.5][00:20.050]日本語の歌詞\n[00:08.25]한국어 가사\n[00:12.001]English / 中文")
        assertEquals(listOf(1400L, 8150L, 11901L, 19950L), result.map { it.timeMs })
        assertEquals(result.first().original, result.last().original)
        val first = Lyrics("s", result)
        assertNotEquals(LyricsEngine.originalHash(first), LyricsEngine.originalHash(first.copy(lines = result + LyricLine(original = "new"))))
    }
    @Test fun automaticResumeNeverOverridesUserSleepHeadsetNetworkOrRestore() {
        for (cause in PauseCause.entries) {
            assertFalse(ResumePolicy(false).mayResume(cause))
            assertEquals(cause == PauseCause.FOCUS, ResumePolicy(true).mayResume(cause))
        }
    }
}
