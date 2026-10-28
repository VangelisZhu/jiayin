package com.eddyizm.tempus.jiayin.core

import kotlin.math.*

enum class SoundPreset(val label: String) {
    WARM("温暖饱满"), VOCAL("流行人声"), RHYTHM("节奏低频"), ROCK("摇滚层次"), ACOUSTIC("原声自然"), FLAT("均衡原声")
}
enum class SoundStrategy(val label: String) { AUTO("自动"), FIXED("固定") }
enum class SpaceMode(val label: String) { OFF("关闭"), NATURAL("自然耐听"), IMMERSIVE("轻度沉浸") }
enum class SoundOutput(val label: String) { HEADPHONES("耳机"), SPEAKER("手机扬声器"), OTHER("其他本地输出"), UNKNOWN("等待实际输出"), REMOTE("远端播放") }
data class AudioEffectSettings(
    val enabled: Boolean = false, val strategy: SoundStrategy = SoundStrategy.AUTO,
    val preset: SoundPreset = SoundPreset.WARM, val strength: Float = .5f,
    val space: SpaceMode = SpaceMode.OFF, val spaceStrength: Float = .25f,
)
data class ResolvedAudioEffect(val preset: SoundPreset, val reason: String)
data class AudioEffectConfig(val enabled: Boolean = false, val preset: SoundPreset = SoundPreset.WARM,
    val strength: Float = .5f, val space: SpaceMode = SpaceMode.OFF, val spaceStrength: Float = .25f)

object AudioEffectResolver {
    private fun normal(s: String) = s.trim().lowercase(java.util.Locale.ROOT).replace(Regex("[\\s_–—-]+"), "")
    private val tags = buildMap {
        fun group(preset: SoundPreset, vararg names: String) { names.forEach { put(normal(it), preset) } }
        group(SoundPreset.VOCAL, "pop", "流行", "流行音乐", "华语流行", "cantopop", "mandopop", "j-pop", "k-pop", "c-pop", "synthpop", "indie pop", "electropop")
        group(SoundPreset.RHYTHM, "hip-hop", "hip hop/rap", "rap", "嘻哈", "说唱", "r&b", "rnb", "rhythm and blues", "electronic", "electronica", "电子", "dance", "舞曲", "edm", "house", "techno", "trance")
        group(SoundPreset.ROCK, "rock", "摇滚", "metal", "金属", "punk", "朋克", "alternative rock", "indie rock", "hard rock", "heavy metal")
        group(SoundPreset.ACOUSTIC, "acoustic", "原声", "folk", "民谣", "jazz", "爵士", "bossa nova")
        group(SoundPreset.FLAT, "classical", "古典", "古典音乐", "symphony", "交响乐", "chamber", "室内乐", "baroque")
    }
    fun resolve(settings: AudioEffectSettings, genres: List<String>, song: SoundPreset?): ResolvedAudioEffect {
        if (settings.strategy == SoundStrategy.FIXED) return ResolvedAudioEffect(settings.preset, "固定音色")
        if (song != null) return ResolvedAudioEffect(song, "此歌曲指定")
        val hits = genres.mapNotNull { tags[normal(it)] }.distinct()
        return if (hits.size == 1) ResolvedAudioEffect(hits.single(), "曲风：${genres.joinToString("、")}")
        else ResolvedAudioEffect(settings.preset, if (hits.isEmpty()) "未识别曲风，使用${settings.preset.label}" else "曲风存在冲突，使用${settings.preset.label}")
    }
    fun config(s: AudioEffectSettings, resolved: ResolvedAudioEffect, output: SoundOutput, compare: Boolean) = AudioEffectConfig(
        s.enabled && !compare && output != SoundOutput.REMOTE, resolved.preset, s.strength.coerceIn(0f, 1f),
        if (output in listOf(SoundOutput.UNKNOWN, SoundOutput.REMOTE)) SpaceMode.OFF else s.space, s.spaceStrength.coerceIn(0f, 1f))
}

