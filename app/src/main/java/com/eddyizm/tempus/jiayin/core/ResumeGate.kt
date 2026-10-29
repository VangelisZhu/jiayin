package com.eddyizm.tempus.jiayin.core

enum class FocusInterruption { TRANSIENT, PERMANENT }
enum class ExternalPlayback { PLAYING, PAUSED, STOPPED, UNKNOWN }
data class VideoSession(val id: String, val bilibili: Boolean, val state: ExternalPlayback)

/** Ephemeral eligibility. No account/song names or external metadata are retained. */
class ResumeGate {
    var serial = 0L; private set
    var interruption: FocusInterruption? = null; private set
    var interruptedAt: Long = 0; private set
    var eligible = false; private set
    private var session: String? = null
    private var bindingUntil = 0L
    private var stoppedAt: Long? = null
    private var lastWasPlaying = false
    private var attempted = false
    fun arm(kind: FocusInterruption, wasPlaying: Boolean, now: Long) {
        if (!eligible && !wasPlaying) return
        if (!eligible) { serial++; eligible = true; interruptedAt = now; bindingUntil = now + 2000; attempted = false }
        interruption = kind
    }
    fun cancel() { serial++; eligible = false; interruption = null; clearVideo() }
    fun clearVideo() { session = null; bindingUntil = 0; stoppedAt = null; lastWasPlaying = false; attempted = false }
    fun observe(sessions: List<VideoSession>, now: Long): Long? {
        if (!eligible || attempted) return null
        if (session == null) {
            val playing = sessions.filter { it.state == ExternalPlayback.PLAYING }
            if (now <= bindingUntil && playing.size == 1 && playing.single().bilibili) {
                session = playing.single().id; lastWasPlaying = true
            }
            return null
        }
        val video = sessions.find { it.id == session }
        if (video == null || sessions.any { it.id != session && it.state != ExternalPlayback.PAUSED && it.state != ExternalPlayback.STOPPED }) {
            // Disappearance or competing/unknown sessions invalidate this association.
            clearVideo(); return null
        }
        when (video.state) {
            ExternalPlayback.PLAYING -> { lastWasPlaying = true; stoppedAt = null }
            ExternalPlayback.UNKNOWN -> { lastWasPlaying = false; stoppedAt = null }
            ExternalPlayback.PAUSED, ExternalPlayback.STOPPED -> {
                if (lastWasPlaying && stoppedAt == null) stoppedAt = now
                return stoppedAt?.plus(1000)
            }
        }
        return null
    }
    fun consumeStandard(): Boolean = eligible.also { if (it) cancel() }
    fun consumeVideo(expected: Long, now: Long): Boolean {
        if (!eligible || attempted || expected != serial || stoppedAt?.let { now >= it + 1000 } != true) return false
        attempted = true; cancel(); return true
    }
}
