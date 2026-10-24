package com.eddyizm.tempus.jiayin.data

import com.eddyizm.tempus.jiayin.core.*
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Optional, read-only companion. Explicitly paired to this immutable account session. */
class ManagerMetadata(private val repo: JiayinRepository) {
    fun load(tracks: List<Track>, address: String = repo.settings.getString("${repo.account}.manager-url", "").orEmpty(), token: String? = repo.vault.get("${repo.account}.manager-read")): List<Track> {
        if (address.isBlank() || token.isNullOrBlank()) return tracks
        check(repo.currentSession() && !repo.offline) { "账户已切换或处于离线状态" }
        val base = address.toHttpUrl()
        require(base.username.isBlank() && base.password.isBlank() && base.query == null && base.fragment == null &&
            (base.isHttps || ServerAddressPolicy.allowsCleartext(base.host))) { "后台请使用 HTTPS；HTTP 仅允许局域网地址" }
        val url = base.newBuilder().addPathSegments("api/client/catalog").build()
        val body = repo.http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use {
            check(it.isSuccessful) { "曲库管理连接失败（HTTP ${it.code}）" }
            check((it.body?.contentLength() ?: 0) <= 8 * 1024 * 1024) { "曲库管理信息过大" }
            JsonParser.parseString(it.body?.string() ?: error("曲库管理返回空内容")).asJsonObject
        }
        check(repo.currentSession()) { "账户已切换" }
        check(body.str("server").toHttpUrl().toString().trimEnd('/') == repo.server.toHttpUrl().toString().trimEnd('/') && body.str("username") == repo.user) { "曲库管理绑定的服务器或账户不匹配" }
        val metadata = body.objects("tracks").associateBy { it.str("id") }
        return tracks.map { t -> metadata[t.id]?.let {
            t.copy(sharedRegion = it.str("region"), language = it.str("language"), albumVersion = it.str("albumVersion"))
        } ?: t }
    }
}
