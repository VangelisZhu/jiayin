package com.eddyizm.tempus.jiayin.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class LyricMatch(val id: Long, val title: String, val artist: String, val album: String, val duration: Int, val synced: String?, val plain: String?)

/** Conservative repair: never infer missing characters, or silently choose another recording. */
object LyricRepairPolicy {
    const val PROCESSING_VERSION = 2
    data class LineHealth(val originalBroken: Boolean, val translationBroken: Boolean)
    data class Inspection(val rows: List<LineHealth>) {
        val originalBroken get() = rows.any { it.originalBroken }
        val translationBroken get() = rows.any { it.translationBroken }
    }
    fun inspect(value: Lyrics) = Inspection(value.lines.map { LineHealth(it.originalInvalid || broken(it.original), it.translationInvalid || broken(it.translation.orEmpty())) })
    fun display(value: Lyrics, issue: String? = null): Lyrics {
        val health = inspect(value)
        return value.copy(lines = value.lines.mapIndexed { i, line ->
            line.copy(original = if (health.rows[i].originalBroken) "此行文字异常" else line.original.removePrefix("\uFEFF"),
                translation = if (health.rows[i].translationBroken) "此行译文异常" else line.translation?.removePrefix("\uFEFF"),
                originalInvalid = health.rows[i].originalBroken, translationInvalid = health.rows[i].translationBroken)
        }, processingVersion = PROCESSING_VERSION,
            issue = issue ?: value.issue ?: if (health.originalBroken) "部分原文编码异常" else if (health.translationBroken) "部分译文编码异常，原文可正常阅读" else null)
    }
    fun damaged(value: Lyrics) = inspect(value).let { it.originalBroken || it.translationBroken }
    private fun broken(text: String): Boolean = text.indices.any { i ->
        val c = text[i]
        c == '\uFFFD' || (c.code < 32 && c !in "\n\r\t") ||
            (c.isHighSurrogate() && (i == text.lastIndex || !text[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !text[i - 1].isHighSurrogate()))
    }
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private fun translatedAlias(value: String): Pair<String, String>? {
        // Some NAS tags append an English translation to a Japanese/Korean/Chinese title.
        // Keep performance/version labels significant (live, instrumental, remix, etc.).
        val alias = Regex("^(.+?)\\s*[（(]([A-Za-z][A-Za-z '-]*)[）)]$").matchEntire(value)
        val extra = alias?.groupValues?.get(2).orEmpty().lowercase(Locale.ROOT)
        val versions = listOf("live", "remix", "mix", "inst", "instrumental", "acoustic", "version", "ver", "edit", "demo", "remaster", "cover")
        val base = alias?.groupValues?.get(1).orEmpty()
        return if (base.any { it.code in 0x3040..0x30ff || it.code in 0x4e00..0x9fff || it.code in 0xac00..0xd7af } && versions.none { it in extra }) base to extra else null
    }
    private fun titles(value: String) = setOf(normalized(value)) + translatedAlias(value)?.let { setOf(normalized(it.first), normalized(it.second)) }.orEmpty()
    fun title(track: Track) = translatedAlias(track.title)?.first ?: track.title
    fun query(track: Track) = "${title(track)} ${track.artist}"
    private fun liveAlbum(value: String) = Regex("\\blive\\b|ライブ|演唱会|现场", RegexOption.IGNORE_CASE).containsMatchIn(value)
    fun choose(track: Track, matches: List<LyricMatch>): LyricMatch? {
        if (track.duration <= 0 || track.artist.isBlank() || track.artist == "未知歌手") return null
        val eligible = matches.filter { candidate ->
            candidate.duration > 0 && abs(candidate.duration - track.duration) <= 3 &&
                normalized(candidate.artist) == normalized(track.artist) &&
                (!liveAlbum(candidate.album) || liveAlbum(track.album)) &&
                titles(track.title).intersect(titles(candidate.title)).isNotEmpty() &&
                LyricsEngine.parseLrc(candidate.synced ?: candidate.plain.orEmpty()).let { lines ->
                    lines.any { it.original.isNotBlank() } && !damaged(Lyrics(track.id, lines))
                }
        }.distinctBy { it.id }
        val album = eligible.filter { track.album.isNotBlank() && normalized(it.album) == normalized(track.album) }
        val candidates = album.ifEmpty { eligible }
        return candidates.singleOrNull() // Ambiguity is left to the user's manual selection.
    }
}
