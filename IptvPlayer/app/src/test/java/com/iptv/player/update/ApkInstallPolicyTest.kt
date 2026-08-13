package com.iptv.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun evaluate(
        fileSize: Long = 100,
        expectedSize: Long = 100,
        availableBytes: Long = 1_000,
        packageMatches: Boolean = true,
        archiveVersion: Long = 118,
        installedVersion: Long = 117,
        archiveSigners: Set<String> = signer,
        installedSigners: Set<String> = signer,
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
