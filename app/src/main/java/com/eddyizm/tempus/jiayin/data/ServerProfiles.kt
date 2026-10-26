package com.eddyizm.tempus.jiayin.data

import android.content.Context
import com.eddyizm.tempus.jiayin.core.digest
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl

@androidx.annotation.Keep data class ServerProfile(val id: String, val name: String, val address: String, val username: String, val authenticated: Boolean = true)
data class AccountSession(val profile: ServerProfile, val generation: Long, val offline: Boolean = false)
object SessionGate {
    @Volatile private var blocked: String? = null
    fun block(account: String) { blocked = account }
    fun permits(account: String) = blocked != account
    fun clear() { blocked = null }
}
class AuthenticationRequired(message: String = "登录已失效，请重新登录") : IllegalStateException(message)
interface ProfileRegistry {
    fun active(): AccountSession?
    fun activate(profile: ServerProfile?, offline: Boolean = false)
    fun logout(id: String)
    fun remove(id: String)
}

/** Registry changes are committed together. Secrets are stored only in SecureVault. */
class ServerProfiles(context: Context) : ProfileRegistry {
    private val prefs = context.getSharedPreferences("jiayin", Context.MODE_PRIVATE)
    private val vault = SecureVault(context)
    private val gson = Gson()
    @Synchronized fun all(): List<ServerProfile> {
        val saved = prefs.getString("server-profiles-v1", null)
        val values = saved?.let { gson.fromJson(it, Array<ServerProfile>::class.java).toList() }.orEmpty()
        // Retain the preview.7 data namespace, including chunked documents and downloads.
        val id = prefs.getString("account", null) ?: return values
        if (values.any { it.id == id }) return values
        val address = prefs.getString("server", "").orEmpty()
        if (address.isBlank()) return values
        val initial = ServerProfile(id, runCatching { address.toHttpUrl().host }.getOrDefault("音乐服务器"), address, prefs.getString("user", "").orEmpty(), vault.get("$id.token") != null)
        val imported = values + initial
        save(imported); return imported
    }

    private fun save(values: List<ServerProfile>) { check(prefs.edit().putString("server-profiles-v1", gson.toJson(values)).commit()) }
    @Synchronized fun upsert(value: ServerProfile) { save(all().filterNot { it.id == value.id } + value) }
    fun find(id: String) = all().firstOrNull { it.id == id }
    override fun active(): AccountSession? {
        val id = prefs.getString("account", null) ?: return null
        val p = find(id) ?: return null
        if (!p.authenticated || vault.get("$id.token") == null) return null
        return AccountSession(p, prefs.getLong("session-generation", 0), prefs.getBoolean("session-offline", false))
    }
    @Synchronized override fun activate(profile: ServerProfile?, offline: Boolean) {
        if (profile != null) require(profile.authenticated && vault.get("${profile.id}.token") != null) { "请先重新登录" }
        check(prefs.edit().apply {
            putLong("session-generation", prefs.getLong("session-generation", 0) + 1)
            putBoolean("session-offline", offline)
            if (profile == null) { remove("account"); remove("server"); remove("user") }
            else { putString("account", profile.id); putString("server", profile.address); putString("user", profile.username) }
        }.commit())
        CredentialBridge.invalidate()
    }
    @Synchronized override fun logout(id: String) {
        vault.remove("$id.token"); vault.remove("$id.salt"); vault.remove("$id.manager-read"); vault.remove("$id.manager-member")
        find(id)?.let { upsert(it.copy(authenticated = false)) }; CredentialBridge.invalidate()
    }
    @Synchronized override fun remove(id: String) { logout(id); vault.remove("$id.deepl"); save(all().filterNot { it.id == id }) }
    fun rename(id: String, name: String) { require(name.isNotBlank()); find(id)?.let { upsert(it.copy(name = name.trim())) } }
    companion object { fun id(address: String, username: String) = digest("$address\n$username") }
}

/** An immutable data namespace; background operations never resolve their owner at completion. */
class AccountStorage(private val dao: LocalDao, val account: String, private val gson: Gson = Gson()) {
    fun <T> get(kind: String, id: String, type: Class<T>): T? = dao.get(account, kind, id)?.let { runCatching { gson.fromJson(it.payload, type) }.getOrNull() }
    fun put(kind: String, id: String, value: Any) = dao.put(LocalRow().also { it.account = account; it.kind = kind; it.id = id; it.payload = gson.toJson(value); it.updated = System.currentTimeMillis() })
    fun <T> list(kind: String, type: Class<T>): List<T> = dao.list(account, kind).mapNotNull { runCatching { gson.fromJson(it.payload, type) }.getOrNull() }
}
