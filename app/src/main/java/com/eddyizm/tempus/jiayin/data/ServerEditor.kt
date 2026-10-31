package com.eddyizm.tempus.jiayin.data

import com.eddyizm.tempus.jiayin.core.ServerAddressPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ServerDraft(val originalId: String? = null, val name: String = "", val address: String = "",
    val username: String = "", val password: String = "", val preserve: Boolean? = null,
    val confirmedSameLibrary: Boolean = false, val acceptUnverified: Boolean = false)
data class ServerEditorState(val draft: ServerDraft = ServerDraft(), val busy: Boolean = false,
    val committing: Boolean = false, val message: String? = null, val errors: Map<String, String> = emptyMap(),
    val success: Boolean = false, val needsIdentityConfirmation: Boolean = false, val savedId: String? = null)
class ServerFieldError(val field: String, message: String) : IllegalArgumentException(message)
class LibraryIdentityUnknown : IllegalStateException("连接成功，但无法核实原曲库的歌曲标识。请确认仍是同一音乐服务、同一账户，且未重建曲库。")
data class PreparedServer(val profile: ServerProfile, val token: String?, val salt: String?, val nameOnly: Boolean = false,
    val original: ServerProfile? = null)

object ServerEditorPolicy {
    fun address(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: throw ServerFieldError("address", "请填写完整地址，例如 https://music.example.com")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
            throw ServerFieldError("address", "地址中不要包含密码、查询参数或片段")
        if (url.scheme != "https" && !ServerAddressPolicy.allowsCleartext(url.host))
            throw ServerFieldError("address", "公网地址请使用 HTTPS；HTTP 仅用于本地网络")
        return url.toString().trimEnd('/')
    }
    fun error(error: Throwable): String = when (error) {
        is ServerFieldError, is LibraryIdentityUnknown -> error.message.orEmpty()
        is AuthenticationRequired -> "账户或密码不正确，或此账户没有访问权限"
        is javax.net.ssl.SSLException -> "HTTPS 证书验证失败，请检查证书及服务器地址"
        is java.net.UnknownHostException -> "无法解析服务器域名，请检查拼写和网络"
        is java.net.ConnectException -> "无法连接服务器，请检查地址、端口和网络"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "连接超时，请检查网络后重试"
        is com.google.gson.JsonParseException, is IllegalStateException -> "音乐服务返回异常，请确认填写的是 Navidrome / Subsonic 入口，而非 NAS 管理页"
        is java.io.IOException -> "网络请求未完成，请检查连接后重试"
        else -> "操作未完成，请重试"
    }
}
