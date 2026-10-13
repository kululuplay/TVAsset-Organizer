package com.iptv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProviderStartGatePolicyTest {

    @Test
    fun `open gate is ready`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.READY,
            decide(),
        )
    }

    @Test
    fun `ordinary local teardown waits without a fatal error`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.WAIT_FOR_LOCAL_CLEANUP,
            decide(localPendingCount = 1),
        )
    }

    @Test
    fun `proven local hang requests process recovery`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.RECOVER_LOCAL_PROCESS,
            decide(localPendingCount = 1, localRecoveryRequired = true),
        )
    }

    @Test
    fun `local hang asks for recovery before any remote state`() {
        // The recovery launcher itself refuses under remote uncertainty; the
        // caller then forces the gate open. Never a permanent block.
        assertEquals(
            ProviderStartGatePolicy.Decision.RECOVER_LOCAL_PROCESS,
            decide(
                remoteUncertain = true,
                localPendingCount = 1,
                localRecoveryRequired = true,
            ),
        )
    }

    @Test
    fun `remote uncertainty is forced open instead of blocking`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.PROCEED_AFTER_RESET,
            decide(remoteUncertain = true),
        )
    }

    @Test
    fun `active receiver owner is forced open instead of blocking`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.PROCEED_AFTER_RESET,
            decide(activeRemoteOwnerCount = 1),
        )
    }

    @Test
    fun `pending local cleanup under remote uncertainty still waits once`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.WAIT_FOR_LOCAL_CLEANUP,
            decide(remoteUncertain = true, localPendingCount = 1),
        )
    }

    @Test
    fun `no snapshot ever yields a screen level block`() {
        for (remote in listOf(false, true)) {
            for (owners in 0..2) {
                for (pending in 0..2) {
                    for (recovery in listOf(false, true)) {
                        val decision = decide(remote, owners, pending, recovery)
                        assertNotEquals(ProviderStartGatePolicy.Decision.BLOCKED, decision)
                        assertNotEquals(
                            ProviderStartGatePolicy.Decision.BLOCKED_BY_REMOTE_OWNER,
                            decision,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `after the bounded wait a busy queue is forced open`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.PROCEED_AFTER_RESET,
            ProviderStartGatePolicy.decideAfterWait(snapshot(localPendingCount = 1)),
        )
    }

    @Test
    fun `after the bounded wait ready and recovery are unchanged`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.READY,
            ProviderStartGatePolicy.decideAfterWait(snapshot()),
        )
        assertEquals(
            ProviderStartGatePolicy.Decision.RECOVER_LOCAL_PROCESS,
            ProviderStartGatePolicy.decideAfterWait(
                snapshot(localPendingCount = 1, localRecoveryRequired = true),
            ),
        )
    }

    @Test
    fun `one queue wait is about one second`() {
        assertEquals(1_000L, ProviderStartGatePolicy.DRAIN_WAIT_MS)
    }

    private fun decide(
        remoteUncertain: Boolean = false,
        activeRemoteOwnerCount: Int = 0,
        localPendingCount: Int = 0,
        localRecoveryRequired: Boolean = false,
    ): ProviderStartGatePolicy.Decision = ProviderStartGatePolicy.decide(
        snapshot(remoteUncertain, activeRemoteOwnerCount, localPendingCount, localRecoveryRequired),
    )

    private fun snapshot(
        remoteUncertain: Boolean = false,
        activeRemoteOwnerCount: Int = 0,
        localPendingCount: Int = 0,
        localRecoveryRequired: Boolean = false,
    ) = ProviderConnectionSafety.Snapshot(
        remoteUncertain = remoteUncertain,
        activeRemoteOwnerCount = activeRemoteOwnerCount,
        localPendingCount = localPendingCount,
        localProcessRecoveryRequired = localRecoveryRequired,
    )
}
