@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.*
import com.eddyizm.tempus.jiayin.ui.AudioEffectsPage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class AudioEffectsAndroidTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun main(action: () -> Unit) = ins.runOnMainSync(action)
    private suspend fun await(label: String, test: () -> Boolean) = withTimeout(20000) { while (!test()) delay(30); android.util.Log.i("EffectCheck", label) }
    private fun buffer(samples: ShortArray) = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply { samples.forEach(::putShort); flip() }
    private fun process(p: JiayinAudioEffectProcessor, samples: ShortArray): ShortArray { p.queueInput(buffer(samples)); val result = p.output.order(ByteOrder.nativeOrder()); return ShortArray(result.remaining() / 2) { result.short } }

    @Test fun processorOffIsBitExactAtThreeRatesAndMonoStereoAndMultichannel() = runBlocking {
        for (rate in listOf(44100, 48000, 96000)) for (channels in listOf(1, 2, 6)) {
            val p = JiayinAudioEffectProcessor()
            try {
                p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)); p.flush()
                delay(50)
                val samples = ShortArray(channels * 8192) { ((it * 31) % 65536 - 32768).toShort() }
                assertArrayEquals(samples, process(p, samples))
                if (channels == 6) { p.configure(AudioEffectConfig(true, space = SpaceMode.IMMERSIVE)); delay(50); assertArrayEquals(samples, process(p, samples)) }
            } finally { p.close(); p.reset() }
        }
    }
    @Test fun oneHundredRampsPreserveFramesStayContinuousAndFinishBitExact() = runBlocking {
        val p = JiayinAudioEffectProcessor(); var sample = 0
        fun wave(n: Int) = ShortArray(n * 2) { i -> (.35 * 32768 * sin(2 * PI * 100 * (sample + i / 2) / 48000)).toInt().toShort() }.also { sample += n }
        try {
            p.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); p.flush()
            var last = 0
            repeat(100) { n ->
                p.configure(AudioEffectConfig(n % 3 != 0, SoundPreset.entries[n % 6], (n % 10) / 10f, SpaceMode.entries[n % 3], .5f))
                delay(8)
                repeat(12) {
                    val result = process(p, wave(1024)); assertEquals(2048, result.size)
                    for (i in result.indices step 2) { assertTrue("Discontinuity at switch $n", abs(result[i].toInt() - last) < 2000); last = result[i].toInt() }
                }
            }
            p.configure(AudioEffectConfig()); delay(50); repeat(30) { process(p, wave(1024)) }
            val dry = wave(2048); assertArrayEquals(dry, process(p, dry))
            assertEquals(false, p.status.value.applied?.enabled)
            p.flush(); assertArrayEquals(dry, process(p, dry))
        } finally { p.close(); p.reset() }
    }
    @Test fun formatChangesClearHistoryAndLatestAutomaticPresetWins() = runBlocking {
        val p = JiayinAudioEffectProcessor()
        try {
            p.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)); p.flush()
            for (preset in SoundPreset.entries) p.configure(AudioEffectConfig(true, preset), true)
            delay(50); repeat(30) { process(p, ShortArray(2048)) }
            assertEquals(SoundPreset.FLAT, p.status.value.applied?.preset)
            p.configure(AudioFormat(96000, 1, C.ENCODING_PCM_16BIT)); p.flush(); delay(50)
            repeat(60) { assertArrayEquals(ShortArray(1024), process(p, ShortArray(1024))) }
            assertEquals(96000, p.status.value.format.rate); assertNull(p.status.value.error)
        } finally { p.close(); p.reset() }
    }
    @Test fun outputPreferencesAndSameIdSongRulesAreIsolatedAndStaleAccountCannotWrite() = runBlocking {
        MediaPipelineTest.MediaServer(allowedUsers = setOf("effect-a", "effect-b")).use { server ->
            val root = JiayinRepository.get(context)
            root.login("http://localhost:${server.port}", "effect-a", "fixture-password")
            val a = root.scoped(); val store = AudioEffectStore(a.settings)
            try {
                store.save(SoundOutput.HEADPHONES, AudioEffectSettings(true, preset = SoundPreset.ROCK, space = SpaceMode.NATURAL))
                store.save(SoundOutput.SPEAKER, AudioEffectSettings(true, preset = SoundPreset.ACOUSTIC))
                store.saveSong(a, "same", SoundPreset.VOCAL)
                root.login("http://localhost:${server.port}", "effect-b", "fixture-password")
                val b = root.scoped()
                assertNull(store.song(b.account, "same")); store.saveSong(b, "same", SoundPreset.FLAT)
                assertEquals(SoundPreset.VOCAL, store.song(a.account, "same"))
                assertEquals(SoundPreset.ROCK, store.settings(SoundOutput.HEADPHONES).preset)
                assertEquals(SoundPreset.ACOUSTIC, store.settings(SoundOutput.SPEAKER).preset)
                assertTrue(runCatching { store.saveSong(a, "same", SoundPreset.WARM) }.isFailure)
                assertEquals(SoundPreset.FLAT, store.song(b.account, "same"))
                store.saveSong(b, "same", null); assertNull(store.song(b.account, "same"))
            } finally { store.save(SoundOutput.HEADPHONES, AudioEffectSettings(space = SpaceMode.NATURAL)); store.save(SoundOutput.SPEAKER, AudioEffectSettings()) }
        }
    }
    @Test(timeout = 120000) fun realPlaybackProcessesWithoutReplacingSessionQueueOrStartingDownloads() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val songs = listOf(Track("fx-one", "Generated Pop", genre = "Pop", duration = 30), Track("fx-two", "Generated Rock", genre = "Rock", duration = 30))
        MediaPipelineTest.MediaServer(supplied = songs, sampleRate = 48000, bits = 24, channels = 2).use { server ->
            val root = JiayinRepository.get(context)
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            root.saveLibrary(Library(songs, syncedAt = System.currentTimeMillis())); root.dao.delete(root.account, "queue", "active")
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            val store = AudioEffectStore(root.settings)
            try {
                scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
                await("library ready") { !vm.state.value.loading }; main { vm.start(songs) }
                await("actual AudioTrack route and PCM") { PlaybackHub.state.value.playing && AudioEffectHub.state.value.output != SoundOutput.UNKNOWN && AudioEffectHub.state.value.processing.format.rate > 0 }
                val output = AudioEffectHub.state.value.output
                assertEquals(48000, AudioEffectHub.state.value.processing.format.rate)
                assertEquals(2, AudioEffectHub.state.value.processing.format.channels)
                assertEquals(C.ENCODING_PCM_16BIT, AudioEffectHub.state.value.processing.format.encoding)
                val before = PlaybackHub.state.value.queue
                val manager = context.getSystemService(NotificationManager::class.java)
                await("notification") { manager.activeNotifications.any { it.id == 1001 } }
                @Suppress("DEPRECATION") val token = manager.activeNotifications.first { it.id == 1001 }.notification.extras.get(Notification.EXTRA_MEDIA_SESSION)
                main { store.save(output, AudioEffectSettings(true, space = SpaceMode.NATURAL)) }
                await("real PCM applied") { AudioEffectHub.state.value.processing.applied?.enabled == true && AudioEffectHub.state.value.resolved.preset == SoundPreset.VOCAL }
                repeat(20) { n -> main { store.save(output, AudioEffectSettings(true, SoundStrategy.FIXED, SoundPreset.entries[n % 6], space = SpaceMode.entries[n % 3])) }; delay(50) }
                assertEquals(before.entries, PlaybackHub.state.value.queue.entries)
                assertEquals(before.current?.key, PlaybackHub.state.value.queue.current?.key)
                assertTrue(PlaybackHub.state.value.queue.positionMs >= before.positionMs)
                @Suppress("DEPRECATION") assertEquals(token, manager.activeNotifications.first { it.id == 1001 }.notification.extras.get(Notification.EXTRA_MEDIA_SESSION))
                main { AudioEffectHub.compare("test", true) }; await("compare bypass") { AudioEffectHub.state.value.processing.applied?.enabled == false }
                main { AudioEffectHub.compare("test", false); store.save(output, AudioEffectSettings(true)); vm.command("next") }
                await("next resolves rock") { PlaybackHub.state.value.current?.id == "fx-two" && AudioEffectHub.state.value.processing.applied?.preset == SoundPreset.ROCK }
                main { vm.command("pause"); store.save(output, AudioEffectSettings(false)) }
                await("remains paused") { !PlaybackHub.state.value.playing && PlaybackHub.state.value.cause == PauseCause.USER }
                assertEquals(0, server.downloads.get())
                assertEquals(0, server.searches.get())
            } finally {
                main { AudioEffectHub.compare("test", false); store.save(SoundOutput.SPEAKER, AudioEffectSettings()); vm.command("pause") }
                scenario.close(); JiayinMediaService.stopForSwitch(context)
            }
        }
    }
    @Test(timeout = 60000) fun nativeSettingsTouchSavesPresetAndCloseClearsComparison() = runBlocking {
        MediaPipelineTest.MediaServer().use { server ->
            val root = JiayinRepository.get(context); root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            root.saveLibrary(Library(server.tracks, syncedAt = System.currentTimeMillis()))
            val store = AudioEffectStore(root.settings)
            store.save(SoundOutput.HEADPHONES, AudioEffectSettings(space = SpaceMode.NATURAL))
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try {
                lateinit var vm: JiayinViewModel
                scenario.onActivity { a -> vm = ViewModelProvider(a)[JiayinViewModel::class.java] }
                await("UI library") { !vm.state.value.loading }; main { vm.start(server.tracks) }
                await("UI playing") { PlaybackHub.state.value.playing && AudioEffectHub.state.value.output != SoundOutput.UNKNOWN }
                store.save(SoundOutput.SPEAKER, AudioEffectSettings())
                scenario.onActivity { a -> a.setContent { androidx.compose.material3.MaterialTheme { AudioEffectsPage(vm) { a.finish() } } } }
                click("耳机"); click("音效总开关"); click("固定"); click("固定音色：温暖饱满"); click("流行人声")
                assertTrue(store.settings(SoundOutput.HEADPHONES).enabled)
                assertEquals(SoundStrategy.FIXED, store.settings(SoundOutput.HEADPHONES).strategy)
                assertEquals(SoundPreset.VOCAL, store.settings(SoundOutput.HEADPHONES).preset)
                delay(800)
                val file = java.io.File(context.getExternalFilesDir(null), "ui-fixture/audio-effects.png"); file.parentFile!!.mkdirs()
                ins.uiAutomation.takeScreenshot().also { b -> file.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
                repeat(4) { if (nodes().none { it.text?.toString() == "原声对比" }) {
                    nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); delay(350)
                } }
                click("原声对比"); await("comparison active") { AudioEffectHub.comparison.value != null }
            } finally { scenario.close(); store.save(SoundOutput.HEADPHONES, AudioEffectSettings(space = SpaceMode.NATURAL)); JiayinMediaService.stopForSwitch(context) }
            assertNull(AudioEffectHub.comparison.value)
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptyList(); val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { all += n; repeat(n.childCount) { n.getChild(it)?.let(::visit) } }; visit(root); return all
    }
    private fun click(label: String) {
        var found: AccessibilityNodeInfo? = null
        repeat(50) { if (found == null) { found = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }; if (found == null) SystemClock.sleep(100) } }
        val node = checkNotNull(found) { "Missing $label" }; val r = Rect().also(node::getBoundsInScreen); val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) { val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, r.centerX().toFloat(), r.centerY().toFloat(), 0); e.source = InputDevice.SOURCE_TOUCHSCREEN; ins.uiAutomation.injectInputEvent(e, true); e.recycle() }; SystemClock.sleep(250)
    }
}
