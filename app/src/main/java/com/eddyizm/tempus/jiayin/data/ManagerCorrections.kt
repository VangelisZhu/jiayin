package com.eddyizm.tempus.jiayin.data

import com.eddyizm.tempus.jiayin.core.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Captures a fixed account session; corrections never issue playback commands. */
class ManagerCorrections(private val repo: JiayinRepository) {
    companion object { private val refreshLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>() }
    private val base get() = repo.settings.getString("${repo.account}.manager-url", "").orEmpty().toHttpUrl().also {
        require(it.username.isBlank() && it.password.isBlank() && it.query == null && it.fragment == null &&
            (it.isHttps || ServerAddressPolicy.allowsCleartext(it.host))) { "后台使用 HTTPS；HTTP 仅允许局域网地址" }
    }

    suspend fun request(path: String, body: JsonElement? = null): JsonElement = withContext(Dispatchers.IO) {
        check(repo.currentSession() && !repo.offline) { "账户已切换或正在离线浏览" }
        val pairing = path == "pair"
        val token = repo.vault.get("${repo.account}.manager-member")
        if (!pairing) check(!token.isNullOrBlank()) { "请先连接曲库整理并等待管理员授权" }
        val url = base.newBuilder().addPathSegments("api/member/" + path.substringBefore('?')).encodedQuery(path.substringAfter('?', "").takeIf { it.isNotBlank() }).build()
        val builder = Request.Builder().url(url).header("X-Jiayin-Request", "1")
        if (!pairing) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        val result = repo.http.newCall(builder.build()).execute().use { response ->
            val stream = response.body?.byteStream() ?: error("后台返回空内容")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; check(output.size() + n <= 2 * 1024 * 1024) { "后台响应过大，请缩小范围" }; output.write(buffer, 0, n) }
            val raw = output.toByteArray()
            val parsed = JsonParser.parseString(raw.toString(Charsets.UTF_8))
            check(response.isSuccessful) { runCatching { parsed.asJsonObject.str("detail") }.getOrDefault("").ifBlank { "整理请求失败（HTTP ${response.code}）" } }
            parsed
        }
        check(repo.currentSession()) { "账户已切换，已忽略旧结果" }
        result
    }

    suspend fun pair(address: String): JsonObject {
        val clean = address.trim().trimEnd('/').toHttpUrl()
        require(clean.username.isBlank() && clean.password.isBlank() && clean.query == null && clean.fragment == null &&
            (clean.isHttps || ServerAddressPolicy.allowsCleartext(clean.host))) { "后台地址无效；公网请使用 HTTPS" }
        check(repo.currentSession()) { "账户已切换" }
        val old = repo.settings.getString("${repo.account}.manager-url", null)
        repo.settings.edit().putString("${repo.account}.manager-url", clean.toString().trimEnd('/')).apply()
        try {
            val auth = repo.vault.authentication(repo.account) ?: error("请重新登录音乐账户")
            val body = JsonObject().apply { addProperty("username", repo.user); addProperty("token", auth.first); addProperty("salt", auth.second); addProperty("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL) }
            val response = request("pair", body).asJsonObject
            check(response.str("server").toHttpUrl().toString().trimEnd('/') == repo.server.toHttpUrl().toString().trimEnd('/')) { "此后台绑定了其他音乐服务器，请核对地址" }
            check(repo.currentSession()) { "账户已切换" }
            repo.vault.put("${repo.account}.manager-member", response.str("token"))
            repo.settings.edit().remove("${repo.account}.manager-cursor").apply()
            return response
        } catch (e: Exception) {
            repo.settings.edit().putString("${repo.account}.manager-url", old).apply()
            throw e
        }
    }

    suspend fun refresh(): List<Track> = refreshLocks.getOrPut(repo.account) { Mutex() }.withLock { refreshLocked() }

    private suspend fun refreshLocked(): List<Track> {
        var cursor = repo.settings.getLong("${repo.account}.manager-cursor", 0)
        val updates = mutableListOf<JsonObject>()
        var more: Boolean
        do {
            val body = request("catalog?after=$cursor").asJsonObject
            updates.addAll(body.objects("tracks"))
            val next = body.get("cursor")?.asLong ?: cursor
            more = body.get("has_more")?.asBoolean == true && next > cursor
            cursor = next
        } while (more)
        return withContext(Dispatchers.IO) {
            check(repo.currentSession()) { "账户已切换" }
            updates.forEach { row ->
                repo.put("manager-metadata", row.str("id"), SharedCorrection(row.str("id"), row.getAsJsonObject("metadata").toString(), row.str("revision"), row.str("cover_art"), row.getAsJsonObject("refs")?.toString()))
                repo.dao.delete(repo.account, "lyrics", row.str("id"))
            }
            val metadata = repo.list("manager-metadata", SharedCorrection::class.java).associateBy { it.id }
            val library = repo.library()
            val tracks = library.tracks.map { t -> metadata[t.id]?.let { update ->
                val m = JsonParser.parseString(update.metadata).asJsonObject
                val refs = update.refs?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
                fun names(key: String) = m.getAsJsonArray(key)?.map { it.asString }.orEmpty()
                if (m.entrySet().isEmpty()) t.copy(cover = VersionedCover.encode(update.cover.ifBlank { t.cover }, update.revision))
                else t.copy(title = m.str("title"), artist = m.str("artist_credit"), album = m.str("album"),
                    albumId = refs.str("albumId").ifBlank { if (t.album == m.str("album")) t.albumId else "" },
                    artistId = refs.str("artistId").ifBlank { if (names("artists").firstOrNull() == t.performers().firstOrNull()?.name) t.artistId else "" },
                    cover = VersionedCover.encode(update.cover.ifBlank { t.cover }, update.revision),
                    artists = names("artists").map { name -> ArtistRef(refs.objects("artists").find { it.str("name") == name }?.str("id").orEmpty(), name) },
                    albumArtists = names("album_artists").map { name -> ArtistRef(refs.objects("albumArtists").find { it.str("name") == name }?.str("id").orEmpty(), name) }, genres = names("genres"), genre = names("genres").firstOrNull().orEmpty(),
                    recordingId = m.str("recording_id"), releaseId = m.str("release_id"),
                    releaseDate = runCatching { val date = m.str("date"); if (date.isBlank()) 0L else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(date + when (date.length) { 4 -> "-01-01"; 7 -> "-01"; else -> "" })?.time ?: 0L }.getOrDefault(0L),
                    sharedRegion = m.str("region"), language = m.str("language"), albumVersion = m.str("album_version"))
            } ?: t }
            check(repo.currentSession()) { "账户已切换" }
            repo.saveLibrary(library.copy(tracks = tracks))
            repo.settings.edit().putLong("${repo.account}.manager-cursor", cursor).apply()
            if (updates.isNotEmpty()) com.eddyizm.tempus.jiayin.playback.PlaybackHub.metadataRevision.value++
            tracks
        }
    }
}

@androidx.annotation.Keep data class SharedCorrection(val id: String, val metadata: String, val revision: String, val cover: String, val refs: String? = null)
