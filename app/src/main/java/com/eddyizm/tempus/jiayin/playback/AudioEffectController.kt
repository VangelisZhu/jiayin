@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("DEPRECATION")
package com.eddyizm.tempus.jiayin.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AudioEffectState(val output: SoundOutput = SoundOutput.UNKNOWN, val account: String = "",
    val trackId: String? = null, val resolved: ResolvedAudioEffect = ResolvedAudioEffect(SoundPreset.WARM, "等待歌曲"),
    val status: String = "未播放 · 设置将在起播时应用", val processing: EffectProcessing = EffectProcessing())
object AudioEffectHub {
    internal val mutable = MutableStateFlow(AudioEffectState())
    val state = mutable.asStateFlow()
    internal val comparison = MutableStateFlow<String?>(null)
    fun compare(owner: String, enabled: Boolean) { if (enabled) comparison.value = owner else comparison.compareAndSet(owner, null) }
}

/** AudioTrack's routedDevice, not the list of connected accessories, chooses the output profile. */
class AudioEffectController(private val context: Context, private val repo: JiayinRepository) {
    val processor = JiayinAudioEffectProcessor()
    private val store = AudioEffectStore(repo.settings)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var closed = false
    @Volatile private var audioTrack: AudioTrack? = null
    private var song: Track? = null
    private var output = SoundOutput.UNKNOWN
    private var config: AudioEffectConfig? = null
    private var inputChannels = 0
    private val routing = AudioRouting.OnRoutingChangedListener { source ->
        if (!closed && source === audioTrack) refresh(song)
    }
    private val preferences = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("audio-effects.") == true || key?.startsWith("${repo.account}.audio-song.") == true) refresh(song)
    }
    init {
        repo.settings.registerOnSharedPreferenceChangeListener(preferences)
        scope.launch { processor.status.collect { refresh(song) } }
        scope.launch { AudioEffectHub.comparison.collect { refresh(song) } }
    }
    fun sourceChannels(channels: Int) { inputChannels = channels; refresh(song) }
    fun renderers() = object : DefaultRenderersFactory(context) {
        init { setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON) }
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val sink = DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf(processor))
                .setAudioTrackProvider(object : DefaultAudioSink.AudioTrackProvider {
                    override fun getAudioTrack(c: AudioSink.AudioTrackConfig, attributes: AudioAttributes, session: Int, context: Context?): AudioTrack {
                        // Keep platform spatialization disabled on this dedicated software path.
                        // No AudioTrack recreation is needed when the user toggles our spatial DSP.
                        val attrs = AudioAttributes.Builder().setUsage(attributes.usage).setContentType(attributes.contentType)
                            .setFlags(attributes.flags).setAllowedCapturePolicy(attributes.allowedCapturePolicy)
                            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER).build()
                        val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(c, attrs, session, context)
                        handler.post {
                            if (!closed) {
                                audioTrack?.let { runCatching { it.removeOnRoutingChangedListener(routing) } }
                                audioTrack = track
                                runCatching { track.addOnRoutingChangedListener(routing, handler) }
                                refresh(song)
                            }
                        }
                        return track
                    }
                }).build()
            return object : ForwardingAudioSink(sink) {
                override fun supportsFormat(format: Format) = format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)
                override fun getFormatSupport(format: Format) = if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
            }
        }
    }
    fun refresh(track: Track?) {
        if (closed || !repo.currentSession()) return
        val songChanged = song?.id != track?.id
        song = track
        val device = runCatching { audioTrack?.routedDevice }.getOrNull()
        if (device != null) output = outputFor(device.type)
        val s = store.settings(output)
        val resolved = AudioEffectResolver.resolve(s, track?.genreNames().orEmpty(), store.song(repo.account, track?.id))
        val comparing = AudioEffectHub.comparison.value != null
        val processing = processor.status.value
        val multi = inputChannels > 2 || processing.format.channels > 2
        val next = AudioEffectResolver.config(s, resolved, output, comparing).let { if (multi) it.copy(enabled = false) else it }
        if (config != next) { config = next; processor.configure(next, songChanged) }
        val status = when {
            processing.error != null -> processing.error
            !s.enabled -> "已关闭"
            output == SoundOutput.REMOTE -> "当前音效仅适用于本机播放"
            multi -> "多声道内容，音效未应用"
            track == null || processing.format.rate == 0 -> "等待播放，尚未应用"
            processing.format.encoding != C.ENCODING_PCM_16BIT -> "当前音频格式不支持，音效未应用"
            processing.applied != next && runCatching { audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(true) -> "已保存 · 恢复播放时应用"
            processing.applied != next -> "正在平滑切换音效"
            comparing -> "原声对比 · 家音音效已旁路"
            processing.format.channels == 1 -> "已生效 · 单声道内容，空间效果未应用"
            output == SoundOutput.UNKNOWN -> "音色已生效 · 等待实际输出，空间效果未应用"
            else -> "已生效"
        }
        AudioEffectHub.mutable.value = AudioEffectState(output, repo.account, track?.id, resolved, status, processing)
    }
    fun close() {
        closed = true
        repo.settings.unregisterOnSharedPreferenceChangeListener(preferences)
        audioTrack?.let { runCatching { it.removeOnRoutingChangedListener(routing) } }; audioTrack = null
        scope.cancel(); processor.close()
        AudioEffectHub.comparison.value = null; AudioEffectHub.mutable.value = AudioEffectState()
    }
    companion object {
        fun outputFor(type: Int): SoundOutput = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> SoundOutput.HEADPHONES
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> SoundOutput.SPEAKER
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> SoundOutput.REMOTE
            else -> SoundOutput.OTHER
        }
    }
}
