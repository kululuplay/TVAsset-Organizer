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
    fun `token completion never clears a legacy unresolved owner`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.block(ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP)
        val token = ProviderConnectionSafety.beginLocalNativeStopUncertainty()

        ProviderConnectionSafety.resolveDefinitiveLocalStop(token)

        assertFalse(ProviderConnectionSafety.newConnectionAllowed)
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
