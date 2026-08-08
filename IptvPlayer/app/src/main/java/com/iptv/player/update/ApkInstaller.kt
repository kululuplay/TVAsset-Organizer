/*
 * ApkInstaller.kt
 * Launches the system package installer for a downloaded APK via FileProvider,
 * and exposes whether the app is currently allowed to install packages.
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

object ApkInstaller {

    private const val MIME_APK = "application/vnd.android.package-archive"

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
    fun install(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )

        // ACTION_INSTALL_PACKAGE is the canonical package-installer route. Several
        // Fire TV / Android TV OEM installers also require the content URI in
        // ClipData before they honour FLAG_GRANT_READ_URI_PERMISSION.
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, MIME_APK)
            clipData = ClipData.newRawUri("Kululu IPTV update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val installHandlers = context.packageManager.queryIntentActivities(
            installIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        val intent = if (installHandlers.isNotEmpty()) {
            installIntent
        } else {
            // Compatibility fallback for old/vendor installers that expose only
            // ACTION_VIEW for APK MIME types.
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                clipData = ClipData.newRawUri("Kululu IPTV update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        ).forEach { handler ->
            context.grantUriPermission(
                handler.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
