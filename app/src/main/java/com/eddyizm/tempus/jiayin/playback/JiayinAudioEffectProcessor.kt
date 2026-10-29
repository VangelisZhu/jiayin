@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.eddyizm.tempus.jiayin.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

data class EffectFormat(val rate: Int = 0, val channels: Int = 0, val encoding: Int = 0)
data class EffectProcessing(val format: EffectFormat = EffectFormat(), val applied: AudioEffectConfig? = null, val error: String? = null)

/** Lives exactly as long as its service/player. Coefficients and buffers are prepared off-thread. */
class JiayinAudioEffectProcessor : BaseAudioProcessor() {
    private data class Request(val config: AudioEffectConfig = AudioEffectConfig(), val milliseconds: Int = 200)
    private data class Ready(val format: EffectFormat, val request: Request, val dsp: AudioEffectDsp)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = MutableStateFlow(Request())
    private val formats = MutableStateFlow(EffectFormat())
    private val prepared = AtomicReference<Ready?>()
    private val mutable = MutableStateFlow(EffectProcessing())
    val status = mutable.asStateFlow()
    private var current: Ready? = null
    private var incoming: Ready? = null
    private var progress = 0
    private var fadeFrames = 1
    private val before = DoubleArray(2)
    private val after = DoubleArray(2)
    @Volatile var inputFrames: Long = 0; private set
    @Volatile var lastInputMs: Long = 0; private set
    private var failed = false
    init {
        scope.launch {
            combine(requests, formats) { request, format -> request to format }.collectLatest { (request, format) ->
                if (format.rate > 0 && format.channels in 1..2 && format.encoding == C.ENCODING_PCM_16BIT) {
                    val dsp = AudioEffectDsp(request.config, format.rate, format.channels)
                    ensureActive()
                    if (requests.value == request && formats.value == format) prepared.set(Ready(format, request, dsp))
                }
            }
        }
    }
    fun configure(config: AudioEffectConfig, automatic: Boolean = false) { requests.value = Request(config, if (automatic) 500 else 200) }
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        formats.value = EffectFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, inputAudioFormat.encoding)
        // Active even when disabled: toggling never re-prepares the player or timeline.
        return inputAudioFormat
    }
    override fun onFlush() {
        current?.dsp?.clear(); incoming?.dsp?.clear()
        if (current?.format != formats.value) current = null
        if (incoming?.format != formats.value) incoming = null
        progress = 0; failed = false
        mutable.value = EffectProcessing(formats.value, current?.request?.config)
    }
    override fun onReset() { current = null; incoming = null; prepared.set(null); formats.value = EffectFormat(); mutable.value = EffectProcessing() }
    fun close() { scope.cancel(); prepared.set(null) }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val format = formats.value
        inputFrames += inputBuffer.remaining() / (format.channels.coerceAtLeast(1) * 2)
        lastInputMs = android.os.SystemClock.elapsedRealtime()
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        if (format.channels !in 1..2 || format.encoding != C.ENCODING_PCM_16BIT || failed) {
            output.put(inputBuffer); output.flip(); return
        }
        if (incoming == null) {
            val ready = prepared.getAndSet(null)
            if (ready != null && ready.format == format && ready.request == requests.value) {
                if (current?.request?.config != ready.request.config) {
                    incoming = ready; progress = 0; fadeFrames = (format.rate * ready.request.milliseconds / 1000).coerceAtLeast(1)
                }
            }
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        val start = inputBuffer.position()
        try {
            if (incoming == null && (current == null || current!!.dsp.bypass)) output.put(inputBuffer)
            else while (inputBuffer.remaining() >= format.channels * 2) {
                val l = inputBuffer.short.toDouble() / 32768
                val r = if (format.channels == 2) inputBuffer.short.toDouble() / 32768 else l
                current?.dsp?.frame(l, r, before) ?: run { before[0] = l; before[1] = r }
                val next = incoming
                if (next != null) {
                    next.dsp.frame(l, r, after)
                    val mix = (++progress).toDouble() / fadeFrames
                    before[0] += (after[0] - before[0]) * mix; before[1] += (after[1] - before[1]) * mix
                    if (progress >= fadeFrames) {
                        current = next; incoming = null
                        mutable.value = EffectProcessing(format, next.request.config)
                    }
                }
                output.putShort(AudioEffectDsp.pcm16(before[0]))
                if (format.channels == 2) output.putShort(AudioEffectDsp.pcm16(before[1]))
            }
            // AudioSink supplies complete PCM frames; preserve any unexpected tail defensively.
            if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        } catch (_: Exception) {
            failed = true; current = null; incoming = null; prepared.set(null)
            inputBuffer.position(start); output.clear(); output.put(inputBuffer)
            mutable.value = EffectProcessing(format, error = "音效处理异常，已安全关闭本次处理")
        }
        output.flip()
    }
}
