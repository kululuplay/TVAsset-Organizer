package com.iptv.player.update

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallPolicyTest {
    private val signer = setOf("stable-signing-cert")

    @Test
    fun `valid newer matching APK passes`() {
        assertNull(evaluate())
    }

    @Test
    fun `truncated download is rejected`() {
        assertEquals(
            ApkValidationFailure.INCOMPLETE,
            evaluate(fileSize = 90, expectedSize = 100),
        )
    }

    @Test
    fun `installer staging space is required after download`() {
        assertEquals(
            ApkValidationFailure.INSUFFICIENT_STORAGE,
            evaluate(fileSize = 100, expectedSize = 100, availableBytes = 199),
        )
    }

    @Test
    fun `same or older version is rejected`() {
        assertEquals(
            ApkValidationFailure.NOT_NEWER,
            evaluate(archiveVersion = 117, installedVersion = 117),
        )
    }

    @Test
    fun `different signing certificate is rejected`() {
        assertEquals(
            ApkValidationFailure.SIGNATURE_MISMATCH,
            evaluate(archiveSigners = setOf("different-cert")),
        )
    }

    @Test
    fun `verified official digest tolerates missing OEM signer metadata`() {
        assertNull(
            evaluate(
                archiveSigners = emptySet(),
                installedSigners = emptySet(),
                artifactDigestVerified = true,
            ),
        )
    }

    @Test
    fun `verified official digest tolerates unavailable OEM package metadata`() {
        assertNull(
            evaluate(
                packageMatches = null,
                archiveVersion = null,
                installedVersion = null,
                archiveSigners = null,
                installedSigners = null,
                artifactDigestVerified = true,
            ),
        )
    }

    @Test
    fun `unavailable package metadata without verified digest is rejected`() {
        assertEquals(
            ApkValidationFailure.INVALID_APK,
            evaluate(packageMatches = null),
        )
    }

    @Test
    fun `missing signer metadata without a verified digest is rejected`() {
        assertEquals(
            ApkValidationFailure.INVALID_APK,
            evaluate(archiveSigners = emptySet()),
        )
    }

    @Test
    fun `verified digest never overrides a real signer mismatch`() {
        assertEquals(
            ApkValidationFailure.SIGNATURE_MISMATCH,
            evaluate(
                archiveSigners = setOf("different-cert"),
                artifactDigestVerified = true,
            ),
        )
    }

    @Test
    fun `GitHub sha256 digest is normalized safely`() {
        val hex = "ab".repeat(32)
        assertEquals(hex, ApkIntegrityPolicy.normalizeSha256("sha256:$hex"))
        assertEquals(hex, ApkIntegrityPolicy.normalizeSha256(hex.uppercase()))
        assertNull(ApkIntegrityPolicy.normalizeSha256("sha256:not-a-digest"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `API 28 plus signing query includes legacy and modern flags`() {
        val flags = ApkPackageInfoQueryPolicy.signingFlags(28)

        assertTrue(flags and PackageManager.GET_SIGNATURES != 0)
        assertTrue(flags and PackageManager.GET_SIGNING_CERTIFICATES != 0)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `pre API 28 signing query uses legacy flag`() {
        assertEquals(
            PackageManager.GET_SIGNATURES,
            ApkPackageInfoQueryPolicy.signingFlags(27),
        )
    }

    private fun evaluate(
        fileSize: Long = 100,
        expectedSize: Long = 100,
        availableBytes: Long = 1_000,
        packageMatches: Boolean? = true,
        archiveVersion: Long? = 118,
        installedVersion: Long? = 117,
        archiveSigners: Set<String>? = signer,
        installedSigners: Set<String>? = signer,
        artifactDigestVerified: Boolean = false,
    ) = ApkInstallPolicy.evaluate(
        fileSize,
        expectedSize,
        availableBytes,
        packageMatches,
        archiveVersion,
        installedVersion,
        archiveSigners,
        installedSigners,
        artifactDigestVerified,
    )
}
