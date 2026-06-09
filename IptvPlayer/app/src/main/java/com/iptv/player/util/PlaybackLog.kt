/*
 * PlaybackLog.kt
 * Tiny append-only diagnostics log for playback, written to the app's external
 * files dir so the user can SHARE it (the FileProvider already exposes that dir
 * for update APKs). Captures the green-screen / no-audio decisions made by the
 * player engines and the controller — those failures only reproduce on real
 * Android TV sticks, so they must be confirmable from on-device logs.
 *
 * It ALSO keeps the most recent lines in memory and notifies listeners, so the
 * on-screen Debug overlay (preview / fullscreen / VOD) can show the exact same
 * stage/green/fallback events live while reproducing a problem.
 */
package com.iptv.player.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

object PlaybackLog {

    const val FILE_NAME = "playback.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val LOGCAT_TAG = "PlaybackLog"

    /** How many recent lines the on-screen Debug overlay keeps/shows. */
    private const val RING_CAPACITY = 16

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val shortStamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Live tail for the Debug overlay (newest last). Guarded by [ring] itself. */
    private val ring = ArrayDeque<String>()

    /** Listener that wants live diagnostic lines (the on-screen overlays). */
    fun interface Listener {
        fun onLog(line: String)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        Log.d(LOGCAT_TAG, "[$tag] $message")
        val now = Date()
        val shortLine = "${shortStamp.format(now)} [$tag] $message"
        synchronized(ring) {
            ring.addLast(shortLine)
            while (ring.size > RING_CAPACITY) ring.removeFirst()
        }
        if (listeners.isNotEmpty()) {
            mainHandler.post { listeners.forEach { it.onLog(shortLine) } }
        }
        runCatching {
            val file = file(context) ?: return
            // Truncate when it grows past the cap so it never fills the disk.
            if (file.length() > MAX_BYTES) file.writeText("")
            file.appendText("${stamp.format(now)} [$tag] $message\n")
        }
    }

    /** Recent lines (oldest first) for an overlay that just became visible. */
    fun recentLines(): List<String> = synchronized(ring) { ring.toList() }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** The shareable log file (may not exist yet). Null if no external dir. */
    fun file(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, FILE_NAME)
    }
}
