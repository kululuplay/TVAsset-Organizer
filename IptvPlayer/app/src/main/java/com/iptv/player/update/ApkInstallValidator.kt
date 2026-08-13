package com.iptv.player.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.security.MessageDigest

enum class ApkValidationFailure {
    MISSING,
    INCOMPLETE,
    CHECKSUM_MISMATCH,
    INSUFFICIENT_STORAGE,
    INVALID_APK,
    WRONG_PACKAGE,
    NOT_NEWER,
    SIGNATURE_MISMATCH,
}

sealed interface ApkValidationResult {
    data object Valid : ApkValidationResult
    data class Invalid(val failure: ApkValidationFailure) : ApkValidationResult
}

/** Pure decision core kept separate so update compatibility is regression-testable. */
internal object ApkInstallPolicy {
    fun evaluate(
        fileSize: Long,
        expectedSize: Long,
        availableBytes: Long,
        packageMatches: Boolean,
        archiveVersionCode: Long,
        installedVersionCode: Long,
        archiveSigners: Set<String>,
        installedSigners: Set<String>,
        artifactDigestVerified: Boolean = false,
    ): ApkValidationFailure? = when {
        fileSize <= 0L -> ApkValidationFailure.MISSING
        expectedSize > 0L && fileSize != expectedSize -> ApkValidationFailure.INCOMPLETE
        availableBytes >= 0L && availableBytes < fileSize * 2L ->
            ApkValidationFailure.INSUFFICIENT_STORAGE
        !packageMatches -> ApkValidationFailure.WRONG_PACKAGE
        archiveVersionCode <= installedVersionCode -> ApkValidationFailure.NOT_NEWER
        archiveSigners.isNotEmpty() && installedSigners.isNotEmpty() &&
            archiveSigners != installedSigners -> ApkValidationFailure.SIGNATURE_MISMATCH
        (archiveSigners.isEmpty() || installedSigners.isEmpty()) &&
            !artifactDigestVerified ->
            ApkValidationFailure.INVALID_APK
        else -> null
    }
}

/** Normalizes the digest format returned by the GitHub Releases API. */
internal object ApkIntegrityPolicy {
    private val sha256 = Regex("^[0-9a-f]{64}$")

    fun normalizeSha256(raw: String?): String? {
        val value = raw?.trim()?.lowercase().orEmpty()
        val hex = if (value.startsWith("sha256:")) value.substringAfter(':') else value
        return hex.takeIf { sha256.matches(it) }
    }
}

/** Verifies a downloaded APK before handing it to an OEM package installer. */
object ApkInstallValidator {

    fun validate(
        context: Context,
        file: File,
        expectedSize: Long = -1L,
        expectedSha256: String? = null,
    ): ApkValidationResult {
        if (!file.isFile || file.length() <= 0L) {
            return ApkValidationResult.Invalid(ApkValidationFailure.MISSING)
        }
        if (expectedSize > 0L && file.length() != expectedSize) {
            return ApkValidationResult.Invalid(ApkValidationFailure.INCOMPLETE)
        }

        val digestVerified = if (!expectedSha256.isNullOrBlank()) {
            val expected = ApkIntegrityPolicy.normalizeSha256(expectedSha256)
                ?: return ApkValidationResult.Invalid(ApkValidationFailure.CHECKSUM_MISMATCH)
            val actual = runCatching { file.sha256() }.getOrNull()
                ?: return ApkValidationResult.Invalid(ApkValidationFailure.CHECKSUM_MISMATCH)
            if (actual != expected) {
                return ApkValidationResult.Invalid(ApkValidationFailure.CHECKSUM_MISMATCH)
            }
            true
        } else {
            false
        }

        val packageManager = context.packageManager
        val archive = packageInfo(packageManager, file.absolutePath)
            ?: return ApkValidationResult.Invalid(ApkValidationFailure.INVALID_APK)
        val installed = installedPackageInfo(packageManager, context.packageName)
            ?: return ApkValidationResult.Invalid(ApkValidationFailure.INVALID_APK)

        val failure = ApkInstallPolicy.evaluate(
            fileSize = file.length(),
            expectedSize = expectedSize,
            availableBytes = runCatching {
                StatFs(file.parentFile?.absolutePath ?: file.absolutePath).availableBytes
            }.getOrDefault(-1L),
            packageMatches = archive.packageName == context.packageName,
            archiveVersionCode = archive.versionCodeCompat(),
            installedVersionCode = installed.versionCodeCompat(),
            archiveSigners = archive.signerDigests(),
            installedSigners = installed.signerDigests(),
            artifactDigestVerified = digestVerified,
        )
        return if (failure == null) {
            ApkValidationResult.Valid
        } else {
            ApkValidationResult.Invalid(failure)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, path: String): PackageInfo? {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                packageManager.getPackageArchiveInfo(
                    path,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            }.getOrNull()
        } else {
            null
        }
        if (modern != null && modern.signerDigests().isNotEmpty()) return modern

        val legacy = runCatching {
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }.getOrNull()
        return legacy ?: modern
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(
        packageManager: PackageManager,
        packageName: String,
    ): PackageInfo? {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            }.getOrNull()
        } else {
            null
        }
        if (modern != null && modern.signerDigests().isNotEmpty()) return modern

        val legacy = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }.getOrNull()
        return legacy ?: modern
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val modernSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { signingInfo?.apkContentsSigners.orEmpty() }.getOrDefault(emptyArray())
        } else emptyArray()
        val signatures = modernSignatures.takeIf { it.isNotEmpty() }
            ?: this.signatures.orEmpty()
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
