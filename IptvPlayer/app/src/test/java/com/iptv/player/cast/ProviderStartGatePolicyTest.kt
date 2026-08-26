package com.iptv.player.cast

import org.junit.Assert.assertEquals
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
    fun `remote uncertainty wins over local recovery`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.BLOCKED_BY_REMOTE_OWNER,
            decide(
                remoteUncertain = true,
                localPendingCount = 1,
                localRecoveryRequired = true,
            ),
        )
    }

    @Test
    fun `active receiver owner blocks local playback`() {
        assertEquals(
            ProviderStartGatePolicy.Decision.BLOCKED_BY_REMOTE_OWNER,
            decide(activeRemoteOwnerCount = 1),
        )
    }

    private fun decide(
        remoteUncertain: Boolean = false,
        activeRemoteOwnerCount: Int = 0,
        localPendingCount: Int = 0,
        localRecoveryRequired: Boolean = false,
    ): ProviderStartGatePolicy.Decision = ProviderStartGatePolicy.decide(
        ProviderConnectionSafety.Snapshot(
            remoteUncertain = remoteUncertain,
            activeRemoteOwnerCount = activeRemoteOwnerCount,
            localPendingCount = localPendingCount,
            localProcessRecoveryRequired = localRecoveryRequired,
        ),
    )
}
