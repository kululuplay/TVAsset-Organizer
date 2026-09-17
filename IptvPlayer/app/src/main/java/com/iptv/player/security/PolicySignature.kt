/*
 * PolicySignature.kt
 * Verifies the operator-signed remote playback policy delivered by the heartbeat
 * (`playbackPolicySigned`: {payload, sig, kid}). The payload is the exact JSON
 * text the panel signed; `sig` is a base64 DER ECDSA-SHA256 signature over its
 * UTF-8 bytes, made with the P-256 private key whose SPKI public half ships in
 * BuildConfig.POLICY_PUBLIC_KEY_PEM. When a key is configured the app never
 * applies an unsigned or badly signed policy, so a compromised or spoofed
 * telemetry host cannot reconfigure players in the field.
 *
 * Pure JVM (java.security only); never throws.
 */
package com.iptv.player.security

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object PolicySignature {

    private const val KEY_ALGORITHM = "EC"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Anything larger is not a policy; refuse before touching the crypto stack. */
    private const val MAX_PAYLOAD_CHARS = 64 * 1024
    private const val MAX_SIG_CHARS = 512

    /**
     * True only when [sigBase64] is a valid ECDSA-SHA256 signature over the
     * UTF-8 bytes of [payload] by the key in [publicKeyPem]. Returns false for
     * a blank/garbage PEM, an empty or malformed signature, or any exception.
     */
    fun verify(publicKeyPem: String, payload: String, sigBase64: String): Boolean {
        if (publicKeyPem.isBlank() || payload.isEmpty() || sigBase64.isBlank()) return false
        if (payload.length > MAX_PAYLOAD_CHARS || sigBase64.length > MAX_SIG_CHARS) return false
        return runCatching {
            val sig = decodeBase64(sigBase64.trim()) ?: return false
            if (sig.isEmpty()) return false
            val bytes = payload.toByteArray(Charsets.UTF_8)
            // The embedded PEM may hold several PUBLIC KEY blocks (old + new
            // during a rotation); the signature only has to match one of them.
            pemBlocks(publicKeyPem).any { block ->
                runCatching {
                    val key = parsePublicKey(block) ?: return@runCatching false
                    Signature.getInstance(SIGNATURE_ALGORITHM).run {
                        initVerify(key)
                        update(bytes)
                        verify(sig)
                    }
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    /** Splits a PEM text into its individual `PUBLIC KEY` blocks (at most 4). */
    internal fun pemBlocks(pem: String): List<String> {
        val marker = "-----END PUBLIC KEY-----"
        val blocks = pem.split(marker).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return emptyList()
        return blocks.take(4).map { "$it\n$marker" }
    }

    /**
     * The policy text to apply, or null when nothing may be applied. With no
     * key configured (dev/local builds) the legacy unsigned object is allowed
     * through as [unsignedFallback]; with a key, only a verified [payload].
     */
    fun acceptedPayload(
        publicKeyPem: String,
        payload: String?,
        sigBase64: String?,
        unsignedFallback: String?,
    ): String? {
        if (publicKeyPem.isBlank()) return unsignedFallback
        if (payload.isNullOrEmpty() || sigBase64.isNullOrBlank()) return null
        return if (verify(publicKeyPem, payload, sigBase64)) payload else null
    }

    private fun parsePublicKey(pem: String): PublicKey? {
        val body = pem.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }
        if (body.isEmpty()) return null
        val der = decodeBase64(body) ?: return null
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(der))
    }

    /**
     * Unit tests run against android.jar stubs where android.util.Base64 returns
     * null, so fall back to java.util.Base64 (API 26+) and finally a tiny local
     * decoder that also serves API 21-25 devices should the platform one fail.
     */
    private fun decodeBase64(text: String): ByteArray? {
        runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()?.let { return it }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching { java.util.Base64.getDecoder().decode(text) }.getOrNull()?.let { return it }
        }
        return decodeBase64Manually(text)
    }

    private fun decodeBase64Manually(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() }.trimEnd('=')
        if (clean.isEmpty()) return null
        var bits = 0
        var buffer = 0
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        for (c in clean) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
