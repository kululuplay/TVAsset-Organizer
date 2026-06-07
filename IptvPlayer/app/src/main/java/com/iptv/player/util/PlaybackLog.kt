/*
 * PlaybackLog.kt
 * Tiny append-only diagnostics log for playback, written to the app's external
 * files dir so the user can SHARE it (the FileProvider already exposes that dir
 * for update APKs). Captures the green-screen / no-audio decisions made by the
 * player engines and the controller — those failures only reproduce on real
 * Android TV sticks, so they must be confirmable from on-device logs.
 */
package com.iptv.player.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PlaybackLog {

    const val FILE_NAME = "playback.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val LOGCAT_TAG = "PlaybackLog"

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        Log.d(LOGCAT_TAG, "[$tag] $message")
        runCatching {
            val file = file(context) ?: return
            // Truncate when it grows past the cap so it never fills the disk.
            if (file.length() > MAX_BYTES) file.writeText("")
            file.appendText("${stamp.format(Date())} [$tag] $message\n")
        }
    }

    /** The shareable log file (may not exist yet). Null if no external dir. */
    fun file(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, FILE_NAME)
    }
}
