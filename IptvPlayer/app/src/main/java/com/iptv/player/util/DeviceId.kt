/*
 * DeviceId.kt
 * Stable per-install device id shared by ALL telemetry (the live-device heartbeat
 * and the crash uploader) so the ops panel can tie a crash report back to the box
 * that produced it. Resolved once and persisted: ANDROID_ID when usable, otherwise
 * a random UUID that survives restarts (a factory reset minting a new id is fine).
 *
 * Needs no permission and no Google Play Services — works on Fire TV / Sony TV.
 */
package com.iptv.player.util

import android.content.Context
import android.provider.Settings
import com.iptv.player.data.ServiceLocator
import java.util.UUID

object DeviceId {

    // The notorious shared/duplicated ANDROID_ID some cheap clone boxes ship; if
    // we see it (or null/blank) we fall back to a persisted random UUID so those
    // devices don't all collapse into one row.
    private const val BAD_ANDROID_ID = "9774d56d682e549c"

    /** Returns the persisted id, creating + storing one on first use. */
    suspend fun get(context: Context): String =
        ServiceLocator.settings.getOrCreateDeviceId {
            val androidId = runCatching {
                Settings.Secure.getString(
                    context.applicationContext.contentResolver,
                    Settings.Secure.ANDROID_ID,
                )
            }.getOrNull()
            if (androidId.isNullOrBlank() || androidId == BAD_ANDROID_ID) {
                UUID.randomUUID().toString()
            } else {
                androidId
            }
        }
}
