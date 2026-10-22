package com.eddyizm.tempus.jiayin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES/GCM keys never leave Android Keystore. No credential is baked into the APK. */
class SecureVault(context: Context) {
    private val prefs = context.getSharedPreferences("jiayin-vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("jiayin-secrets-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("jiayin-secrets-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray()) }
        val result = cipher.iv + cipher.doFinal(value.toByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(result, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun get(name: String): String? = runCatching {
        val raw = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))); updateAAD(name.toByteArray())
        }
        String(cipher.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()
    fun remove(name: String) { prefs.edit().remove(name).apply() }
}
