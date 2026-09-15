package com.iptv.player.player

import com.iptv.player.cast.ProviderConnectionSafety
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class BoundedPlaybackWaitTest {
    @After fun clean() { ProviderConnectionSafety.resetForTests() }

    @Test fun `stuck cleanup is fail closed even if a late queue marker returns`() {
        ProviderConnectionSafety.resetForTests()
        var starts = 0
        var errors = 0
        val wait = BoundedPlaybackWait(
            onComplete = { starts++ },
            onTimeout = {
                ProviderConnectionSafety.block(ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP)
                errors++
            },
        )
        wait.timeout()
        wait.complete()
        wait.timeout()
        assertEquals(0, starts)
        assertEquals(1, errors)
        assertFalse(ProviderConnectionSafety.newConnectionAllowed)
        assertTrue(ProviderConnectionSafety.snapshot().canRecoverLocalProcess)
    }

    @Test fun `ordinary completed cleanup cannot later poison ownership`() {
        var starts = 0
        var errors = 0
        val wait = BoundedPlaybackWait({ starts++ }, { errors++ })
        wait.complete()
        wait.complete()
        wait.timeout()
        assertEquals(1, starts)
        assertEquals(0, errors)
    }

    @Test fun `local timeout cannot override remote owner protection`() {
        ProviderConnectionSafety.resetForTests()
        ProviderConnectionSafety.beginRemoteOwnership()
        val wait = BoundedPlaybackWait({}, {
            ProviderConnectionSafety.block(ProviderConnectionSafety.Uncertainty.LOCAL_NATIVE_STOP)
        })
        wait.timeout()
        assertFalse(ProviderConnectionSafety.newConnectionAllowed)
        assertFalse(ProviderConnectionSafety.snapshot().canRecoverLocalProcess)
    }
}
