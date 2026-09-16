/*
 * SecureKeyRecovery.kt
 * Pure decision for SecureValueCodec: when is a wrapped AES blob beyond repair
 * (so it must be replaced) versus temporarily unreadable (so the caller keeps
 * failing loudly and the blob is preserved)? Kept free of Android types so it
 * is unit-testable without the Keystore.
 */
package com.iptv.player.security

import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

internal object SecureKeyRecovery {

    /**
     * True when the wrapped key can never be unwrapped again: the Keystore alias
     * is gone, or the alias exists but no longer matches the blob (restored from
     * another device, permanently invalidated key). Transient Keystore failures
     * return false so a hiccup never destroys usable key material.
     */
    fun shouldReplaceWrappedKey(privateKeyPresent: Boolean, unwrapFailure: Throwable?): Boolean {
        if (!privateKeyPresent) return true
        return when (unwrapFailure) {
            null -> false
            // KeyPermanentlyInvalidatedException extends InvalidKeyException.
            is UnrecoverableKeyException, is InvalidKeyException,
            is BadPaddingException, is IllegalBlockSizeException -> true
            else -> false
        }
    }
}