/** Pure, bounded stereo DSP; built off the render thread. A single final conversion follows DSP. */
class AudioEffectDsp(val config: AudioEffectConfig, val sampleRate: Int, val channels: Int) {
    private data class Coeff(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)
    private val gains = gains(config.preset).map { it * config.strength.coerceIn(0f, 1f) }
    private val coeffs = frequencies.mapIndexedNotNull { i, f ->
        if (f >= sampleRate * .45 || gains[i] == 0.0) null else {
            val a = 10.0.pow(gains[i] / 40); val w = 2 * PI * f / sampleRate
            val alpha = sin(w) / (2 * .75); val a0 = 1 + alpha / a
            Coeff((1 + alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha / a) / a0)
        }
    }
    private val states = DoubleArray(coeffs.size * channels * 2)
    private val amount = config.spaceStrength.coerceIn(0f, 1f).toDouble()
    private val space = if (channels == 2) config.space else SpaceMode.OFF
    // Sum of positive stage gains is a conservative frequency-response bound. Space expansion
    // has a separate bound; no loudness boost or make-up gain is applied.
    private val headroom = 10.0.pow(-gains.filter { it > 0 }.sum() / 20) / if (space == SpaceMode.IMMERSIVE) 1 + .6 * amount else 1.0
    private val crossAlpha = 1 - exp(-2 * PI * 700 / sampleRate)
    private val bassAlpha = 1 - exp(-2 * PI * 200 / sampleRate)
    private var lowL = 0.0; private var lowR = 0.0; private var lowSide = 0.0
    val bypass get() = !config.enabled || channels !in 1..2
    private fun eq(value: Double, channel: Int): Double {
        var y = value * headroom
        for (i in coeffs.indices) {
            val b = coeffs[i]; val n = (channel * coeffs.size + i) * 2
            val x = y; y = b.b0 * x + states[n]
            states[n] = b.b1 * x - b.a1 * y + states[n + 1]
            states[n + 1] = b.b2 * x - b.a2 * y
        }
        return y
    }
    /** Output reused by the caller; no allocation in the sample loop. */
    fun frame(left: Double, right: Double, out: DoubleArray) {
        if (bypass) { out[0] = left; out[1] = right; return }
        var l = eq(left, 0); var r = if (channels == 2) eq(right, 1) else l
        when (space) {
            SpaceMode.NATURAL -> {
                lowL += crossAlpha * (l - lowL); lowR += crossAlpha * (r - lowR)
                val cross = .22 * amount
                l = (l + cross * lowR) / (1 + cross); r = (r + cross * lowL) / (1 + cross)
            }
            SpaceMode.IMMERSIVE -> {
                val middle = (l + r) * .5; val side = (l - r) * .5
                lowSide += bassAlpha * (side - lowSide)
                val wide = side + .3 * amount * (side - lowSide)
                l = middle + wide; r = middle - wide
            }
            else -> Unit
        }
        check(l.isFinite() && r.isFinite()) { "Non-finite audio output" }
        out[0] = l; out[1] = r
    }
    fun clear() { states.fill(0.0); lowL = 0.0; lowR = 0.0; lowSide = 0.0 }
    companion object {
        val frequencies = listOf(80.0, 250.0, 1000.0, 3000.0, 8000.0)
        fun gains(p: SoundPreset): List<Double> = when (p) {
            SoundPreset.WARM -> listOf(2.0, -.8, .3, -.6, -.6)
            SoundPreset.VOCAL -> listOf(.8, -.8, .8, 1.2, -.5)
            SoundPreset.RHYTHM -> listOf(3.0, -1.0, 0.0, .3, -.3)
            SoundPreset.ROCK -> listOf(1.4, -.8, .5, .6, -.8)
            SoundPreset.ACOUSTIC -> listOf(.4, -.4, .3, .4, 0.0)
            SoundPreset.FLAT -> List(5) { 0.0 }
        }
        fun pcm16(x: Double): Short = (x.coerceIn(-1.0, 32767.0 / 32768) * 32768).roundToInt().toShort()
    }
}
