package com.eddyizm.tempus.jiayin.core

object LyricsEngine {
    fun pair(songId: String, tracks: List<LyricTrack>): Lyrics? {
        val main = tracks.firstOrNull { it.kind != "translation" && !it.language.startsWith("zh") }
            ?: tracks.firstOrNull { it.kind != "translation" } ?: tracks.firstOrNull() ?: return null
        val translated = tracks.firstOrNull { it !== main && (it.kind == "translation" || it.language.startsWith("zh")) }
        val original = main.lines.map { it.copy(timeMs = it.timeMs?.plus(main.offsetMs)) }
        val translations = translated?.lines.orEmpty().map { it.copy(timeMs = it.timeMs?.plus(translated?.offsetMs ?: 0)) }
        val used = mutableSetOf<Int>()
        val paired = original.mapIndexed { i, line ->
            // A translation can have multiple cues per original line. Associate to the
            // original's time interval, allowing 350 ms source rounding, never index-zip.
            val next = original.drop(i + 1).firstOrNull { it.timeMs != null }?.timeMs
            val matches = if (line.timeMs == null) emptyList() else translations.withIndex().filter { (j, cue) ->
                j !in used && cue.timeMs != null && cue.timeMs >= line.timeMs - 350 && (next == null || cue.timeMs < next - 350)
            }
            used.addAll(matches.map { it.index })
            line.copy(translation = matches.joinToString("\n") { it.value.original }.ifBlank { null })
        }
        // Unsynchronised translation cannot safely be aligned; preserve it in its own
        // paragraph instead of silently attaching the wrong Chinese sentence.
        val unmatched = translations.withIndex().filter { it.index !in used }.map { it.value }
        return Lyrics(songId, paired + unmatched.map { LyricLine(original = "", translation = it.original) }, main.language)
    }
    fun parseLrc(value: String): List<LyricLine> {
        val time = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val offset = Regex("\\[offset:([+-]?\\d+)]").find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val timed = value.lines().flatMap { row ->
            val text = row.replace(time, "").trim()
            time.findAll(row).map { m ->
                val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                LyricLine((m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + fraction + offset).coerceAtLeast(0), text)
            }.toList()
        }
        return if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else value.lines().filter { it.isNotBlank() && !it.startsWith("[") }.map { LyricLine(original = it) }
    }
    fun originalHash(lyrics: Lyrics) = digest(lyrics.lines.joinToString("\n") { "${it.timeMs}:${it.original}" })
}
