package com.iptv.player.playback.core

import org.junit.Assert.*
import org.junit.Test

class VerifiedCodecPolicyTest {
    private val now = 1_000_000L
    private fun select(evidence: CodecEvidence?, sdk: Int = 28, hardware: Boolean = true, secure: Boolean = false) =
        VerifiedCodecPolicy.select(sdk, hardware, secure, evidence, now)

    @Test fun `one long session cannot count twice but later degradation still revokes it`() {
        val once = CodecEvidence(3, 60, 1, 0, now)
        val sameSession = VerifiedCodecPolicy.observe(once, CodecQueueMode.ASYNC, 1_800, 0, 60_000, true, now, false)
        assertEquals(1, sameSession.asyncGoodSessions)
        val degraded = VerifiedCodecPolicy.observe(sameSession, CodecQueueMode.ASYNC, 1_600, 200, 60_000, true, now, false)
        assertEquals(0, degraded.asyncGoodSessions)
        assertEquals(CodecQueueMode.DEFAULT, select(degraded))
    }

    @Test fun `unknown devices never get an unmeasured queue override`() {
        assertEquals(CodecQueueMode.DEFAULT, select(null))
        assertEquals(CodecQueueMode.DEFAULT, select(CodecEvidence()))
    }
    @Test fun `only three measured bad baseline windows permit a trial`() {
        var evidence = CodecEvidence()
        repeat(2) {
            evidence = VerifiedCodecPolicy.observe(evidence, CodecQueueMode.DEFAULT, 1_700, 100, 60_000, true, now)
            assertEquals(CodecQueueMode.DEFAULT, select(evidence))
        }
        evidence = VerifiedCodecPolicy.observe(evidence, CodecQueueMode.DEFAULT, 1_700, 100, 60_000, true, now)
        assertEquals(CodecQueueMode.ASYNC, select(evidence))
    }
    @Test fun `network stalls rapid zaps and stopped video never qualify`() {
        val prior = CodecEvidence()
        assertEquals(prior, VerifiedCodecPolicy.observe(prior, CodecQueueMode.DEFAULT, 1_700, 200, 60_000, false, now))
        assertEquals(prior, VerifiedCodecPolicy.observe(prior, CodecQueueMode.DEFAULT, 1_700, 200, 30_000, true, now))
        assertEquals(prior, VerifiedCodecPolicy.observe(prior, CodecQueueMode.DEFAULT, 0, 200, 60_000, true, now))
    }
    @Test fun `software secure and modern platform codecs retain their default`() {
        val evidence = CodecEvidence(3, 100, 2, 0, now)
        assertEquals(CodecQueueMode.DEFAULT, select(evidence, sdk = 22))
        assertEquals(CodecQueueMode.DEFAULT, select(evidence, sdk = 31))
        assertEquals(CodecQueueMode.DEFAULT, select(evidence, hardware = false))
        assertEquals(CodecQueueMode.DEFAULT, select(evidence, secure = true))
    }
    @Test fun `improved async observations need two successful sessions`() {
        val first = VerifiedCodecPolicy.observe(CodecEvidence(3, 60), CodecQueueMode.ASYNC, 1_798, 2, 60_000, true, now)
        assertEquals(1, first.asyncGoodSessions)
        val second = VerifiedCodecPolicy.observe(first, CodecQueueMode.ASYNC, 1_798, 2, 60_000, true, now)
        assertEquals(2, second.asyncGoodSessions)
    }
    @Test fun `worse async result and codec failure revoke approval for seven days`() {
        val prior = CodecEvidence(3, 60, 2, 0, now)
        val bad = VerifiedCodecPolicy.observe(prior, CodecQueueMode.ASYNC, 1_700, 100, 60_000, true, now)
        assertEquals(0, bad.asyncGoodSessions)
        assertEquals(CodecQueueMode.DEFAULT, select(bad))
        assertEquals(CodecQueueMode.DEFAULT, select(VerifiedCodecPolicy.failed(prior, now)))
    }
    @Test fun `expired and future dated evidence cannot select a queue`() {
        assertEquals(CodecQueueMode.DEFAULT, select(CodecEvidence(3, 60, 2, 0, now + 1)))
        assertEquals(CodecQueueMode.DEFAULT, VerifiedCodecPolicy.select(28, true, false,
            CodecEvidence(3, 60, 2, 0, now), now + VerifiedCodecPolicy.TTL_MS + 1))
    }
    @Test fun `a healthy baseline clears the trial trigger`() {
        val good = VerifiedCodecPolicy.observe(CodecEvidence(2, 60), CodecQueueMode.DEFAULT, 1_800, 0, 60_000, true, now)
        assertEquals(0, good.baselineBadWindows)
        assertEquals(CodecQueueMode.DEFAULT, select(good))
    }
}
