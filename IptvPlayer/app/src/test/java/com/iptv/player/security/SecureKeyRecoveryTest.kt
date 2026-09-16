package com.iptv.player.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

class SecureKeyRecoveryTest {
    @Test fun `missing keystore alias replaces the wrapped key`() {
        assertTrue(SecureKeyRecovery.shouldReplaceWrappedKey(privateKeyPresent = false, unwrapFailure = null))
        assertTrue(SecureKeyRecovery.shouldReplaceWrappedKey(false, ProviderException("keystore down")))
    }

    @Test fun `permanent unwrap failures replace the wrapped key`() {
        listOf(
            UnrecoverableKeyException("gone"),
            InvalidKeyException("permanently invalidated"),
            BadPaddingException("blob from another device"),
            IllegalBlockSizeException("truncated blob"),
        ).forEach { assertTrue(it.toString(), SecureKeyRecovery.shouldReplaceWrappedKey(true, it)) }
    }

    @Test fun `usable key and transient failures keep the wrapped key`() {
        assertFalse(SecureKeyRecovery.shouldReplaceWrappedKey(true, null))
        assertFalse(SecureKeyRecovery.shouldReplaceWrappedKey(true, ProviderException("keystore busy")))
        assertFalse(SecureKeyRecovery.shouldReplaceWrappedKey(true, IllegalStateException("cipher not ready")))
    }
}
