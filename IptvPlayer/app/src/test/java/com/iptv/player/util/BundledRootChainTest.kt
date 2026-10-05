package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

/**
 * The root bundled for old Android versions must be the real ISRG Root X1 and
 * must anchor the portal chain on its own. The chain is the one served by
 * kululu.live on 5 Oct 2026 (also used by the instrumented trust test); it is
 * validated at that date so the test does not expire with the leaf.
 */
class BundledRootChainTest {

    private val factory = CertificateFactory.getInstance("X.509")

    private fun certificates(path: String): List<X509Certificate> =
        File(path).inputStream().use { input ->
            factory.generateCertificates(input).map { it as X509Certificate }
        }

    private val root = certificates("src/main/res/raw/isrg_root_x1.pem").single()
    private val chain = certificates("src/androidTest/assets/tls/kululu_live_chain.pem")

    @Test
    fun bundledRootIsIsrgRootX1() {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(root.encoded)
            .joinToString("") { "%02X".format(it) }
        // Published by ISRG: https://letsencrypt.org/certificates/
        assertEquals("96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6", fingerprint)
        assertEquals(root.subjectX500Principal, root.issuerX500Principal)
    }

    @Test
    fun portalChainValidatesAgainstTheBundledRootAlone() {
        assertTrue(
            "fixture leaf is for the portal",
            chain.first().subjectAlternativeNames.orEmpty().any { it[1] == "kululu.live" },
        )
        val parameters = PKIXParameters(setOf(TrustAnchor(root, null))).apply {
            isRevocationEnabled = false
            date = Date.from(Instant.parse("2026-10-05T12:00:00Z"))
        }
        // Throws when the chain does not lead to the bundled root.
        CertPathValidator.getInstance("PKIX")
            .validate(factory.generateCertPath(chain), parameters)
    }
}
