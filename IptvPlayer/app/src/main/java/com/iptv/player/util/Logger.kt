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
import java.util.concurrent.Executors

object Logger {

    private const val TAG = "Logger"
    private const val DIR = "logs"
    private const val FILE = "kululu-log.txt"
    private const val FILE_OLD = "kululu-log.1.txt"
    private const val SHARE_FILE = "kululu-log-share.txt"
    private const val MARKER = "crash-pending.txt" // dropped on crash, consumed next launch
    private const val MAX_BYTES = 512 * 1024 // rotate at ~0.5 MB; keep one previous

    private val lock = Any()
    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileWriter = Executors.newSingleThreadExecutor { task ->
        Thread(task, "field-log-writer").apply { isDaemon = true }
    }

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
                    File(dir, FILE_OLD).takeIf { it.exists() }?.let {
                        w.append(SensitiveDataRedactor.redact(it.readText()))
                    }
                    File(dir, FILE).takeIf { it.exists() }?.let {
                        w.append(SensitiveDataRedactor.redact(it.readText()))
                    }
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
                val tail = if (text.length > maxChars) text.takeLast(maxChars) else text
                SensitiveDataRedactor.redact(tail)
            }.getOrDefault("")
        }
    }

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val safeMessage = SensitiveDataRedactor.redact(msg)
        val safeStack = tr?.let { SensitiveDataRedactor.redact(Log.getStackTraceString(it)) }
        mirrorToLogcat(level, tag, safeMessage, safeStack)
        val dir = logDir ?: return
        // File.length(), rotation and append can take hundreds of milliseconds on
        // cold/slow TV storage. Serialize them on one daemon worker so callers
        // (especially Splash/Dashboard on the main looper) only enqueue work.
        runCatching {
            fileWriter.execute {
                appendToFile(dir, level, tag, safeMessage, safeStack)
            }
        }
    }

    private fun mirrorToLogcat(
        level: String,
        tag: String,
        safeMessage: String,
        safeStack: String?,
    ) {
        // Always mirror to logcat for tethered debugging.
        when (level) {
            "E" -> Log.e(tag, safeMessage + safeStack?.let { "\n$it" }.orEmpty())
            "W" -> Log.w(tag, safeMessage + safeStack?.let { "\n$it" }.orEmpty())
            "D" -> Log.d(tag, safeMessage)
            else -> Log.i(tag, safeMessage)
        }
    }

    private fun appendToFile(
        dir: File,
        level: String,
        tag: String,
        safeMessage: String,
        safeStack: String?,
    ) {
        synchronized(lock) {
            runCatching {
                appendToFileLocked(dir, level, tag, safeMessage, safeStack)
            }
        }
    }

    /** Caller must hold [lock]; SimpleDateFormat and rotation are protected too. */
    private fun appendToFileLocked(
        dir: File,
        level: String,
        tag: String,
        safeMessage: String,
        safeStack: String?,
    ) {
        val file = File(dir, FILE)
        if (file.length() > MAX_BYTES) rotate(dir, file)
        FileWriter(file, true).use { writer ->
            writer.append(timestamp.format(Date())).append(' ')
                .append(level).append('/').append(tag).append(": ")
                .append(safeMessage).append('\n')
            if (safeStack != null) writer.append(safeStack).append('\n')
        }
    }

    private fun rotate(dir: File, current: File) {
        runCatching {
            val old = File(dir, FILE_OLD)
            if (old.exists()) old.delete()
            current.renameTo(old)
        }
    }

    /** True when the previous run crashed and its report has not been uploaded yet. */
    fun hasPendingCrash(): Boolean =
        logDir?.let { File(it, MARKER).exists() } ?: false

    /** Marker contents ("<epochMillis>\n<summary>"), or null when there is none. */
    fun crashSummary(): String? {
        val dir = logDir ?: return null
        return synchronized(lock) {
            runCatching {
                File(dir, MARKER).takeIf { it.exists() }?.readText()
            }.getOrNull()
        }
    }

    /** Clear the marker once a crash report has been delivered. */
    fun clearPendingCrash() {
        val dir = logDir ?: return
        synchronized(lock) { runCatching { File(dir, MARKER).delete() } }
    }

    /**
     * A dying process cannot rely on the daemon queue being drained. Write the
     * fatal line and marker together on the crashing thread under the same lock.
     * Blocking is intentional here: the app is already terminating and durable
     * diagnostics matter more than responsiveness.
     */
    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val tag = "FATAL"
        val message = "Uncaught exception on thread '${thread.name}'"
        val safeMessage = SensitiveDataRedactor.redact(message)
        val safeStack = SensitiveDataRedactor.redact(Log.getStackTraceString(throwable))
        mirrorToLogcat("E", tag, safeMessage, safeStack)
        val dir = logDir ?: return
        synchronized(lock) {
            runCatching {
                appendToFileLocked(dir, "E", tag, safeMessage, safeStack)
            }
            runCatching {
                val summary =
                    SensitiveDataRedactor.redact(
                        "${throwable.javaClass.name}: ${throwable.message ?: ""}",
                    ).take(500)
                FileWriter(File(dir, MARKER), false).use { writer ->
                    writer.append(System.currentTimeMillis().toString())
                        .append('\n')
                        .append(summary)
                }
            }
        }
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Capture the crash before the process is torn down, then defer to the
            // platform handler so normal crash reporting / process death still happens.
            writeCrash(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
