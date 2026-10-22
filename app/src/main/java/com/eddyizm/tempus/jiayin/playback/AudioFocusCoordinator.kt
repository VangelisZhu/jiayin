package com.eddyizm.tempus.jiayin.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Only focus owner: ExoPlayer's automatic audio focus handling is disabled. */
class AudioFocusCoordinator(context: Context, private val event: (Int) -> Unit) : AudioManager.OnAudioFocusChangeListener {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var owned = false
    private var activeRequest = false
    private val focus = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(this, main).build() else null
    @Suppress("DEPRECATION") fun request(): Boolean {
        if (owned) return true
        activeRequest = true
        val granted = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(focus!!) else manager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        owned = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!owned) activeRequest = false
        return owned
    }
    override fun onAudioFocusChange(change: Int) {
        if (!activeRequest) return
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) owned = false
        else if (change == AudioManager.AUDIOFOCUS_GAIN) owned = true
        if (change == AudioManager.AUDIOFOCUS_LOSS) activeRequest = false
        event(change)
    }
    @Suppress("DEPRECATION") fun abandon() {
        owned = false
        activeRequest = false
        if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(focus!!) else manager.abandonAudioFocus(this)
    }
}
