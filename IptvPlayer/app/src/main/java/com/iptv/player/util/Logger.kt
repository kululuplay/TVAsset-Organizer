/*
 * Logger.kt
 * Lightweight, dependency-free file logger for field debugging on TV devices
 * that are rarely tethered to a computer. Writes a small, rotated plain-text log
 * to the app's external files dir (so it can be shared via the existing
 * FileProvider) and installs a global uncaught-exception handler so crashes are
 * captured before the process dies. All writes are best-effort and never throw —
 * logging must not be able to crash the app it is trying to diagnose.
 */
package com.iptv.player.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private const val TAG = "Logger"
    private const val DIR = "logs"
    private const val FILE = "kululu-log.txt"
    private const val FILE_OLD = "kululu-log.1.txt"
    private const val SHARE_FILE = "kululu-log-share.txt"
    private const val MAX_BYTES = 512 * 1024 // rotate at ~0.5 MB; keep one previous

    private val lock = Any()
    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logDir: File? = null
    @Volatile private var crashHandlerInstalled = false

    /** Wire up the logger and the global crash handler. Safe to call once at startup. */
    fun init(context: Context) {
        val base = context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.filesDir
        logDir = File(base, DIR).apply { runCatching { mkdirs() } }
        installCrashHandler()
        i(TAG, "Logger started")
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = write("W", tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write("E", tag, msg, tr)

    /**
     * Builds a single shareable snapshot (previous + current log) and returns it,
     * or null if nothing has been logged yet. Lives under the external files dir
     * so it can be handed to the FileProvider for an ACTION_SEND attachment.
     */
    fun shareableFile(): File? {
        val dir = logDir ?: return null
        return synchronized(lock) {
            runCatching {
                val out = File(dir, SHARE_FILE)
                FileWriter(out, false).use { w ->
                    File(dir, FILE_OLD).takeIf { it.exists() }?.let { w.append(it.readText()) }
                    File(dir, FILE).takeIf { it.exists() }?.let { w.append(it.readText()) }
                }
                out.takeIf { it.length() > 0 }
            }.getOrNull()
        }
    }

    /** Recent log text (current file only), for inlining into a diagnostics report. */
    fun recentText(maxChars: Int = 8000): String {
        val dir = logDir ?: return ""
        return synchronized(lock) {
            runCatching {
                val text = File(dir, FILE).takeIf { it.exists() }?.readText().orEmpty()
                if (text.length > maxChars) text.takeLast(maxChars) else text
            }.getOrDefault("")
        }
    }

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        // Always mirror to logcat for tethered debugging.
        when (level) {
            "E" -> Log.e(tag, msg, tr)
            "W" -> Log.w(tag, msg, tr)
            "D" -> Log.d(tag, msg)
            else -> Log.i(tag, msg)
        }
        val dir = logDir ?: return
        synchronized(lock) {
            runCatching {
                val file = File(dir, FILE)
                if (file.length() > MAX_BYTES) rotate(dir, file)
                FileWriter(file, true).use { w ->
                    w.append(timestamp.format(Date())).append(' ')
                        .append(level).append('/').append(tag).append(": ")
                        .append(msg).append('\n')
                    if (tr != null) w.append(Log.getStackTraceString(tr)).append('\n')
                }
            }
        }
    }

    private fun rotate(dir: File, current: File) {
        runCatching {
            val old = File(dir, FILE_OLD)
            if (old.exists()) old.delete()
            current.renameTo(old)
        }
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Capture the crash before the process is torn down, then defer to the
            // platform handler so normal crash reporting / process death still happens.
            e("FATAL", "Uncaught exception on thread '${thread.name}'", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
