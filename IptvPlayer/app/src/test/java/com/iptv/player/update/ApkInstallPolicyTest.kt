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
    fun `archive that needs a newer Android is rejected despite a verified digest`() {
        assertEquals(
            ApkValidationFailure.INCOMPATIBLE_ANDROID,
            evaluate(archiveMinSdk = 26, deviceSdkInt = 25, artifactDigestVerified = true),
        )
        assertEquals(
            ApkValidationFailure.INCOMPATIBLE_ANDROID,
            evaluate(archiveMinSdk = 26, deviceSdkInt = 25),
        )
        assertNull(evaluate(archiveMinSdk = 26, deviceSdkInt = 26))
    }

    @Test
    fun `unreadable archive below the release minimum is incompatible not invalid`() {
        // Android 5 cannot parse a minSdk 23 package at all; the verified GitHub
        // digest used to short-circuit straight into a failing system installer.
        for (digestVerified in listOf(true, false)) {
            assertEquals(
                ApkValidationFailure.INCOMPATIBLE_ANDROID,
                evaluate(
                    packageMatches = null,
                    archiveVersion = null,
                    archiveSigners = null,
                    artifactDigestVerified = digestVerified,
                    releaseMinSdk = 23,
                    deviceSdkInt = 22,
                ),
            )
        }
    }

    @Test
    fun `unreadable archive on a supported Android keeps the existing verdicts`() {
        assertEquals(
            ApkValidationFailure.INVALID_APK,
            evaluate(packageMatches = null, releaseMinSdk = 23, deviceSdkInt = 23),
        )
        assertNull(
            evaluate(
                packageMatches = null,
                archiveVersion = null,
                installedVersion = null,
                archiveSigners = null,
                installedSigners = null,
                artifactDigestVerified = true,
                releaseMinSdk = 23,
                deviceSdkInt = 23,
            ),
        )
    }

    @Test
    fun `compatibility is decided after download integrity and before everything else`() {
        assertEquals(
            ApkValidationFailure.INCOMPLETE,
            evaluate(fileSize = 90, expectedSize = 100, archiveMinSdk = 26, deviceSdkInt = 23),
        )
        assertEquals(
            ApkValidationFailure.INCOMPATIBLE_ANDROID,
            evaluate(
                availableBytes = 0,
                packageMatches = false,
                archiveVersion = 100,
                archiveSigners = setOf("different-cert"),
                archiveMinSdk = 26,
                deviceSdkInt = 23,
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
        archiveMinSdk: Int? = null,
        releaseMinSdk: Int? = null,
        deviceSdkInt: Int = 30,
    ) = ApkInstallPolicy.evaluate(
        fileSize = fileSize,
        expectedSize = expectedSize,
        availableBytes = availableBytes,
        packageMatches = packageMatches,
        archiveVersionCode = archiveVersion,
        installedVersionCode = installedVersion,
        archiveSigners = archiveSigners,
        installedSigners = installedSigners,
        artifactDigestVerified = artifactDigestVerified,
        archiveMinSdk = archiveMinSdk,
        releaseMinSdk = releaseMinSdk,
        deviceSdkInt = deviceSdkInt,
    )
}
