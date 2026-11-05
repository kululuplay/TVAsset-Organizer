/*
 * ApkInstaller.kt
 * Launches the system package installer for a downloaded APK via FileProvider
 * (API 24+) or a world-readable file:// URI (API 23, where Android 6.0's
 * PackageInstallerActivity rejects content:// URIs with a "Parsing-Fehler"
 * dialog), and exposes whether the app is currently allowed to install packages.
 */
package com.iptv.player.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Pure policy for APK installer URI scheme and target package grants. */
internal object ApkInstallerUriPolicy {
    /**
     * Android 6.0 (API 23) PackageInstallerActivity only accepts `file://` and
     * `package:` schemes; handing it a `content://` FileProvider URI hits
     * DLG_PACKAGE_ERROR ("Beim Parsen des Pakets ist ein Problem aufgetreten").
     * Android 7.0+ (API 24+) supports `content://` and enforces FileUriExposedException.
     */
    fun useFileUri(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.N

    val KNOWN_INSTALLER_PACKAGES: Set<String> = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.amazon.packageinstaller",
        "com.miui.packageinstaller",
    )
}

object ApkInstaller {

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val UPDATE_SUBDIR = "updates"

    /** On Android O+ the user must grant "install unknown apps" for this app. */
    fun canRequestInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens settings so the user can allow installs from this app. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Launches the installer for [file]. Returns false if it could not start. */
    @Suppress("DEPRECATION")
    fun install(context: Context, file: File): Boolean = runCatching {
        val useFileScheme = ApkInstallerUriPolicy.useFileUri(Build.VERSION.SDK_INT)
        val targetFile = if (useFileScheme) stageForLegacyFileInstaller(context, file) else file
        val uri = if (useFileScheme) {
            Uri.fromFile(targetFile)
        } else {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile,
            )
        }

        // ACTION_INSTALL_PACKAGE is the canonical package-installer route. Several
        // Fire TV / Android TV OEM installers also require the content URI in
        // ClipData before they honour FLAG_GRANT_READ_URI_PERMISSION.
        val installIntent = buildInstallerIntent(Intent.ACTION_INSTALL_PACKAGE, uri, useFileScheme)
        val viewIntent = buildInstallerIntent(Intent.ACTION_VIEW, uri, useFileScheme)

        val pm = context.packageManager
        val installHandlers = queryAllHandlers(pm, installIntent)
        val viewHandlers = queryAllHandlers(pm, viewIntent)
        val intent = if (installHandlers.isNotEmpty()) installIntent else viewIntent

        if (!useFileScheme) {
            val packagesToGrant = LinkedHashSet<String>().apply {
                addAll(ApkInstallerUriPolicy.KNOWN_INSTALLER_PACKAGES)
                installHandlers.forEach { add(it) }
                viewHandlers.forEach { add(it) }
            }
            packagesToGrant.forEach { pkg ->
                runCatching {
                    context.grantUriPermission(
                        pkg,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }

        context.startActivity(intent)
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun buildInstallerIntent(action: String, uri: Uri, useFileScheme: Boolean): Intent =
        Intent(action).apply {
            setDataAndType(uri, MIME_APK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!useFileScheme) {
                clipData = ClipData.newRawUri("Kululu IPTV update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    /**
     * Queries both default-only and unfiltered activities so OEM TV ROMs that
     * route through a system ResolverActivity still reveal the underlying
     * package installer package names for explicit URI permission grants.
     */
    @Suppress("DEPRECATION")
    private fun queryAllHandlers(pm: PackageManager, intent: Intent): Set<String> {
        val packages = LinkedHashSet<String>()
        runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNullTo(packages) { it.activityInfo?.packageName }
        }
        runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNullTo(packages) { it.activityInfo?.packageName }
        }
        return packages
    }

    /**
     * On API 23 (`file://` URI), `/data/user/0/<pkg>/cache` may not be traversable
     * by `com.android.packageinstaller` (different UID). Stage into external cache
     * when available, and mark parent directories world-executable + the APK file
     * world-readable so the system PackageParser can open it directly.
     */
    private fun stageForLegacyFileInstaller(context: Context, source: File): File {
        val extRoot = runCatching { context.externalCacheDir ?: context.getExternalFilesDir(null) }
            .getOrNull()
        if (extRoot != null) {
            val dir = File(extRoot, UPDATE_SUBDIR)
            if (dir.isDirectory || dir.mkdirs()) {
                val staged = File(dir, source.name)
                val copied = runCatching {
                    if (staged.absolutePath != source.absolutePath) {
                        FileInputStream(source).use { input ->
                            FileOutputStream(staged).use { output ->
                                input.copyTo(output, bufferSize = 64 * 1024)
                                output.flush()
                                runCatching { output.fd.sync() }
                            }
                        }
                    }
                    staged.isFile && staged.length() == source.length()
                }.getOrDefault(false)
                if (copied) {
                    makeReadableForLegacyInstaller(staged)
                    return staged
                }
            }
        }
        makeReadableForLegacyInstaller(source)
        return source
    }

    /**
     * API 23 file:// path only: the installer runs under another UID. Parents
     * get the traverse bit, never the read bit, so the app's private
     * directories stay unlistable. API 24+ uses FileProvider and changes no
     * file mode at all; a mode set here would outlive the update.
     */
    private fun makeReadableForLegacyInstaller(file: File) {
        runCatching {
            file.setReadable(true, false)
            var parent = file.parentFile
            var depth = 0
            while (parent != null && depth < 4) {
                parent.setExecutable(true, false)
                parent = parent.parentFile
                depth++
            }
        }
    }
}
