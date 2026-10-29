package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AudioEffectsTest {
    @Test fun automaticAliasesConflictUnknownAndSpecificOverride() {
        val s = AudioEffectSettings()
        fun pick(vararg tags: String) = AudioEffectResolver.resolve(s, tags.toList(), null)
        assertEquals(SoundPreset.VOCAL, pick("J-Pop", "华语流行").preset)
        assertEquals(SoundPreset.RHYTHM, pick("Hip Hop", "R&B").preset)
        assertEquals(SoundPreset.WARM, pick("Pop", "Jazz").preset)
        assertTrue(pick("Pop", "Jazz").reason.contains("冲突"))
        assertTrue(pick("Japanese", "Generated singer").reason.contains("未识别"))
        assertEquals(SoundPreset.FLAT, pick("古典").preset)
        assertEquals(SoundPreset.ACOUSTIC, AudioEffectResolver.resolve(s, listOf("Pop"), SoundPreset.ACOUSTIC).preset)
    }
    @Test fun fixedIgnoresSongAndGenreAndCustomFallbackIsUsed() {
        val s = AudioEffectSettings(strategy = SoundStrategy.FIXED, preset = SoundPreset.ROCK)
        assertEquals(SoundPreset.ROCK, AudioEffectResolver.resolve(s, listOf("Pop"), SoundPreset.FLAT).preset)
        assertEquals(SoundPreset.ROCK, AudioEffectResolver.resolve(s.copy(strategy = SoundStrategy.AUTO), emptyList(), null).preset)
    }
    @Test fun compareAndRemoteNeverEnableEffectsAndUnknownRouteNeverSpatializes() {
        val s = AudioEffectSettings(enabled = true, space = SpaceMode.IMMERSIVE)
        val r = AudioEffectResolver.resolve(s, emptyList(), null)
        assertFalse(AudioEffectResolver.config(s, r, SoundOutput.HEADPHONES, true).enabled)
        assertFalse(AudioEffectResolver.config(s, r, SoundOutput.REMOTE, false).enabled)
        assertEquals(SpaceMode.OFF, AudioEffectResolver.config(s, r, SoundOutput.UNKNOWN, false).space)
        assertEquals(SpaceMode.IMMERSIVE, AudioEffectResolver.config(s, r, SoundOutput.HEADPHONES, false).space)
    }
    @Test fun defaultIsOffAndPresetsHaveBoundedGains() {
        assertFalse(AudioEffectSettings().enabled)
        SoundPreset.entries.forEach { p -> assertTrue(AudioEffectDsp.gains(p).all { abs(it) <= 3 }) }
    }
    @Test fun disabledAndZeroStrengthAreExactIdentity() {
        val out = DoubleArray(2)
        for (config in listOf(AudioEffectConfig(), AudioEffectConfig(true, strength = 0f))) {
            val dsp = AudioEffectDsp(config, 48000, 2)
            for (n in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) {
                val x = n / 32768.0; dsp.frame(x, -x, out)
                assertEquals(x, out[0], 0.0); assertEquals(n.toShort(), AudioEffectDsp.pcm16(out[0]))
            }
        }
    }
    @Test fun naturalCrossfeedsOnlyStereoAndKeepsCentralImage() {
        val config = AudioEffectConfig(true, SoundPreset.FLAT, space = SpaceMode.NATURAL, spaceStrength = 1f)
        val stereo = AudioEffectDsp(config, 48000, 2); val mono = AudioEffectDsp(config, 48000, 1)
        val out = DoubleArray(2)
        repeat(1000) { stereo.frame(.4, 0.0, out) }
        assertTrue(out[1] > .07); assertEquals(.4, out.sum(), 1e-8)
        stereo.clear(); repeat(1000) { stereo.frame(.4, .4, out) }
        assertEquals(.4, out[0], 1e-8); assertEquals(out[0], out[1], 0.0)
        mono.frame(.4, .4, out); assertEquals(.4, out[0], 0.0)
    }
    @Test fun immersivePreservesCenterAndExpandsHighSidesMoreThanBass() {
        fun sideRatio(freq: Int): Double {
            val dry = AudioEffectDsp(AudioEffectConfig(true, SoundPreset.FLAT), 48000, 2)
            val wet = AudioEffectDsp(AudioEffectConfig(true, SoundPreset.FLAT, space = SpaceMode.IMMERSIVE, spaceStrength = 1f), 48000, 2)
            val a = DoubleArray(2); val b = DoubleArray(2); var dryPower = 0.0; var wetPower = 0.0
            repeat(48000) { n -> val x = .2 * sin(2 * PI * freq * n / 48000); dry.frame(x, -x, a); wet.frame(x, -x, b)
                if (n > 4800) { dryPower += a[0] * a[0]; wetPower += b[0] * b[0] }
                assertEquals(-b[0], b[1], 1e-10)
            }
            return sqrt(wetPower / dryPower)
        }
        assertTrue(sideRatio(4000) > sideRatio(40) * 1.15)
        val dsp = AudioEffectDsp(AudioEffectConfig(true, SoundPreset.FLAT, space = SpaceMode.IMMERSIVE), 48000, 2)
        val out = DoubleArray(2); repeat(100) { dsp.frame(.3, .3, out); assertEquals(out[0], out[1], 0.0) }
    }
    @Test fun allPresetsStableAcrossRatesAndFullScaleImpulseAndSilence() {
        val out = DoubleArray(2)
        for (rate in listOf(44100, 48000, 96000)) for (p in SoundPreset.entries) for (space in SpaceMode.entries) {
            val dsp = AudioEffectDsp(AudioEffectConfig(true, p, 1f, space, 1f), rate, 2)
            repeat(rate / 4) { n ->
                val x = if (n == 0) 1.0 else if (n < 500) (if (n % 2 == 0) 1.0 else -1.0) else 0.0
                dsp.frame(x, -x, out)
                assertTrue(out.all { it.isFinite() && abs(it) < 2 })
                out.forEach { assertTrue(AudioEffectDsp.pcm16(it).toInt() in -32768..32767) }
            }
            assertTrue(out.all { abs(it) < 1e-6 })
            dsp.clear(); dsp.frame(0.0, 0.0, out); assertEquals(0.0, out[0], 0.0)
        }
    }
    @Test fun measuredWarmResponseAddsBassRelativeToMidrangeWithoutBoostingFullScale() {
        fun magnitude(freq: Double): Double {
            val dsp = AudioEffectDsp(AudioEffectConfig(true, SoundPreset.WARM, 1f), 48000, 1)
            val out = DoubleArray(2); var power = 0.0
            repeat(48000) { n -> val x = .2 * sin(2 * PI * freq * n / 48000); dsp.frame(x, x, out); if (n >= 4800) power += out[0] * out[0] }
            return sqrt(power / 43200) / (.2 / sqrt(2.0))
        }
        assertTrue(magnitude(80.0) > magnitude(1000.0))
        for (freq in listOf(20.0, 80.0, 250.0, 1000.0, 3000.0, 8000.0, 18000.0)) assertTrue(magnitude(freq) <= 1.01)
    }
    @Test fun multichannelAlwaysBypasses() {
        val dsp = AudioEffectDsp(AudioEffectConfig(true, SoundPreset.RHYTHM, 1f, SpaceMode.IMMERSIVE, 1f), 48000, 6)
        val out = DoubleArray(2); dsp.frame(.1, -.8, out)
        assertTrue(dsp.bypass); assertArrayEquals(doubleArrayOf(.1, -.8), out, 0.0)
    }
    @Test fun vividPreservesDefaultsAndFlatButHasStrongerMeasuredBassContrast() {
        assertEquals(SoundCharacter.NATURAL, AudioEffectSettings().character)
        fun level(c: SoundCharacter, f: Double): Double {
            val dsp = AudioEffectDsp(AudioEffectConfig(true, SoundPreset.WARM, .5f, character = c), 48000, 2)
            val out = DoubleArray(2); var power = 0.0
            repeat(24000) { n -> val x = .3 * sin(2 * PI * f * n / 48000); dsp.frame(x, x, out); if (n > 2400) power += out[0] * out[0] }
            return power
        }
        assertTrue(level(SoundCharacter.VIVID, 80.0) / level(SoundCharacter.VIVID, 3000.0) > level(SoundCharacter.NATURAL, 80.0) / level(SoundCharacter.NATURAL, 3000.0))
        for (config in listOf(AudioEffectConfig(true, SoundPreset.FLAT, character = SoundCharacter.VIVID), AudioEffectConfig(true, strength = 0f, character = SoundCharacter.VIVID))) {
            val dsp = AudioEffectDsp(config, 48000, 2); val out = DoubleArray(2)
            repeat(1000) { dsp.frame(.3, -.2, out); assertArrayEquals(doubleArrayOf(.3, -.2), out, 0.0) }
        }
    }
    @Test fun vividAllPresetsRatesAndSpacesStayFiniteAtFullScale() {
        val out = DoubleArray(2)
        for (rate in listOf(44100, 48000, 96000)) for (preset in SoundPreset.entries) for (space in SpaceMode.entries) {
            val dsp = AudioEffectDsp(AudioEffectConfig(true, preset, 1f, space, 1f, SoundCharacter.VIVID), rate, 2)
            repeat(rate / 3) { n -> val x = if (n < 1000) if (n % 2 == 0) 1.0 else -1.0 else 0.0
                dsp.frame(x, -x, out); assertTrue(out.all { it.isFinite() && abs(it) < 2 })
            }
            assertTrue(out.all { abs(it) < 1e-6 })
        }
    }

}
