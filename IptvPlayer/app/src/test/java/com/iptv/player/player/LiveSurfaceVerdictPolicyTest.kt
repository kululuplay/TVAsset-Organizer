package com.iptv.player.player

import com.iptv.player.player.LiveSurfaceVerdictPolicy.Trust
import com.iptv.player.player.LiveSurfaceVerdictPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSurfaceVerdictPolicyTest {

    private fun frames(total: Long, lastSecond: Int) =
        NativeFrameCadence.Snapshot(total = total, lastSecond = lastSecond, lastAgeMs = 40L)

    @Test
    fun `customer case - blind PixelCopy on a stick without another video stage accepts native frames`() {
        // API 25 constrained Amlogic stick, 1080i H.264, software HD withheld:
        // no remote rule, no fallback stage, PixelCopy never saw a healthy frame.
        val trust = LiveSurfaceVerdictPolicy.trustOf(null)
        val advisory = LiveSurfaceVerdictPolicy.pixelsAdvisory(trust, fallbackAvailable = false, provenThisStream = false)
        val live = LiveSurfaceVerdictPolicy.nativeLive(frames(total = 30, lastSecond = 24), ready = true)
        assertTrue(advisory)
        assertTrue(live)
        assertTrue(LiveSurfaceVerdictPolicy.earlyAccept(advisory, live, sinceFirstFrameMs = 1_000L, greenSeen = false))
        // A zeroed-YUV readback classifies as green: wait out the settling window.
        assertFalse(LiveSurfaceVerdictPolicy.earlyAccept(advisory, live, sinceFirstFrameMs = 1_000L, greenSeen = true))
        assertTrue(LiveSurfaceVerdictPolicy.earlyAccept(advisory, live, sinceFirstFrameMs = 3_000L, greenSeen = true))
    }

    @Test
    fun `pixels are advisory only at the dead end or under remote trust and never once proven`() {
        assertFalse(LiveSurfaceVerdictPolicy.pixelsAdvisory(Trust.STRICT, fallbackAvailable = false, provenThisStream = false))
        assertFalse(LiveSurfaceVerdictPolicy.pixelsAdvisory(Trust.STRICT, fallbackAvailable = true, provenThisStream = false))
        assertFalse(LiveSurfaceVerdictPolicy.pixelsAdvisory(Trust.DEAD_END_ONLY, fallbackAvailable = true, provenThisStream = false))
        assertTrue(LiveSurfaceVerdictPolicy.pixelsAdvisory(Trust.DEAD_END_ONLY, fallbackAvailable = false, provenThisStream = false))
        assertTrue(LiveSurfaceVerdictPolicy.pixelsAdvisory(Trust.ALWAYS, fallbackAvailable = true, provenThisStream = false))
        assertTrue(LiveSurfaceVerdictPolicy.pixelsAdvisory(Trust.ALWAYS, fallbackAvailable = false, provenThisStream = false))
        for (trust in Trust.entries) {
            for (fallback in listOf(true, false)) {
                assertFalse(LiveSurfaceVerdictPolicy.pixelsAdvisory(trust, fallback, provenThisStream = true))
            }
        }
    }

    @Test
    fun `gate verdicts stay authoritative when strict and accept only live unverified video`() {
        assertEquals(Verdict.ACCEPT_NATIVE, LiveSurfaceVerdictPolicy.onGateVerdict(advisory = true, live = true, verified = false))
        assertEquals(Verdict.IGNORE, LiveSurfaceVerdictPolicy.onGateVerdict(advisory = true, live = false, verified = false))
        assertEquals(Verdict.IGNORE, LiveSurfaceVerdictPolicy.onGateVerdict(advisory = true, live = true, verified = true))
        assertEquals(Verdict.INVALID, LiveSurfaceVerdictPolicy.onGateVerdict(advisory = false, live = true, verified = false))
        assertEquals(Verdict.INVALID, LiveSurfaceVerdictPolicy.onGateVerdict(advisory = false, live = false, verified = false))
    }

    @Test
    fun `deadline rejects stalls and trickles and defers only while rebuffering`() {
        fun deadline(advisory: Boolean, snapshot: NativeFrameCadence.Snapshot, ready: Boolean, sinceMs: Long) =
            LiveSurfaceVerdictPolicy.onDeadline(
                advisory = advisory,
                live = LiveSurfaceVerdictPolicy.nativeLive(snapshot, ready),
                ready = ready,
                sinceFirstFrameMs = sinceMs,
            )
        // Xiaomi MiTV interlaced stall: ~5 frames, then nothing.
        assertEquals(Verdict.INVALID, deadline(true, frames(total = 5, lastSecond = 0), ready = true, sinceMs = 9_000L))
        // 1 fps trickle after a long run is not live video.
        assertEquals(Verdict.INVALID, deadline(true, frames(total = 200, lastSecond = 1), ready = true, sinceMs = 9_000L))
        // Rebuffer exactly at the deadline: recheck, but never past 15 s.
        assertEquals(Verdict.DEFER, deadline(true, frames(total = 200, lastSecond = 0), ready = false, sinceMs = 9_000L))
        assertEquals(Verdict.DEFER, deadline(true, frames(total = 200, lastSecond = 0), ready = false, sinceMs = 14_999L))
        assertEquals(Verdict.INVALID, deadline(true, frames(total = 200, lastSecond = 0), ready = false, sinceMs = 15_000L))
        // Proven pixels (or a strict trust) make the deadline authoritative again.
        val proven = LiveSurfaceVerdictPolicy.pixelsAdvisory(Trust.DEAD_END_ONLY, fallbackAvailable = false, provenThisStream = true)
        assertEquals(Verdict.INVALID, deadline(proven, frames(total = 225, lastSecond = 25), ready = true, sinceMs = 9_000L))
        assertEquals(Verdict.INVALID, deadline(false, frames(total = 225, lastSecond = 25), ready = false, sinceMs = 9_000L))
        // Remote trust on a route that still has a fallback.
        val trusted = LiveSurfaceVerdictPolicy.pixelsAdvisory(Trust.ALWAYS, fallbackAvailable = true, provenThisStream = false)
        assertEquals(Verdict.ACCEPT_NATIVE, deadline(trusted, frames(total = 225, lastSecond = 25), ready = true, sinceMs = 9_000L))
    }

    @Test
    fun `native liveness needs READY plus total and last-second frame floors`() {
        assertFalse(LiveSurfaceVerdictPolicy.nativeLive(frames(total = 23, lastSecond = 23), ready = true))
        assertTrue(LiveSurfaceVerdictPolicy.nativeLive(frames(total = 24, lastSecond = 8), ready = true))
        assertFalse(LiveSurfaceVerdictPolicy.nativeLive(frames(total = 500, lastSecond = 7), ready = true))
        assertFalse(LiveSurfaceVerdictPolicy.nativeLive(frames(total = 500, lastSecond = 25), ready = false))
    }

    @Test
    fun `early accept waits exactly one second, or three after a green readback`() {
        assertFalse(LiveSurfaceVerdictPolicy.earlyAccept(true, true, sinceFirstFrameMs = 999L, greenSeen = false))
        assertTrue(LiveSurfaceVerdictPolicy.earlyAccept(true, true, sinceFirstFrameMs = 1_000L, greenSeen = false))
        assertFalse(LiveSurfaceVerdictPolicy.earlyAccept(true, true, sinceFirstFrameMs = 2_999L, greenSeen = true))
        assertTrue(LiveSurfaceVerdictPolicy.earlyAccept(true, true, sinceFirstFrameMs = 3_000L, greenSeen = true))
        assertFalse(LiveSurfaceVerdictPolicy.earlyAccept(advisory = false, live = true, sinceFirstFrameMs = 60_000L, greenSeen = false))
        assertFalse(LiveSurfaceVerdictPolicy.earlyAccept(advisory = true, live = false, sinceFirstFrameMs = 60_000L, greenSeen = false))
    }

    @Test
    fun `inline copy budget applies only while pixels are advisory`() {
        fun budget(trust: Trust, fallbackAvailable: Boolean, proven: Boolean, constrained: Boolean) =
            LiveSurfaceVerdictPolicy.inlineCopyBudgetMs(
                advisory = LiveSurfaceVerdictPolicy.pixelsAdvisory(trust, fallbackAvailable, proven),
                constrainedDevice = constrained,
            )
        // Customer stick: constrained, no other video stage.
        assertEquals(1_500L, budget(Trust.DEAD_END_ONLY, fallbackAvailable = false, proven = false, constrained = true))
        assertEquals(4_000L, budget(Trust.ALWAYS, fallbackAvailable = true, proven = false, constrained = false))
        // A constrained stick whose SD or light-codec stream still reaches VLC:
        // slow copies are classified as in 1.5.98, so real green is still caught.
        assertEquals(Long.MAX_VALUE, budget(Trust.DEAD_END_ONLY, fallbackAvailable = true, proven = false, constrained = true))
        // nativeFrameTrust=false and proven pixels: 1.5.98 monitor, no budget.
        assertEquals(Long.MAX_VALUE, budget(Trust.STRICT, fallbackAvailable = false, proven = false, constrained = true))
        assertEquals(Long.MAX_VALUE, budget(Trust.ALWAYS, fallbackAvailable = false, proven = true, constrained = true))
    }

    @Test
    fun `slow inline copy is no proof and accepts only what the early-accept poll would`() {
        val live = LiveSurfaceVerdictPolicy.nativeLive(frames(total = 40, lastSecond = 25), ready = true)
        assertEquals(
            Verdict.ACCEPT_NATIVE,
            LiveSurfaceVerdictPolicy.onSlowInlineCopy(advisory = true, live = live, sinceFirstFrameMs = 1_620L, greenSeen = false),
        )
        // Xiaomi MiTV interlaced stall: ~5 frames, then none. Not accepted.
        val stalled = LiveSurfaceVerdictPolicy.nativeLive(frames(total = 5, lastSecond = 0), ready = true)
        assertEquals(
            Verdict.IGNORE,
            LiveSurfaceVerdictPolicy.onSlowInlineCopy(advisory = true, live = stalled, sinceFirstFrameMs = 1_620L, greenSeen = false),
        )
        // Authoritative pixels never accept on slowness alone.
        assertEquals(
            Verdict.IGNORE,
            LiveSurfaceVerdictPolicy.onSlowInlineCopy(advisory = false, live = live, sinceFirstFrameMs = 1_620L, greenSeen = false),
        )
        // An earlier green readback keeps the three-second settling wait.
        assertEquals(
            Verdict.IGNORE,
            LiveSurfaceVerdictPolicy.onSlowInlineCopy(advisory = true, live = live, sinceFirstFrameMs = 2_999L, greenSeen = true),
        )
        assertEquals(
            Verdict.ACCEPT_NATIVE,
            LiveSurfaceVerdictPolicy.onSlowInlineCopy(advisory = true, live = live, sinceFirstFrameMs = 3_000L, greenSeen = true),
        )
    }

    @Test
    fun `remote override maps false to strict, unset to dead end only and true to always`() {
        assertEquals(Trust.STRICT, LiveSurfaceVerdictPolicy.trustOf(false))
        assertEquals(Trust.DEAD_END_ONLY, LiveSurfaceVerdictPolicy.trustOf(null))
        assertEquals(Trust.ALWAYS, LiveSurfaceVerdictPolicy.trustOf(true))
    }

    @Test
    fun `Fire TV defaults to always-advisory pixels, other devices to the dead-end rule, a remote rule wins`() {
        assertEquals(Trust.ALWAYS, LiveSurfaceVerdictPolicy.defaultTrust("Amazon"))
        assertEquals(Trust.ALWAYS, LiveSurfaceVerdictPolicy.defaultTrust(" amazon "))
        assertEquals(Trust.DEAD_END_ONLY, LiveSurfaceVerdictPolicy.defaultTrust("Xiaomi"))
        assertEquals(Trust.DEAD_END_ONLY, LiveSurfaceVerdictPolicy.defaultTrust(""))
        assertEquals(Trust.DEAD_END_ONLY, LiveSurfaceVerdictPolicy.defaultTrust(null))
        assertEquals(Trust.ALWAYS, LiveSurfaceVerdictPolicy.resolveTrust(null, "Amazon"))
        assertEquals(Trust.STRICT, LiveSurfaceVerdictPolicy.resolveTrust(false, "Amazon"))
        assertEquals(Trust.ALWAYS, LiveSurfaceVerdictPolicy.resolveTrust(true, "Xiaomi"))
        assertEquals(Trust.DEAD_END_ONLY, LiveSurfaceVerdictPolicy.resolveTrust(null, "NVIDIA"))
    }

    @Test
    fun `Fire TV stick with a VLC fallback keeps pixel verdicts advisory until proven`() {
        // 9 Oct 2026: the same Fire TV sticks play the channels in other apps. Under
        // the 1.5.99 dead-end rule the VLC fallback stage made the blind PixelCopy
        // authoritative and moved every channel down the ladder; the default must not.
        val trust = LiveSurfaceVerdictPolicy.resolveTrust(null, "Amazon")
        assertTrue(LiveSurfaceVerdictPolicy.pixelsAdvisory(trust, fallbackAvailable = true, provenThisStream = false))
        assertFalse(LiveSurfaceVerdictPolicy.pixelsAdvisory(trust, fallbackAvailable = true, provenThisStream = true))
        assertEquals("Fire TV default", LiveSurfaceVerdictPolicy.trustReason(null, trust))
        assertEquals("remote nativeFrameTrust", LiveSurfaceVerdictPolicy.trustReason(true, Trust.ALWAYS))
        assertEquals("remote nativeFrameTrust", LiveSurfaceVerdictPolicy.trustReason(false, Trust.STRICT))
        assertEquals("no alternative video stage", LiveSurfaceVerdictPolicy.trustReason(null, Trust.DEAD_END_ONLY))
    }
}
