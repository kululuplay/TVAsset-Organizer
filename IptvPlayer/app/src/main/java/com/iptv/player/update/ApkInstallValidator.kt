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
    /** The package's minSdkVersion is above this device's Android version. */
    INCOMPATIBLE_ANDROID,
}

sealed interface ApkValidationResult {
    data object Valid : ApkValidationResult
    data class Invalid(
        val failure: ApkValidationFailure,
        /** For [ApkValidationFailure.INCOMPATIBLE_ANDROID]: the API level required. */
        val requiredAndroidApi: Int? = null,
    ) : ApkValidationResult
}

/** Pure decision core kept separate so update compatibility is regression-testable. */
internal object ApkInstallPolicy {
    fun evaluate(
        fileSize: Long,
        expectedSize: Long,
        availableBytes: Long,
        packageMatches: Boolean?,
        archiveVersionCode: Long?,
        installedVersionCode: Long?,
        archiveSigners: Set<String>?,
        installedSigners: Set<String>?,
        artifactDigestVerified: Boolean = false,
        /** minSdkVersion read from the archive itself (API 24+), when readable. */
        archiveMinSdk: Int? = null,
        /** Minimum API declared by the GitHub release ([UpdateInfo.minAndroidApi]). */
        releaseMinSdk: Int? = null,
        deviceSdkInt: Int,
    ): ApkValidationFailure? = when {
        fileSize <= 0L -> ApkValidationFailure.MISSING
        expectedSize > 0L && fileSize != expectedSize -> ApkValidationFailure.INCOMPLETE
        // Compatibility is settled before every other verdict: a package the
        // platform cannot install must not surface as a storage, package or,
        // through the verified-digest shortcut below, a valid update.
        archiveMinSdk != null && archiveMinSdk > deviceSdkInt ->
            ApkValidationFailure.INCOMPATIBLE_ANDROID
        // Older Android returns no archive metadata at all for an APK whose
        // minSdk exceeds the device; the release's declared minimum explains it.
        packageMatches == null && releaseMinSdk != null && releaseMinSdk > deviceSdkInt ->
            ApkValidationFailure.INCOMPATIBLE_ANDROID
        availableBytes >= 0L && availableBytes < fileSize * 2L ->
            ApkValidationFailure.INSUFFICIENT_STORAGE
        packageMatches == false -> ApkValidationFailure.WRONG_PACKAGE
        archiveVersionCode != null && installedVersionCode != null &&
            archiveVersionCode <= installedVersionCode -> ApkValidationFailure.NOT_NEWER
        !archiveSigners.isNullOrEmpty() && !installedSigners.isNullOrEmpty() &&
            archiveSigners != installedSigners -> ApkValidationFailure.SIGNATURE_MISMATCH
        !artifactDigestVerified && (
            packageMatches == null ||
                archiveVersionCode == null ||
                installedVersionCode == null ||
                archiveSigners.isNullOrEmpty() ||
                installedSigners.isNullOrEmpty()
            ) ->
            ApkValidationFailure.INVALID_APK
        else -> null
    }
}

/**
 * Android 9/10 archive parsing only collects certificates when the legacy
 * GET_SIGNATURES bit is present, even when GET_SIGNING_CERTIFICATES is also
 * supported. Keep both bits on API 28+ for TV/OEM compatibility.
 */
internal object ApkPackageInfoQueryPolicy {
    @Suppress("DEPRECATION")
    fun signingFlags(sdkInt: Int): Int =
        if (sdkInt >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
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
        /** Minimum Android API declared by the release being installed. */
        releaseMinAndroidApi: Int? = null,
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
        val installed = installedPackageInfo(packageManager, context.packageName)
        val archiveMinSdk = archive?.minSdkCompat()

        val failure = ApkInstallPolicy.evaluate(
            fileSize = file.length(),
            expectedSize = expectedSize,
            availableBytes = runCatching {
                StatFs(file.parentFile?.absolutePath ?: file.absolutePath).availableBytes
            }.getOrDefault(-1L),
            packageMatches = archive?.packageName?.let { it == context.packageName },
            archiveVersionCode = archive?.versionCodeCompat(),
            installedVersionCode = installed?.versionCodeCompat(),
            archiveSigners = archive?.signerDigests(),
            installedSigners = installed?.signerDigests(),
            artifactDigestVerified = digestVerified,
            archiveMinSdk = archiveMinSdk,
            releaseMinSdk = releaseMinAndroidApi,
            deviceSdkInt = Build.VERSION.SDK_INT,
        )
        if (failure == null) return ApkValidationResult.Valid
        return ApkValidationResult.Invalid(
            failure,
            requiredAndroidApi = if (failure == ApkValidationFailure.INCOMPATIBLE_ANDROID) {
                archiveMinSdk ?: releaseMinAndroidApi
            } else {
                null
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, path: String): PackageInfo? {
        val primary = runCatching {
            packageManager.getPackageArchiveInfo(
                path,
                ApkPackageInfoQueryPolicy.signingFlags(Build.VERSION.SDK_INT),
            )
        }.getOrNull()
        if (primary != null && primary.signerDigests().isNotEmpty()) return primary

        val legacy = runCatching {
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }.getOrNull()
        return legacy ?: primary
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(
        packageManager: PackageManager,
        packageName: String,
    ): PackageInfo? {
        val primary = runCatching {
            packageManager.getPackageInfo(
                packageName,
                ApkPackageInfoQueryPolicy.signingFlags(Build.VERSION.SDK_INT),
            )
        }.getOrNull()
        if (primary != null && primary.signerDigests().isNotEmpty()) return primary

        val legacy = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }.getOrNull()
        return legacy ?: primary
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else versionCode.toLong()

    /** The archive's own minSdkVersion; only API 24+ exposes it on ApplicationInfo. */
    private fun PackageInfo.minSdkCompat(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            applicationInfo?.minSdkVersion?.takeIf { it > 0 }
        } else {
            null
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val modernSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                signingInfo?.let { info ->
                    if (info.hasMultipleSigners()) {
                        info.apkContentsSigners
                    } else {
                        info.signingCertificateHistory
                    }
                }.orEmpty()
            }.getOrDefault(emptyArray())
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
