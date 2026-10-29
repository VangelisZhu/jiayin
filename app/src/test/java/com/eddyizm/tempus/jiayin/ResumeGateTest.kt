package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import org.junit.Assert.*
import org.junit.Test

class ResumeGateTest {
    private fun video(state: ExternalPlayback, id: String = "b") = VideoSession(id, true, state)
    @Test fun inactiveMusicNeverAcquiresResumeEligibility() {
        val gate = ResumeGate(); gate.arm(FocusInterruption.TRANSIENT, false, 0)
        assertFalse(gate.consumeStandard()); assertNull(gate.observe(listOf(video(ExternalPlayback.PLAYING)), 10))
    }
    @Test fun transientAndPermanentAreDistinctAndStandardIsOneShot() {
        val gate = ResumeGate(); gate.arm(FocusInterruption.TRANSIENT, true, 0)
        assertEquals(FocusInterruption.TRANSIENT, gate.interruption)
        gate.arm(FocusInterruption.PERMANENT, false, 50)
        assertEquals(FocusInterruption.PERMANENT, gate.interruption)
        assertTrue(gate.consumeStandard()); assertFalse(gate.consumeStandard())
    }
    @Test fun pausedVideoNeedsOneSecondAndSameTicket() {
        val gate = ResumeGate(); gate.arm(FocusInterruption.PERMANENT, true, 0)
        gate.observe(listOf(video(ExternalPlayback.PLAYING)), 1)
        assertEquals(1100L, gate.observe(listOf(video(ExternalPlayback.PAUSED)), 100))
        val ticket = gate.serial
        assertFalse(gate.consumeVideo(ticket, 1099)); assertTrue(gate.consumeVideo(ticket, 1100)); assertFalse(gate.consumeVideo(ticket, 1200))
    }
    @Test fun cancellationAndNewQueueInvalidateLateCallbacks() {
        val gate = ResumeGate(); gate.arm(FocusInterruption.PERMANENT, true, 0)
        gate.observe(listOf(video(ExternalPlayback.PLAYING)), 1); gate.observe(listOf(video(ExternalPlayback.PAUSED)), 10)
        val ticket = gate.serial; gate.cancel(); gate.arm(FocusInterruption.TRANSIENT, true, 100)
        assertFalse(gate.consumeVideo(ticket, 5000))
    }
    @Test fun bufferingAndMissingAndReplacementSessionsDoNotResume() {
        for (replacement in listOf(emptyList(), listOf(video(ExternalPlayback.UNKNOWN)), listOf(video(ExternalPlayback.PAUSED, "new")))) {
            val gate = ResumeGate(); gate.arm(FocusInterruption.PERMANENT, true, 0)
            gate.observe(listOf(video(ExternalPlayback.PLAYING)), 1); gate.observe(listOf(video(ExternalPlayback.PAUSED)), 10)
            val ticket = gate.serial; gate.observe(replacement, 200)
            assertFalse(gate.consumeVideo(ticket, 2000))
        }
    }
    @Test fun competingPlaybackAndLateOrNonBiliSessionsCannotClaimInterruption() {
        val gate = ResumeGate(); gate.arm(FocusInterruption.PERMANENT, true, 0)
        gate.observe(listOf(video(ExternalPlayback.PLAYING), VideoSession("x", false, ExternalPlayback.PLAYING)), 1)
        assertNull(gate.observe(listOf(video(ExternalPlayback.PAUSED)), 100))
        gate.observe(listOf(video(ExternalPlayback.PLAYING)), 2500)
        assertNull(gate.observe(listOf(video(ExternalPlayback.STOPPED)), 2600))
        assertFalse(gate.consumeVideo(gate.serial, 4000))
    }
    @Test fun replayDuringDebounceRestartsThePauseWindow() {
        val gate = ResumeGate(); gate.arm(FocusInterruption.PERMANENT, true, 0)
        gate.observe(listOf(video(ExternalPlayback.PLAYING)), 1); gate.observe(listOf(video(ExternalPlayback.PAUSED)), 10)
        assertNull(gate.observe(listOf(video(ExternalPlayback.PLAYING)), 900))
        assertFalse(gate.consumeVideo(gate.serial, 1010))
        assertEquals(2200L, gate.observe(listOf(video(ExternalPlayback.STOPPED)), 1200))
    }
    @Test fun permissionLossClearsVideoButRetainsStandardRecovery() {
        val gate = ResumeGate(); gate.arm(FocusInterruption.TRANSIENT, true, 0)
        gate.observe(listOf(video(ExternalPlayback.PLAYING)), 1); gate.observe(listOf(video(ExternalPlayback.PAUSED)), 10)
        gate.clearVideo(); assertFalse(gate.consumeVideo(gate.serial, 2000)); assertTrue(gate.consumeStandard())
    }
}
