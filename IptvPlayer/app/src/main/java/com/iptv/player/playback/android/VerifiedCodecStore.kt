package com.iptv.player.playback.android

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.media3.common.MediaLibraryInfo
import com.iptv.player.playback.core.CodecEvidence
import com.iptv.player.playback.core.CodecQueueMode
import com.iptv.player.playback.core.VerifiedCodecPolicy
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Device-local observations. Keys contain hashes, never account/content identifiers. */
internal object VerifiedCodecStore {
    private val entries = LinkedHashMap<String, CodecEvidence>()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "verified-codecs").apply { isDaemon = true } }
    @Volatile private var ready = false
    @Volatile private var loading = false
    private var app: Context? = null
    val deviceScope: String by lazy {
        hash("queue-v1|${MediaLibraryInfo.VERSION}|${Build.FINGERPRINT}|${Build.VERSION.SDK_INT}|${Build.SUPPORTED_ABIS.orEmpty().joinToString()}|" +
            if (Build.VERSION.SDK_INT >= 23) Process.is64Bit() else false)
    }

    @Synchronized fun init(context: Context) {
        if (loading) return
        loading = true
        app = context.applicationContext
        worker.execute {
            val loaded = runCatching {
                val prefs = app!!.getSharedPreferences("verified_codec_queue_v1", Context.MODE_PRIVATE)
                if (prefs.getString("device", null) != deviceScope) emptyMap() else {
                    val root = JSONObject(prefs.getString("evidence", "{}") ?: "{}")
                    val now = System.currentTimeMillis()
                    root.keys().asSequence().take(128).mapNotNull { key ->
                        val o = root.optJSONObject(key) ?: return@mapNotNull null
                        val at = o.optLong("at")
                        if (!key.matches(Regex("[0-9a-f]{64}")) || now - at !in 0..VerifiedCodecPolicy.TTL_MS) return@mapNotNull null
                        key to CodecEvidence(o.optInt("bad").coerceIn(0, 3), o.optInt("rate").coerceIn(0, 1000),
                            o.optInt("good").coerceIn(0, 2), o.optLong("blocked"), at)
                    }.toMap()
                }
            }.getOrDefault(emptyMap())
            synchronized(this) { loaded.forEach { (key, value) -> if (key !in entries) entries[key] = value }; ready = true }
        }
    }

    @Synchronized fun evidence(key: String): CodecEvidence? = if (ready) entries[key] else null

    @Synchronized fun observe(key: String, mode: CodecQueueMode, rendered: Long, dropped: Long, duration: Long,
        allowPromotion: Boolean = true) {
        if (!ready) return
        val now = System.currentTimeMillis()
        val old = entries[key]?.takeIf { now - it.updatedAtMs in 0..VerifiedCodecPolicy.TTL_MS } ?: CodecEvidence()
        put(key, VerifiedCodecPolicy.observe(old, mode, rendered, dropped, duration, true, now, allowPromotion))
    }

    @Synchronized fun failed(key: String) {
        // An async construction failure must be distrusted immediately, even if
        // an optional persisted-profile read has not completed yet.
        put(key, VerifiedCodecPolicy.failed(entries[key] ?: CodecEvidence(), System.currentTimeMillis()))
    }

    private fun put(key: String, evidence: CodecEvidence) {
        entries[key] = evidence
        while (entries.size > 128) entries.remove(entries.minBy { it.value.updatedAtMs }.key)
        val snapshot = entries.toMap()
        val context = app ?: return
        worker.execute {
            runCatching {
                val root = JSONObject()
                snapshot.forEach { (k, e) -> root.put(k, JSONObject().put("bad", e.baselineBadWindows)
                    .put("rate", e.baselineDropPermille).put("good", e.asyncGoodSessions)
                    .put("blocked", e.blockedUntilMs).put("at", e.updatedAtMs)) }
                context.getSharedPreferences("verified_codec_queue_v1", Context.MODE_PRIVATE).edit()
                    .putString("device", deviceScope).putString("evidence", root.toString()).apply()
            }
        }
    }

    fun hash(material: String): String = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
