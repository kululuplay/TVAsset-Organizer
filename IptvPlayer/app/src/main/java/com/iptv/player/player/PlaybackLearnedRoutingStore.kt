package com.iptv.player.player

import android.content.Context
import android.content.SharedPreferences
import com.iptv.player.player.PlaybackRoutingPolicy.Stage
import java.util.concurrent.Executors

/**
 * Process-wide, SharedPreferences-backed owner of [PlaybackLearnedRouting].
 *
 * Reads are in-memory and synchronous on the hot path; every mutation is
 * persisted off the main thread. Nothing here throws: a missing or corrupt
 * state simply starts empty. [clear] is the crash-loop safe mode hook.
 */
internal object PlaybackLearnedRoutingStore {

    private const val PREFS = "playback_learned_routing"
    private const val KEY_STATE = "state"

    private val lock = Any()
    private val model = PlaybackLearnedRouting(clock = System::currentTimeMillis)
    @Volatile private var prefs: SharedPreferences? = null
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "learned-routing").apply { isDaemon = true }
    }

    /** Load the state learned by previous runs. Call once, early, on startup. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        io.execute {
            runCatching {
                val encoded = p.getString(KEY_STATE, null)
                synchronized(lock) { model.load(encoded) }
            }
        }
    }

    fun suggestFor(channelKey: String?, primary: Stage): PlaybackLearnedRouting.Suggestion? =
        synchronized(lock) { model.suggestFor(channelKey, primary) }

    fun deviceDefault(): Stage? = synchronized(lock) { model.deviceDefault() }

    fun fingerprintStage(fingerprint: String?): Stage? =
        synchronized(lock) { model.fingerprintStage(fingerprint) }

    fun rememberFingerprint(channelKey: String?, fingerprint: String?) {
        if (channelKey == null || fingerprint == null) return
        synchronized(lock) { model.rememberFingerprint(channelKey, fingerprint) }
        persist()
    }

    fun recordRescue(
        channelKey: String?,
        fingerprint: String?,
        failed: Stage,
        rescuedBy: Stage,
        primary: Stage,
    ): PlaybackLearnedRouting.Changes {
        val changes = synchronized(lock) {
            model.recordRescue(channelKey, fingerprint, failed, rescuedBy, primary)
        }
        if (changes.any) persist()
        return changes
    }

    fun recordLearnedFailure(
        channelKey: String?,
        fingerprint: String?,
        stage: Stage,
    ): PlaybackLearnedRouting.Dropped {
        val dropped = synchronized(lock) { model.recordLearnedFailure(channelKey, fingerprint, stage) }
        if (dropped.any) persist()
        return dropped
    }

    fun confirm(channelKey: String?, fingerprint: String?, stage: Stage) {
        synchronized(lock) { model.confirm(channelKey, fingerprint, stage) }
        persist()
    }

    /** Forget everything learned (safe mode): a learned route may itself be the crasher. */
    fun clear() {
        synchronized(lock) { model.clear() }
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val encoded = synchronized(lock) { model.encode() }
        io.execute {
            runCatching { p.edit().putString(KEY_STATE, encoded).apply() }
        }
    }
}
