package com.iptv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionSafetyTest {

    @Test
    fun `remote uncertainty clears only after definitive Cast end`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.block(
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT,
        )
        assertFalse(ProviderConnectionSafety.newConnectionAllowed)

        ProviderConnectionSafety.resolveDefinitiveCastEnd()

        assertTrue(ProviderConnectionSafety.newConnectionAllowed)
    }

    @Test
    fun `active remote owner blocks local connection and process recycle`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.beginRemoteOwnership()
        val local = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
        ProviderConnectionSafety.requireLocalProcessRecovery(local)

        val snapshot = ProviderConnectionSafety.snapshot()

        assertEquals(1, snapshot.activeRemoteOwnerCount)
        assertFalse(snapshot.newConnectionAllowed)
        assertFalse(snapshot.canRecoverLocalProcess)
    }

    @Test
    fun `resolving one of two remote owners keeps gate blocked`() {
        ProviderConnectionSafety.resetForTests()
        val first = ProviderConnectionSafety.beginRemoteOwnership()
        ProviderConnectionSafety.beginRemoteOwnership()

        ProviderConnectionSafety.resolveDefinitiveRemoteOwnership(first)

        assertEquals(1, ProviderConnectionSafety.snapshot().activeRemoteOwnerCount)
        assertFalse(ProviderConnectionSafety.newConnectionAllowed)
    }

    @Test
    fun `remote ownership transfer becomes unresolved receiver state`() {
        ProviderConnectionSafety.resetForTests()
        val token = ProviderConnectionSafety.beginRemoteOwnership()

        ProviderConnectionSafety.transferRemoteOwnershipToUncertainty(token)

        val snapshot = ProviderConnectionSafety.snapshot()
        assertEquals(0, snapshot.activeRemoteOwnerCount)
        assertTrue(snapshot.remoteUncertain)
        assertFalse(snapshot.newConnectionAllowed)
    }

    @Test
    fun `definitive Cast end clears tokens from detached controllers`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.beginRemoteOwnership()
        ProviderConnectionSafety.beginRemoteOwnership()
        ProviderConnectionSafety.block(
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT,
        )

        ProviderConnectionSafety.resolveDefinitiveCastEnd()

        val snapshot = ProviderConnectionSafety.snapshot()
        assertEquals(0, snapshot.activeRemoteOwnerCount)
        assertFalse(snapshot.remoteUncertain)
        assertTrue(snapshot.newConnectionAllowed)
    }

    @Test
    fun `late transfer of a resolved remote token is ignored`() {
        ProviderConnectionSafety.resetForTests()
        val token = ProviderConnectionSafety.beginRemoteOwnership()
        ProviderConnectionSafety.resolveDefinitiveCastEnd()

        ProviderConnectionSafety.transferRemoteOwnershipToUncertainty(token)

        val snapshot = ProviderConnectionSafety.snapshot()
        assertFalse(snapshot.remoteUncertain)
        assertTrue(snapshot.newConnectionAllowed)
    }

    @Test
    fun `unconfirmed native stop is not cleared by Cast end`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.block(
            ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP,
        )

        ProviderConnectionSafety.resolveDefinitiveCastEnd()

        assertFalse(ProviderConnectionSafety.newConnectionAllowed)
        assertEquals(
            ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP,
            ProviderConnectionSafety.currentUncertainty(),
        )
    }

    @Test
    fun `retired native worker completion releases its uncertainty`() {
        ProviderConnectionSafety.resetForTests()
        val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
        assertFalse(ProviderConnectionSafety.newConnectionAllowed)

        ProviderConnectionSafety.resolveDefinitiveLocalStop(token)

        assertTrue(ProviderConnectionSafety.newConnectionAllowed)
    }

    @Test
    fun `one native completion cannot clear a second timed out owner`() {
        ProviderConnectionSafety.resetForTests()
        val first = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
        ProviderConnectionSafety.beginLocalNativeStopUncertainty()

        ProviderConnectionSafety.resolveDefinitiveLocalStop(first)

        assertFalse(ProviderConnectionSafety.newConnectionAllowed)
        assertEquals(
            ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP,
            ProviderConnectionSafety.currentUncertainty(),
        )
    }

    @Test
    fun `token completion never clears an unowned recovery requirement`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.block(ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP)
        val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()

        ProviderConnectionSafety.resolveDefinitiveLocalStop(token)

        assertFalse(ProviderConnectionSafety.newConnectionAllowed)
    }

    @Test
    fun `pending local cleanup blocks but does not request process recovery`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.beginLocalNativeStopUncertainty()

        val snapshot = ProviderConnectionSafety.snapshot()

        assertFalse(snapshot.newConnectionAllowed)
        assertFalse(snapshot.localProcessRecoveryRequired)
        assertFalse(snapshot.canRecoverLocalProcess)
    }

    @Test
    fun `timed out owner requests recovery only after explicit escalation`() {
        ProviderConnectionSafety.resetForTests()
        val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()

        ProviderConnectionSafety.requireLocalProcessRecovery(token)

        val snapshot = ProviderConnectionSafety.snapshot()
        assertTrue(snapshot.localProcessRecoveryRequired)
        assertTrue(snapshot.canRecoverLocalProcess)
    }

    @Test
    fun `remote uncertainty prevents a local process recycle`() {
        ProviderConnectionSafety.resetForTests()
        val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
        ProviderConnectionSafety.requireLocalProcessRecovery(token)
        ProviderConnectionSafety.block(
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT,
        )

        val snapshot = ProviderConnectionSafety.snapshot()

        assertTrue(snapshot.localProcessRecoveryRequired)
        assertFalse(snapshot.canRecoverLocalProcess)
    }

    @Test
    fun `definitive cast end exposes an independently pending local recovery`() {
        ProviderConnectionSafety.resetForTests()
        val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
        ProviderConnectionSafety.requireLocalProcessRecovery(token)
        ProviderConnectionSafety.block(
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT,
        )

        ProviderConnectionSafety.resolveDefinitiveCastEnd()

        val snapshot = ProviderConnectionSafety.snapshot()
        assertFalse(snapshot.remoteUncertain)
        assertTrue(snapshot.canRecoverLocalProcess)
        assertFalse(snapshot.newConnectionAllowed)
    }

    @Test
    fun `drained queue escalation marks every unresolved owner`() {
        ProviderConnectionSafety.resetForTests()
        val first = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
        ProviderConnectionSafety.beginLocalNativeStopUncertainty()

        ProviderConnectionSafety.requireProcessRecoveryForPendingLocalStops()
        ProviderConnectionSafety.resolveDefinitiveLocalStop(first)

        val snapshot = ProviderConnectionSafety.snapshot()
        assertEquals(1, snapshot.localPendingCount)
        assertTrue(snapshot.localProcessRecoveryRequired)
    }

    @Test
    fun `drain escalation after final token resolved is a no-op`() {
        ProviderConnectionSafety.resetForTests()
        val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
        ProviderConnectionSafety.resolveDefinitiveLocalStop(token)

        ProviderConnectionSafety.requireProcessRecoveryForPendingLocalStops()

        assertTrue(ProviderConnectionSafety.snapshot().newConnectionAllowed)
    }

    @Test
    fun `late escalation for a resolved token is ignored`() {
        ProviderConnectionSafety.resetForTests()
        val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()
        ProviderConnectionSafety.resolveDefinitiveLocalStop(token)

        ProviderConnectionSafety.requireLocalProcessRecovery(token)

        assertTrue(ProviderConnectionSafety.snapshot().newConnectionAllowed)
    }

    @Test
    fun `remote uncertainty cannot downgrade native stop uncertainty`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.block(
            ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP,
        )

        ProviderConnectionSafety.block(
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT,
        )

        assertEquals(
            ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP,
            ProviderConnectionSafety.currentUncertainty(),
        )
    }

    @Test
    fun `controller detach preserves active or suspended receiver ownership`() {
        assertEquals(
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT,
            ProviderConnectionSafety.uncertaintyForControllerDetach(
                localQuiesceInFlight = false,
                receiverLoadSubmitted = false,
                sessionSuspended = false,
                receiverOwnsPlayback = true,
            ),
        )
        assertEquals(
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT,
            ProviderConnectionSafety.uncertaintyForControllerDetach(
                localQuiesceInFlight = false,
                receiverLoadSubmitted = false,
                sessionSuspended = true,
                receiverOwnsPlayback = false,
            ),
        )
        assertEquals(
            ProviderConnectionSafety.Uncertainty.REMOTE_LOAD_RESULT,
            ProviderConnectionSafety.uncertaintyForControllerDetach(
                localQuiesceInFlight = false,
                receiverLoadSubmitted = true,
                sessionSuspended = false,
                receiverOwnsPlayback = false,
            ),
        )
    }

    @Test
    fun `controller detach prioritizes unresolved native stop`() {
        assertEquals(
            ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP,
            ProviderConnectionSafety.uncertaintyForControllerDetach(
                localQuiesceInFlight = true,
                receiverLoadSubmitted = true,
                sessionSuspended = true,
                receiverOwnsPlayback = true,
            ),
        )
    }

    @Test
    fun `idle controller detach does not block a new connection`() {
        assertEquals(
            null,
            ProviderConnectionSafety.uncertaintyForControllerDetach(
                localQuiesceInFlight = false,
                receiverLoadSubmitted = false,
                sessionSuspended = false,
                receiverOwnsPlayback = false,
            ),
        )
    }
}
