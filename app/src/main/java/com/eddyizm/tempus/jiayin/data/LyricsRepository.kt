package com.eddyizm.tempus.jiayin.data

import com.eddyizm.tempus.jiayin.core.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LyricsRepository(private val repo: JiayinRepository, private val online: LyricSearch = LrclibSearch(repo.http), private val nas: (suspend (Track) -> Lyrics?)? = null) {
    suspend fun load(track: Track, refresh: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        repo.get("lyrics-override", track.id, Lyrics::class.java)?.takeIf { !LyricRepairPolicy.damaged(it) }?.let { return@withContext it }
        val cached = repo.get("lyrics", track.id, Lyrics::class.java)
        if (repo.offline) return@withContext cached?.let { LyricRepairPolicy.display(it) }
        if (cached != null && cached.source.startsWith("LRCLIB") && !LyricRepairPolicy.damaged(cached)) {
            repo.put("lyrics-override", track.id, cached)
            return@withContext cached
        }
        val cachedAt = repo.dao.get(repo.account, "lyrics", track.id)?.updated ?: 0
        val checkedVersion = repo.get("lyrics-processing", track.id, Int::class.java) ?: 0
        if (cached != null && !refresh && (checkedVersion == LyricRepairPolicy.PROCESSING_VERSION || !LyricRepairPolicy.damaged(cached)) && System.currentTimeMillis() - cachedAt < 86_400_000L) return@withContext repair(track, cached, false)
        try {
            val original = (nas?.invoke(track) ?: if (nas == null) fetchNas(track) else null) ?.takeIf { it.lines.isNotEmpty() } ?: return@withContext cached?.let { repair(track, it, refresh) }
            repo.get("lyrics-override", track.id, Lyrics::class.java)?.takeIf { !LyricRepairPolicy.damaged(it) }?.let { return@withContext it }
            repo.put("lyrics", track.id, original.copy(offsetMs = cached?.offsetMs ?: 0))
            repo.put("lyrics-processing", track.id, LyricRepairPolicy.PROCESSING_VERSION)
            val hash = LyricsEngine.originalHash(original)
            val translated = repo.get("translation", "$hash:ZH:manual", Lyrics::class.java)
                ?: repo.get("translation", "$hash:ZH:deepl", Lyrics::class.java)
            val value = if (translated != null) translated.copy(songId = track.id, offsetMs = cached?.offsetMs ?: 0) else original.copy(offsetMs = cached?.offsetMs ?: 0)
            repo.put("lyrics", track.id, value); if (LyricRepairPolicy.damaged(value)) repair(track, value, refresh) else value
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { cached?.let { repair(track, it, refresh) } ?: throw e }
    }
    private suspend fun fetchNas(track: Track): Lyrics? {
        val response = repo.request("getLyricsBySongId", JiayinRepository.one("id", track.id))
        val tracks = response.getAsJsonObject("lyricsList")?.objects("structuredLyrics").orEmpty().map { raw ->
            val lines = raw.objects("line").map { LyricLine(if (it.has("start")) it.num("start").toLong() else null, it.str("value")) }
            LyricTrack(raw.str("lang"), raw.str("kind"), raw.num("offset").toLong(), lines)
        }
        return LyricsEngine.pair(track.id, tracks)
    }
    private suspend fun repair(track: Track, value: Lyrics, force: Boolean): Lyrics {
        if (!LyricRepairPolicy.damaged(value)) return value
        if (!LyricRepairPolicy.inspect(value).originalBroken) return LyricRepairPolicy.display(value)
        if (!repo.settings.getBoolean("auto-repair-lyrics", false))
            return LyricRepairPolicy.display(value, "原文编码异常，可手动联网匹配")
        val key = "${LyricRepairPolicy.PROCESSING_VERSION}:${track.id}:${LyricsEngine.originalHash(value)}"
        val previous = repo.dao.get(repo.account, "lyrics-repair-attempt", key)
        // A service outage must not cause a request on every screen entry or song replay.
        if (!force && previous != null && System.currentTimeMillis() - previous.updated < 900_000L)
            return LyricRepairPolicy.display(value, repo.get("lyrics-repair-attempt", key, String::class.java) ?: "原文编码异常，请重试匹配")
        var status = "NAS 歌词编码异常，未找到唯一匹配版本"
        try {
            val match = LyricRepairPolicy.choose(track, online.candidates(track))
            repo.get("lyrics-override", track.id, Lyrics::class.java)?.takeIf { !LyricRepairPolicy.damaged(it) }?.let { return it }
            if (match != null) {
                val repaired = storeMatch(track, match, "LRCLIB · 乱码自动修复", value.offsetMs)
                // Only carry a known translation over at the same cue time; never pair by row index.
                val health = LyricRepairPolicy.inspect(value)
                val old = value.lines.filterIndexed { i, line -> line.timeMs != null && !health.rows[i].translationBroken && !line.translation.isNullOrBlank() }.associateBy { it.timeMs }
                val combined = repaired.copy(lines = repaired.lines.map { line -> line.copy(translation = old[line.timeMs]?.translation) })
                repo.put("lyrics-override", track.id, combined); repo.put("lyrics", track.id, combined)
                return combined
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "NAS 歌词编码异常，联网修复暂不可用" }
        repo.put("lyrics-repair-attempt", key, status)
        return LyricRepairPolicy.display(value, status)
    }
    suspend fun search(track: Track, query: String): List<LyricMatch> = withContext(Dispatchers.IO) {
        check(!repo.offline && repo.currentSession()) { "请先连接服务器" }
        online.search(query).sortedBy { kotlin.math.abs(it.duration - track.duration) }
    }
    suspend fun select(track: Track, match: LyricMatch): Lyrics = withContext(Dispatchers.IO) {
        storeMatch(track, match, "LRCLIB · 手动匹配")
    }
    private fun storeMatch(track: Track, match: LyricMatch, source: String, offset: Long = 0): Lyrics {
        val value = Lyrics(track.id, LyricsEngine.parseLrc(match.synced ?: match.plain.orEmpty()), source = source, offsetMs = offset)
        require(value.lines.isNotEmpty() && !LyricRepairPolicy.damaged(value)) { "这个版本的歌词为空或也有乱码，请选择其他版本" }
        repo.put("lyrics-override", track.id, value)
        repo.put("lyrics", track.id, value)
        return value
    }
    suspend fun save(value: Lyrics) = withContext(Dispatchers.IO) {
        val raw = repo.get("lyrics", value.songId, Lyrics::class.java)
        val preserved = value.copy(lines = value.lines.mapIndexed { i, line ->
            val old = raw?.lines?.getOrNull(i)?.takeIf { it.timeMs == line.timeMs }
            line.copy(original = if (line.originalInvalid) old?.original ?: line.original else line.original,
                translation = if (line.translationInvalid) old?.translation ?: line.translation else line.translation)
        }, issue = if (LyricRepairPolicy.damaged(value)) value.issue else null)
        repo.put("lyrics", value.songId, preserved)
        if (repo.dao.get(repo.account, "lyrics-override", value.songId) != null || value.source.startsWith("LRCLIB")) repo.put("lyrics-override", value.songId, preserved)
        if (preserved.lines.any { !it.translation.isNullOrBlank() }) repo.put("translation", "${LyricsEngine.originalHash(preserved)}:ZH:manual", preserved)
    }
    suspend fun translate(original: Lyrics): Lyrics = withContext(Dispatchers.IO) {
        check(!repo.offline && repo.currentSession()) { "请先连接服务器" }
        require(original.lines.isNotEmpty() && !LyricRepairPolicy.damaged(original)) { "请先匹配正确歌词，再翻译" }
        val key = repo.vault.get("${repo.account}.deepl")?.takeIf { it.isNotBlank() } ?: error("请先在设置中填写 DeepL API 密钥")
        val cacheKey = "${LyricsEngine.originalHash(original)}:ZH:deepl"
        repo.get("translation", cacheKey, Lyrics::class.java)?.let { return@withContext it.copy(songId = original.songId, offsetMs = original.offsetMs) }
        val endpoint = if (repo.settings.getBoolean("deepl-free", true)) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
        val indices = original.lines.indices.filter { original.lines[it].original.isNotBlank() }
        val result = original.lines.toMutableList()
        val context = original.lines.joinToString("\n") { it.original }.take(16000)
        for (batch in indices.chunked(40)) {
            val data = JsonObject().apply {
                add("text", repo.gson.toJsonTree(batch.map { original.lines[it].original }))
                addProperty("target_lang", "ZH-HANS"); addProperty("context", context)
            }
            val body = data.toString().toRequestBody("application/json".toMediaType())
            require(body.contentLength() < 128 * 1024) { "歌词过长，请分段校正后再翻译" }
            val request = Request.Builder().url(endpoint).header("Authorization", "DeepL-Auth-Key $key").post(body).build()
            val translations = repo.http.newCall(request).execute().use { r ->
                check(r.isSuccessful) { when (r.code) { 403 -> "DeepL 密钥无效或服务类型不匹配"; 456 -> "DeepL 翻译额度已用完"; 429 -> "翻译请求过于频繁，请稍后重试"; else -> "翻译失败（HTTP ${r.code}）" } }
                JsonParser.parseString(r.body!!.string()).asJsonObject.objects("translations").map { it.str("text") }
            }
            check(translations.size == batch.size) { "翻译行数不匹配，未覆盖原词" }
            batch.forEachIndexed { i, line -> result[line] = result[line].copy(translation = translations[i]) }
        }
        original.copy(lines = result, machine = true).also { repo.put("translation", cacheKey, it); save(it) }
    }
}
