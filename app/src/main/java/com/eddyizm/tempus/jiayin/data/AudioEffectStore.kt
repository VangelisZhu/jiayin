package com.eddyizm.tempus.jiayin.data

import android.content.SharedPreferences
import com.eddyizm.tempus.jiayin.core.*

/** Device preferences are shared; song choices are removable account-prefixed rows. */
class AudioEffectStore(val prefs: SharedPreferences) {
    private inline fun <reified T : Enum<T>> enum(key: String, fallback: T): T =
        enumValues<T>().find { it.name == prefs.getString(key, null) } ?: fallback
    private fun key(output: SoundOutput) = "audio-effects.${when (output) { SoundOutput.UNKNOWN, SoundOutput.REMOTE -> SoundOutput.OTHER; else -> output }.name}."
    fun settings(output: SoundOutput): AudioEffectSettings {
        val p = key(output)
        return AudioEffectSettings(prefs.getBoolean("audio-effects.enabled", false), enum(p + "strategy", SoundStrategy.AUTO),
            enum(p + "preset", SoundPreset.WARM), prefs.getFloat(p + "strength", .5f).finite(.5f),
            enum(p + "space", if (output == SoundOutput.HEADPHONES) SpaceMode.NATURAL else SpaceMode.OFF),
            prefs.getFloat(p + "space-strength", .25f).finite(.25f), enum(p + "character", SoundCharacter.NATURAL))
    }
    fun save(output: SoundOutput, value: AudioEffectSettings) {
        val p = key(output)
        prefs.edit().putBoolean("audio-effects.enabled", value.enabled).putString(p + "strategy", value.strategy.name)
            .putString(p + "character", value.character.name).putString(p + "preset", value.preset.name).putFloat(p + "strength", value.strength.finite(.5f))
            .putString(p + "space", value.space.name).putFloat(p + "space-strength", value.spaceStrength.finite(.25f)).apply()
    }
    private fun songKey(account: String, id: String) = "$account.audio-song.${digest(id)}"
    fun song(account: String, id: String?): SoundPreset? = id?.let { enumValues<SoundPreset>().find { p -> p.name == prefs.getString(songKey(account, it), null) } }
    fun saveSong(repo: JiayinRepository, id: String, preset: SoundPreset?) {
        check(repo.currentSession()) { "账户已切换，请重新打开音效设置" }
        prefs.edit().apply { if (preset == null) remove(songKey(repo.account, id)) else putString(songKey(repo.account, id), preset.name) }.apply()
    }
    private fun Float.finite(default: Float) = if (isFinite()) coerceIn(0f, 1f) else default
}
