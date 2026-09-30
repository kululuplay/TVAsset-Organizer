package com.iptv.player.playback.core

import com.iptv.player.playback.core.ProcessRecoveryVerifier.Evidence
import com.iptv.player.playback.core.ProcessRecoveryVerifier.SignalProbe
import com.iptv.player.playback.core.ProcessRecoveryVerifier.State
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessRecoveryVerifierTest {

    private val pkg = "com.iptv.player"

    /** A release build: /proc of the main process is invisible to the recovery process. */
    private fun releaseEvidence(
        tokenAlive: Boolean? = true,
        signalProbe: SignalProbe = SignalProbe.EXISTS,
        managerProcessName: String? = pkg,
        managerUid: Int? = 10123,
    ) = Evidence(
        pid = 3250,
        ownPid = 3428,
        tokenAlive = tokenAlive,
        signalProbe = signalProbe,
        expectedStartTicks = 123_456L,
        observedStartTicks = null,
        managerProcessName = managerProcessName,
        managerUid = managerUid,
        procCommandLine = null,
        procUid = null,
        packageName = pkg,
        ownUid = 10123,
    )

    @Test
    fun releaseBuildWithLiveTokenIsVerifiedEvenWhenProcIsUnreadable() {
        assertEquals(State.VERIFIED, ProcessRecoveryVerifier.decide(releaseEvidence()))
        // Even when the ActivityManager does not list the pid.
        assertEquals(
            State.VERIFIED,
            ProcessRecoveryVerifier.decide(
                releaseEvidence(managerProcessName = null, managerUid = null),
            ),
        )
    }

    @Test
    fun deadTokenMeansGone() {
        assertEquals(State.GONE, ProcessRecoveryVerifier.decide(releaseEvidence(tokenAlive = false)))
        // A dead token wins over a pid that still exists (zombie or reused).
        assertEquals(
            State.GONE,
            ProcessRecoveryVerifier.decide(
                releaseEvidence(tokenAlive = false, signalProbe = SignalProbe.EXISTS),
            ),
        )
    }

    @Test
    fun missingPidMeansGone() {
        assertEquals(
            State.GONE,
            ProcessRecoveryVerifier.decide(releaseEvidence(signalProbe = SignalProbe.NO_SUCH_PROCESS)),
        )
    }

    @Test
    fun withoutTokenAForeignPidMeansGoneButOwnListedPidIsVerified() {
        assertEquals(
            State.GONE,
            ProcessRecoveryVerifier.decide(
                releaseEvidence(tokenAlive = null, signalProbe = SignalProbe.NOT_OURS),
            ),
        )
        assertEquals(
            State.VERIFIED,
            ProcessRecoveryVerifier.decide(releaseEvidence(tokenAlive = null)),
        )
        // Nothing verifiable at all: never kill.
        assertEquals(
            State.INVALID,
            ProcessRecoveryVerifier.decide(
                releaseEvidence(tokenAlive = null, managerProcessName = null, managerUid = null),
            ),
        )
    }

    @Test
    fun liveTokenStillTrustedWhenSignalProbeIsDeniedOrUnavailable() {
        assertEquals(
            State.VERIFIED,
            ProcessRecoveryVerifier.decide(releaseEvidence(signalProbe = SignalProbe.NOT_OURS)),
        )
        assertEquals(
            State.VERIFIED,
            ProcessRecoveryVerifier.decide(releaseEvidence(signalProbe = SignalProbe.UNAVAILABLE)),
        )
    }

    @Test
    fun contradictingIdentityIsInvalid() {
        assertEquals(
            State.INVALID,
            ProcessRecoveryVerifier.decide(
                releaseEvidence(managerProcessName = "$pkg:playback_recovery"),
            ),
        )
        assertEquals(
            State.INVALID,
            ProcessRecoveryVerifier.decide(releaseEvidence(managerUid = 99_000)),
        )
    }

    @Test
    fun debugBuildStartTicksMustMatchWhenReadable() {
        val debug = releaseEvidence().copy(
            observedStartTicks = 123_456L,
            procCommandLine = pkg,
            procUid = 10123,
        )
        assertEquals(State.VERIFIED, ProcessRecoveryVerifier.decide(debug))
        assertEquals(
            State.INVALID,
            ProcessRecoveryVerifier.decide(debug.copy(observedStartTicks = 999L)),
        )
        assertEquals(
            State.INVALID,
            ProcessRecoveryVerifier.decide(debug.copy(procCommandLine = "$pkg:other")),
        )
    }

    @Test
    fun ownOrInvalidPidIsNeverKilled() {
        assertEquals(State.INVALID, ProcessRecoveryVerifier.decide(releaseEvidence().copy(pid = 3428)))
        assertEquals(State.INVALID, ProcessRecoveryVerifier.decide(releaseEvidence().copy(pid = 0)))
        assertEquals(State.INVALID, ProcessRecoveryVerifier.decide(releaseEvidence().copy(pid = -1)))
    }
}
