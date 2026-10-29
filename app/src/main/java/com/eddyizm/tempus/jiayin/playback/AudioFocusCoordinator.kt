package com.eddyizm.tempus.jiayin.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Only focus owner: ExoPlayer's automatic audio focus handling is disabled. */
class AudioFocusCoordinator(context: Context, private val event: (Int) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var owned = false
    private var activeRequest = false
    private var generation = 0L
    private var focus: AudioFocusRequest? = null
    private var listener: AudioManager.OnAudioFocusChangeListener? = null
    @Suppress("DEPRECATION") fun request(): Boolean {
        if (owned) return true
        abandon()
        val token = generation
        val next = AudioManager.OnAudioFocusChangeListener { change -> if (token == generation) changed(change) }
        listener = next
        focus = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(next, main).build() else null
        activeRequest = true
        val granted = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(focus!!) else manager.requestAudioFocus(next, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        owned = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!owned) activeRequest = false
        return owned
    }
    private fun changed(change: Int) {
        if (!activeRequest) return
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) owned = false
        else if (change == AudioManager.AUDIOFOCUS_GAIN) owned = true
        if (change == AudioManager.AUDIOFOCUS_LOSS) activeRequest = false
        event(change)
    }
    @Suppress("DEPRECATION") fun abandon() {
        generation++
        owned = false
        activeRequest = false
        if (Build.VERSION.SDK_INT >= 26) focus?.let { manager.abandonAudioFocusRequest(it) } else listener?.let { manager.abandonAudioFocus(it) }
        focus = null; listener = null
    }
}
