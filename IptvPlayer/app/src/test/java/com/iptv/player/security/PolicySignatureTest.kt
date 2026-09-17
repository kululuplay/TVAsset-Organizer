package com.iptv.player.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PolicySignatureTest {

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val pem: String = pemOf(keys.public.encoded)
    private val payload = """{"policyVersion":1,"vodConnectTimeoutMs":20000,"deviceOverrides":[]}"""

    @Test
    fun `valid signature over exact payload verifies`() {
        assertTrue(PolicySignature.verify(pem, payload, sign(payload)))
    }

    @Test
    fun `pem with CRLF line ends and surrounding whitespace still parses`() {
        val messy = "  " + pem.replace("\n", "\r\n") + "\r\n\r\n"
        assertTrue(PolicySignature.verify(messy, payload, sign(payload)))
    }

    @Test
    fun `tampered payload fails`() {
        val sig = sign(payload)
        assertFalse(PolicySignature.verify(pem, payload.replace("20000", "20001"), sig))
        assertFalse(PolicySignature.verify(pem, "$payload ", sig))
    }

    @Test
    fun `signature from a different key fails`() {
        val other = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val foreign = Signature.getInstance("SHA256withECDSA").run {
            initSign(other.private)
            update(payload.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        assertFalse(PolicySignature.verify(pem, payload, foreign))
    }

    @Test
    fun `garbage pem never throws and fails`() {
        val sig = sign(payload)
        assertFalse(PolicySignature.verify("", payload, sig))
        assertFalse(PolicySignature.verify("   ", payload, sig))
        assertFalse(PolicySignature.verify("not a key", payload, sig))
        assertFalse(PolicySignature.verify("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----", payload, sig))
        assertFalse(PolicySignature.verify("-----BEGIN PUBLIC KEY-----\n!!!!\n-----END PUBLIC KEY-----", payload, sig))
    }

    @Test
    fun `empty or malformed signature fails`() {
        assertFalse(PolicySignature.verify(pem, payload, ""))
        assertFalse(PolicySignature.verify(pem, payload, "   "))
        assertFalse(PolicySignature.verify(pem, payload, "AAAA"))
        assertFalse(PolicySignature.verify(pem, payload, "%%%not base64%%%"))
        assertFalse(PolicySignature.verify(pem, "", sign(payload)))
    }

    @Test
    fun `no key configured falls back to the unsigned object`() {
        assertEquals("{legacy}", PolicySignature.acceptedPayload("", null, null, "{legacy}"))
        assertNull(PolicySignature.acceptedPayload("", null, null, null))
    }

    @Test
    fun `key configured never accepts the unsigned object`() {
        assertNull(PolicySignature.acceptedPayload(pem, null, null, "{legacy}"))
        assertNull(PolicySignature.acceptedPayload(pem, payload, null, "{legacy}"))
        assertNull(PolicySignature.acceptedPayload(pem, null, sign(payload), "{legacy}"))
        assertNull(PolicySignature.acceptedPayload(pem, payload, sign("other"), "{legacy}"))
        assertEquals(payload, PolicySignature.acceptedPayload(pem, payload, sign(payload), "{legacy}"))
    }

    private fun sign(text: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(text.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(sign())
    }

    private fun pemOf(spki: ByteArray): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(spki) +
            "\n-----END PUBLIC KEY-----\n"

    @Test
    fun `any embedded public key block may verify during a rotation`() {
        val other = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val otherPem = "-----BEGIN PUBLIC KEY-----\n" +
            java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(other.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"
        val bundle = otherPem + pem
        assertEquals(2, PolicySignature.pemBlocks(bundle).size)
        assertTrue(PolicySignature.verify(bundle, payload, sign(payload)))
        assertFalse(PolicySignature.verify(otherPem, payload, sign(payload)))
    }
}
