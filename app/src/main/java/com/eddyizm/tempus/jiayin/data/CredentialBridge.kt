package com.eddyizm.tempus.jiayin.data

import com.eddyizm.tempus.App

/** Lets reused upstream networking read encrypted auth without persisting it in clear text. */
object CredentialBridge {
    private val cache = mutableMapOf<String, String?>()
    @Synchronized fun invalidate() { cache.clear() }
    private fun scope(): String? = App.getContext().getSharedPreferences("jiayin", 0).getString("account", null)
    fun active() = scope() != null
    @Synchronized fun read(field: String): String? {
        val id = scope()?.let { "$it.$field" } ?: return null
        if (!cache.containsKey(id)) cache[id] = SecureVault(App.getContext()).get(id)
        return cache[id]
    }
    @Synchronized fun write(field: String, value: String?) {
        val id = scope()?.let { "$it.$field" } ?: return
        if (value == null) SecureVault(App.getContext()).remove(id)
        else if (read(field) != value) SecureVault(App.getContext()).put(id, value)
        cache[id] = value
    }
}
