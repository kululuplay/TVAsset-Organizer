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
    ): ApkValidationFailure? = when {
        fileSize <= 0L -> ApkValidationFailure.MISSING
        expectedSize > 0L && fileSize != expectedSize -> ApkValidationFailure.INCOMPLETE
        availableBytes >= 0L && availableBytes < fileSize * 2L ->
            ApkValidationFailure.INSUFFICIENT_STORAGE
        !packageMatches -> ApkValidationFailure.WRONG_PACKAGE
        archiveVersionCode <= installedVersionCode -> ApkValidationFailure.NOT_NEWER
        archiveSigners.isEmpty() || installedSigners.isEmpty() ->
            ApkValidationFailure.INVALID_APK
        archiveSigners != installedSigners -> ApkValidationFailure.SIGNATURE_MISMATCH
        else -> null
    }
}

/** Verifies a downloaded APK before handing it to an OEM package installer. */
object ApkInstallValidator {

    fun validate(
        context: Context,
        file: File,
        expectedSize: Long = -1L,
    ): ApkValidationResult {
        if (!file.isFile || file.length() <= 0L) {
            return ApkValidationResult.Invalid(ApkValidationFailure.MISSING)
        }
        if (expectedSize > 0L && file.length() != expectedSize) {
            return ApkValidationResult.Invalid(ApkValidationFailure.INCOMPLETE)
        }

        val packageManager = context.packageManager
        val archive = packageInfo(packageManager, file.absolutePath)
            ?: return ApkValidationResult.Invalid(ApkValidationFailure.INVALID_APK)
        val installed = runCatching {
            installedPackageInfo(packageManager, context.packageName)
        }.getOrNull() ?: return ApkValidationResult.Invalid(ApkValidationFailure.INVALID_APK)

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
        )
        return if (failure == null) {
            ApkValidationResult.Valid
        } else {
            ApkValidationResult.Invalid(failure)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(
        packageManager: PackageManager,
        packageName: String,
    ): PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            this.signatures.orEmpty()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
