package com.eddyizm.tempus.jiayin.playback

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.eddyizm.tempus.jiayin.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class VideoMonitorState(val ready: Boolean = false, val sessions: List<VideoSession> = emptyList(), val status: String = "未连接媒体状态监听")
object VideoResumeHub {
    internal val mutable = MutableStateFlow(VideoMonitorState())
    val state = mutable.asStateFlow()
}
object RecoveryDiagnostics {
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val events = mutable.asStateFlow()
    fun record(event: String) { if (com.eddyizm.tempus.BuildConfig.DEBUG) android.util.Log.d("JiayinRecovery", event); mutable.value = (mutable.value + "${SystemClock.elapsedRealtime()}ms $event").takeLast(150) }
    fun clear() { mutable.value = emptyList() }
}

/** Does not inspect, persist, or forward notifications. Permission is used for media sessions only. */
class VideoResumeListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val manager get() = getSystemService(MediaSessionManager::class.java)
    private val prefs get() = getSharedPreferences("jiayin", Context.MODE_PRIVATE)
    private val controllers = linkedMapOf<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val ids = mutableMapOf<android.media.session.MediaSession.Token, String>()
    private var connected = false
    private var listening = false
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { refresh(it.orEmpty()) }
    private val preferences = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "video-resume-compat" || key == "auto-resume") handler.post { configure() }
    }
    override fun onListenerConnected() { connected = true; prefs.registerOnSharedPreferenceChangeListener(preferences); configure() }
    private fun configure() {
        if (!connected) return
        if (!prefs.getBoolean("video-resume-compat", false) || !prefs.getBoolean("auto-resume", false)) {
            stop(); VideoResumeHub.mutable.value = VideoMonitorState(status = "兼容模式未开启"); return
        }
        try {
            if (!listening) { manager.addOnActiveSessionsChangedListener(changed, ComponentName(this, javaClass), handler); listening = true }
            refresh(manager.getActiveSessions(ComponentName(this, javaClass)))
        } catch (_: SecurityException) { stop(); VideoResumeHub.mutable.value = VideoMonitorState(status = "需授权通知使用权") }
    }
    private fun refresh(active: List<MediaController>) {
        if (!listening) return
        val external = active.filter { it.packageName != packageName }
        val tokens = external.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { token -> controllers.remove(token)?.let { it.first.unregisterCallback(it.second) }; ids.remove(token) }
        external.forEach { controller ->
            if (controller.sessionToken !in controllers) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
                    override fun onSessionDestroyed() {
                        controllers.remove(controller.sessionToken)?.let { it.first.unregisterCallback(it.second) }
                        ids.remove(controller.sessionToken); publish()
                    }
                }
                ids[controller.sessionToken] = UUID.randomUUID().toString()
                controllers[controller.sessionToken] = controller to callback
                controller.registerCallback(callback, handler)
            }
        }
        publish()
    }
    private fun publish() {
        if (!listening) return
        val items = controllers.map { (token, pair) ->
            val c = pair.first
            val local = c.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
            VideoSession(ids.getValue(token), c.packageName == "tv.danmaku.bili" && local,
                when (c.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> ExternalPlayback.PLAYING
                    PlaybackState.STATE_PAUSED -> ExternalPlayback.PAUSED
                    PlaybackState.STATE_STOPPED -> ExternalPlayback.STOPPED
                    else -> ExternalPlayback.UNKNOWN
                })
        }
        VideoResumeHub.mutable.value = VideoMonitorState(true, items, if (items.any { it.bilibili }) "已就绪 · 已检测到 B站媒体状态" else "未检测到可用的 B站媒体状态")
    }
    private fun stop() {
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(changed) }
        listening = false
        controllers.values.forEach { it.first.unregisterCallback(it.second) }; controllers.clear(); ids.clear()
    }
    override fun onListenerDisconnected() {
        connected = false; stop(); prefs.unregisterOnSharedPreferenceChangeListener(preferences)
        VideoResumeHub.mutable.value = VideoMonitorState(status = "媒体状态监听已断开，请检查通知使用权")
    }
    override fun onDestroy() { onListenerDisconnected(); super.onDestroy() }
}
