@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController as PlatformController
import android.media.session.MediaSession as PlatformSession
import android.media.session.PlaybackState as PlatformState
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.App
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MediaSurfaceRecoveryTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun main(action: () -> Unit) = ins.runOnMainSync(action)
    private suspend fun await(label: String, predicate: () -> Boolean) = withTimeout(20000) { while (!predicate()) delay(50); android.util.Log.i("SurfaceCheck", label) }
    private suspend fun fixture(transcode: Boolean = false, block: suspend (JiayinViewModel, JiayinRepository, MediaPipelineTest.MediaServer, MediaController, PlatformController) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val prefs = App.getInstance().preferences
        val oldWifi = prefs.getString("audio_transcode_format_wifi", "raw"); val oldMobile = prefs.getString("audio_transcode_format_mobile", "mp3")
        prefs.edit().putString("audio_transcode_format_wifi", if (transcode) "mp3" else "raw").putString("audio_transcode_format_mobile", if (transcode) "mp3" else "raw").commit()
        MediaPipelineTest.MediaServer().use { server ->
            val repo = JiayinRepository.get(context); repo.login("http://localhost:${server.port}", "fixture", "fixture-password")
            repo.saveLibrary(Library(server.tracks, syncedAt = System.currentTimeMillis())); repo.dao.delete(repo.account, "queue", "active")
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel; var future: ListenableFuture<MediaController>? = null
            try {
                scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
                await("library") { !vm.state.value.loading }
                main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, JiayinMediaService::class.java))).buildAsync(); vm.start(server.tracks) }
                val controller = future!!.get(15, TimeUnit.SECONDS)
                await("playing") { PlaybackHub.state.value.playing }
                val manager = context.getSystemService(NotificationManager::class.java)
                await("session") { manager.activeNotifications.any { it.id == 1001 && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) } }
                @Suppress("DEPRECATION") val token = manager.activeNotifications.first { it.id == 1001 }.notification.extras.getParcelable<PlatformSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
                block(vm, repo, server, controller, PlatformController(context, token))
            } finally {
                main { vm.command("pause"); future?.let { MediaController.releaseFuture(it) }; repo.settings.edit().putBoolean("auto-resume", false).putBoolean("video-resume-compat", false).apply(); VideoResumeHub.mutable.value = VideoMonitorState() }
                scenario.close(); JiayinMediaService.stopForSwitch(context)
                prefs.edit().putString("audio_transcode_format_wifi", oldWifi).putString("audio_transcode_format_mobile", oldMobile).commit()
            }
        }
    }
    @Test(timeout = 100000) fun platformAndMedia3SeekRawWhilePlayingAndPaused() = runBlocking {
        fixture { _, _, server, c, p ->
            await("platform seek action") { (p.playbackState?.actions?.and(PlatformState.ACTION_SEEK_TO) ?: 0L) != 0L }
            assertEquals(30000L, p.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION))
            main { assertTrue(c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)); p.transportControls.seekTo(10000) }
            await("raw position") { PlaybackHub.state.value.queue.positionMs in 10000..12500 }
            main { p.transportControls.pause() }; await("paused") { PlaybackHub.state.value.cause == PauseCause.USER }
            main { c.seekTo(c.currentMediaItemIndex, 20000) }
            await("indexed paused seek") { abs(PlaybackHub.state.value.queue.positionMs - 20000) < 500 }
            delay(300); assertFalse(PlaybackHub.state.value.playing)
            await("system state position") { abs((p.playbackState?.position ?: -1) - 20000) < 500 }
            assertEquals(0, server.downloads.get())
        }
    }
    @Test(timeout = 100000) fun transcodeAbsoluteOffsetAndRapidLatestSeekKeepSession() = runBlocking {
        fixture(true) { _, _, server, c, p ->
            val token = p.sessionToken
            main { p.transportControls.seekTo(12000) }
            await("server offset") { 12 in server.offsets && PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.positionMs in 12000..14500 }
            assertEquals(30000L, p.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION))
            main { c.seekTo(4000); c.seekTo(18000); c.seekTo(8000) }
            await("latest target") { 8 in server.offsets && PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.positionMs in 8000..11000 }
            main { p.transportControls.pause() }; await("paused") { PlaybackHub.state.value.cause == PauseCause.USER }
            main { p.transportControls.seekTo(22000) }
            await("paused server offset") { 22 in server.offsets && abs(PlaybackHub.state.value.queue.positionMs - 22000) < 500 }
            delay(500); assertFalse(PlaybackHub.state.value.playing)
            assertEquals(token, p.sessionToken)
            assertTrue(abs((p.playbackState?.position ?: -1) - 22000) < 1500)
        }
    }

    @Test(timeout = 100000) fun downloadedOfflineItemCanSeekFromSystemPanel() = runBlocking {
        fixture { vm, repo, server, _, p ->
            com.eddyizm.tempus.util.Preferences.setDownloadWifiOnly(false)
            var job: Job? = null
            main { job = vm.download(listOf(server.tracks[0])); com.eddyizm.tempus.util.DownloadUtil.getDownloadManager(context).requirements = androidx.media3.exoplayer.scheduler.Requirements(0) }
            job!!.join()
            await("download complete") { AccountDownloads.downloaded(context, repo.account, server.tracks[0].id) }
            server.online = false
            main { vm.start(listOf(server.tracks[0]), source = "Generated offline") }
            await("offline playback") { PlaybackHub.state.value.queue.entries.size == 1 && PlaybackHub.state.value.playing }
            main { p.transportControls.seekTo(17000) }
            await("offline system seek") { PlaybackHub.state.value.queue.positionMs in 17000..19500 && PlaybackHub.state.value.playing }
        }
    }
    @Test(timeout = 100000) fun pendingOffsetCannotOverrideNextTrackAndGenuineReturnStillResumes() = runBlocking {
        fixture(true) { vm, repo, server, _, p ->
            server.streamDelay = 400
            main { p.transportControls.seekTo(24000); p.transportControls.skipToNext() }
            await("next survives seek") { PlaybackHub.state.value.current?.id == server.tracks[1].id && PlaybackHub.state.value.playing }
            assertTrue(PlaybackHub.state.value.queue.positionMs < 5000)
            repo.settings.edit().putBoolean("auto-resume", true).commit()
            val manager = context.getSystemService(AudioManager::class.java); val focus = competingFocus(AudioManager.AUDIOFOCUS_GAIN)
            try {
                main { manager.requestAudioFocus(focus) }; await("permanent pause") { PlaybackHub.state.value.cause == PauseCause.FOCUS }
                main { vm.foreground() }; delay(300); assertFalse(PlaybackHub.state.value.playing)
                main { vm.foreground(userReturn = true) }; await("actual foreground resumes") { PlaybackHub.state.value.playing }
            } finally { main { manager.abandonAudioFocusRequest(focus) } }
        }
    }
    private fun competingFocus(gain: Int): AudioFocusRequest = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
    @Test(timeout = 100000) fun transientFocusReturnsAutomaticallyButUserPauseCancelsIt() = runBlocking {
        fixture { _, repo, _, _, p ->
            repo.settings.edit().putBoolean("auto-resume", true).commit()
            val manager = context.getSystemService(AudioManager::class.java); val focus = competingFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            try {
                main { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(focus)) }
                await("interrupted") { PlaybackHub.state.value.cause == PauseCause.FOCUS }
                main { manager.abandonAudioFocusRequest(focus) }
                await("automatic focus return") { PlaybackHub.state.value.playing }
                main { manager.requestAudioFocus(focus) }; await("interrupted again") { PlaybackHub.state.value.cause == PauseCause.FOCUS }
                main { p.transportControls.pause() }; await("deliberate pause") { PlaybackHub.state.value.cause == PauseCause.USER }
                main { manager.abandonAudioFocusRequest(focus) }; delay(1400)
                assertFalse(PlaybackHub.state.value.playing)
            } finally { main { manager.abandonAudioFocusRequest(focus) } }
        }
    }
    @Test(timeout = 100000) fun permanentLossUsesOnlyConfirmedVideoPauseAndNeverUnknownOrPermissionLoss() = runBlocking {
        fixture { _, repo, _, _, _ ->
            repo.settings.edit().putBoolean("auto-resume", true).putBoolean("video-resume-compat", true).commit()
            val manager = context.getSystemService(AudioManager::class.java); val focus = competingFocus(AudioManager.AUDIOFOCUS_GAIN)
            fun video(state: ExternalPlayback) { VideoResumeHub.mutable.value = VideoMonitorState(true, listOf(VideoSession("generated-bili", true, state)), "生成会话") }
            try {
                main { video(ExternalPlayback.PLAYING); manager.requestAudioFocus(focus) }
                await("permanent loss") { PlaybackHub.state.value.cause == PauseCause.FOCUS }
                main { video(ExternalPlayback.UNKNOWN) }; delay(1200); assertFalse(PlaybackHub.state.value.playing)
                main { video(ExternalPlayback.PLAYING) }; delay(100)
                main { video(ExternalPlayback.PAUSED) }; delay(300); assertFalse(PlaybackHub.state.value.playing)
                await("confirmed video resume") { PlaybackHub.state.value.playing }
                main { video(ExternalPlayback.PLAYING); manager.requestAudioFocus(focus) }
                await("second loss") { PlaybackHub.state.value.cause == PauseCause.FOCUS }
                main { video(ExternalPlayback.PAUSED); VideoResumeHub.mutable.value = VideoMonitorState() }
                delay(1500); assertFalse(PlaybackHub.state.value.playing)
            } finally { main { manager.abandonAudioFocusRequest(focus) } }
        }
    }
}
