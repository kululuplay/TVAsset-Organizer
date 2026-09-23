package com.iptv.player.util

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import com.iptv.player.BuildConfig
import com.iptv.player.playback.android.PlaybackQoeRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Independent of the retired shared-key telemetry. No device/player IO on the UI thread. */
object PlaybackSupportReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var store: AtomicFile? = null
    private val queue = PlaybackUploadQueue()
    @Volatile private var app: Context? = null
    @Volatile private var foreground = false
    private var loop: Job? = null
    private var loaded = false
    private data class Capture(val rows: List<Map<String, Any>>, val boundary: Boolean, val sampledAt: Long,
        val incident: Map<String, Any>? = null)
    private val captures = Channel<Capture>(64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var collector: Job? = null
    private var incidentSampler: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastCycleAt = -10_000L

    @Synchronized fun init(context: Context) {
        app = context.applicationContext
        if (collector == null) collector = scope.launch {
            for (item in captures) persistCapture(item)
        }
    }

    /** Captured at the event, never looked up after a channel change. */
    fun capture(rows: List<Map<String, Any>>, boundary: Boolean = true, incident: Map<String, Any>? = null) {
        if (rows.isEmpty() && boundary && incident == null) return
        if (app == null) return
        captures.trySend(Capture(rows.take(16), boundary, System.currentTimeMillis(), incident))
    }

    private suspend fun persistCapture(item: Capture) {
        val context = app ?: return
        try {
            val secrets = SupportClient.playbackSecrets()
            val safe = item.rows.map { fields ->
                fields.toMutableMap().apply {
                    (get("content_label") as? String)?.let { label ->
                        put("content_label", SupportPayloadPolicy.playbackLabel(label, secrets))
                    }
                }
            }
            val id = UUID.randomUUID().toString()
            val payload = JSONObject().put("schema", 1).put("sampleId", id)
                .put("sampledAtMs", item.sampledAt).put("device", device(context, secrets))
            item.incident?.let { payload.put("incidents", JSONArray().put(JSONObject(it))) }
            var included = safe
            var body = payload.put("sessions", JSONArray(included.map(::JSONObject))).toString()
            while (body.toByteArray(Charsets.UTF_8).size > 32 * 1024 && included.isNotEmpty()) {
                included = included.dropLast(1)
                body = payload.put("sessions", JSONArray(included.map(::JSONObject))).toString()
            }
            mutex.withLock {
                load(context)
                queue.expire(System.currentTimeMillis())
                queue.enqueue(PlaybackUploadQueue.Entry(id, body, item.sampledAt, item.boundary, item.incident != null))
                persist()
            }
            if (item.incident != null && foreground) wake.trySend(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Diagnostics must never affect playback. */ }
    }

    @Synchronized fun start(context: Context) {
        init(context)
        foreground = true
        if (loop?.isActive == true) return
        incidentSampler = scope.launch {
            while (foreground) {
                runCatching { PlaybackQoeRuntime.sampleIncidents() }
                delay(2_000L)
            }
        }
        loop = scope.launch {
            while (foreground) {
                // Even incident bursts stay below the service limit: two attempts per >=10s.
                delay((10_000L - (SystemClock.elapsedRealtime() - lastCycleAt)).coerceAtLeast(0L))
                val cycleStartedAt = SystemClock.elapsedRealtime()
                lastCycleAt = cycleStartedAt
                try {
                    persistCapture(Capture(PlaybackQoeRuntime.supportSnapshot(), false, System.currentTimeMillis()))
                    // Incidents wake this loop; ordinary health stays at a 30-second cadence.
                    for (attempt in 0..1) {
                        val entry = mutex.withLock { load(context); queue.next(System.currentTimeMillis()) } ?: break
                        if (!foreground) break
                        val delivery = SupportClient.uploadPlayback(context, entry.id, entry.body)
                        mutex.withLock {
                            if (delivery.acknowledgedId != null) queue.acknowledge(entry.id, delivery.acknowledgedId)
                            else {
                                if (delivery.rejected) queue.reject(entry.id)
                                queue.failed(System.currentTimeMillis())
                            }
                            persist()
                        }
                        if (delivery.acknowledgedId == null) break
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutex.withLock { queue.failed(System.currentTimeMillis()); runCatching { persist() } }
                }
                withTimeoutOrNull((30_000L - (SystemClock.elapsedRealtime() - cycleStartedAt)).coerceAtLeast(1_000L)) { wake.receive() }
            }
        }
    }

    @Synchronized fun stop() {
        foreground = false
        loop?.cancel()
        loop = null
        incidentSampler?.cancel()
        incidentSampler = null
        // Persist background state, but never start a background network upload.
        PlaybackQoeRuntime.pauseForBackground()
        capture(PlaybackQoeRuntime.supportSnapshot())
    }

    private fun load(context: Context) {
        if (loaded) return
        store = AtomicFile(File(context.filesDir, "support-playback-v1.json"))
        val file = store!!.baseFile
        if (file.length() <= 512 * 1024) runCatching {
            val json = store!!.openRead().bufferedReader().use { JSONObject(it.readText()) }
            val rows = json.optJSONArray("queue") ?: JSONArray()
            val entries = (0 until minOf(rows.length(), 32)).map { index ->
                val row = rows.getJSONObject(index)
                PlaybackUploadQueue.Entry(row.getString("id"), row.getString("body"), row.getLong("at"), row.getBoolean("boundary"), row.optBoolean("urgent"))
            }
            queue.restore(entries, json.optInt("failures"), json.optLong("next"), System.currentTimeMillis())
        }
        loaded = true
    }

    private fun persist() {
        val json = JSONObject().put("failures", queue.failureCount).put("next", queue.nextAttemptMs)
            .put("queue", JSONArray(queue.entries().map { entry -> JSONObject().put("id", entry.id)
                .put("body", entry.body).put("at", entry.sampledAtMs).put("boundary", entry.boundary).put("urgent", entry.urgent) }))
        val file = store ?: return
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    @Suppress("DEPRECATION")
    private fun device(context: Context, secrets: List<String>): JSONObject {
        val result = JSONObject()
        listOf(Triple("manufacturer", Build.MANUFACTURER, 64), Triple("model", Build.MODEL, 96),
            Triple("androidVersion", Build.VERSION.RELEASE, 32), Triple("appVersion", BuildConfig.VERSION_NAME, 32))
            .forEach { (key, value, limit) -> result.put(key, SupportPayloadPolicy.sanitize(value, secrets).take(limit)) }
        result.put("apiLevel", Build.VERSION.SDK_INT).put("versionCode", BuildConfig.VERSION_CODE)
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetworkInfo
            result.put("networkConnected", network?.isConnected == true)
            result.put("metered", cm.isActiveNetworkMetered)
            result.put("networkType", when {
                network == null || !network.isConnected -> "NONE"
                network.type == ConnectivityManager.TYPE_WIFI -> "WIFI"
                network.type == ConnectivityManager.TYPE_ETHERNET -> "ETHERNET"
                network.type == ConnectivityManager.TYPE_MOBILE -> "CELLULAR"
                else -> "OTHER"
            })
        }.onFailure { result.put("networkType", "UNKNOWN") }
        runCatching {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            result.put("availableMemoryMb", memory.availMem / 1_048_576L)
                .put("totalMemoryMb", memory.totalMem / 1_048_576L).put("lowMemory", memory.lowMemory)
            result.put("powerSave", (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode)
        }
        return result
    }
}
