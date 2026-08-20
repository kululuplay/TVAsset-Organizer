package com.iptv.player.util

import android.content.Context
import org.json.JSONObject

/** Small, fail-safe playback kill switches delivered by the heartbeat response. */
object PlaybackRemotePolicy {

    data class Snapshot(
        val policyVersion: Int = 1,
        val expiresAtEpochMs: Long = 0L,
        val disablePixelCopyValidation: Boolean = false,
        val vodConnectTimeoutMs: Int = DEFAULT_VOD_CONNECT_TIMEOUT_MS,
        val vodReadTimeoutMs: Int = DEFAULT_VOD_READ_TIMEOUT_MS,
        val allowSourceEngineFallback: Boolean = true,
    )

    @Volatile private var current = Snapshot()

    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot =
        effective(current, nowMs)

    internal fun effective(candidate: Snapshot, nowMs: Long): Snapshot =
        candidate.takeIf { it.expiresAtEpochMs > nowMs } ?: Snapshot()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = Snapshot(
            policyVersion = prefs.getInt(KEY_POLICY_VERSION, 1),
            expiresAtEpochMs = prefs.getLong(KEY_EXPIRES_AT, 0L),
            disablePixelCopyValidation = prefs.getBoolean(KEY_DISABLE_PIXEL_COPY, false),
            vodConnectTimeoutMs = prefs.getInt(
                KEY_VOD_CONNECT_TIMEOUT,
                DEFAULT_VOD_CONNECT_TIMEOUT_MS,
            ).coerceIn(MIN_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS),
            vodReadTimeoutMs = prefs.getInt(
                KEY_VOD_READ_TIMEOUT,
                DEFAULT_VOD_READ_TIMEOUT_MS,
            ).coerceIn(MIN_TIMEOUT_MS, MAX_READ_TIMEOUT_MS),
            allowSourceEngineFallback = prefs.getBoolean(KEY_SOURCE_ENGINE_FALLBACK, true),
        )
    }

    fun apply(context: Context, json: JSONObject) {
        val next = sanitize(
            policyVersion = json.optInt("policyVersion", 1),
            expiresAtEpochMs = json.optLong("expiresAtEpochMs", 0L),
            disablePixelCopyValidation = json.optBoolean(
                "disablePixelCopyValidation",
                current.disablePixelCopyValidation,
            ),
            vodConnectTimeoutMs = json.optInt(
                "vodConnectTimeoutMs",
                current.vodConnectTimeoutMs,
            ),
            vodReadTimeoutMs = json.optInt(
                "vodReadTimeoutMs",
                current.vodReadTimeoutMs,
            ),
            allowSourceEngineFallback = json.optBoolean(
                "allowSourceEngineFallback",
                current.allowSourceEngineFallback,
            ),
        )
        current = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_POLICY_VERSION, next.policyVersion)
            .putLong(KEY_EXPIRES_AT, next.expiresAtEpochMs)
            .putBoolean(KEY_DISABLE_PIXEL_COPY, next.disablePixelCopyValidation)
            .putInt(KEY_VOD_CONNECT_TIMEOUT, next.vodConnectTimeoutMs)
            .putInt(KEY_VOD_READ_TIMEOUT, next.vodReadTimeoutMs)
            .putBoolean(KEY_SOURCE_ENGINE_FALLBACK, next.allowSourceEngineFallback)
            .apply()
    }

    internal fun sanitize(
        policyVersion: Int = 1,
        expiresAtEpochMs: Long = 0L,
        disablePixelCopyValidation: Boolean,
        vodConnectTimeoutMs: Int,
        vodReadTimeoutMs: Int,
        allowSourceEngineFallback: Boolean,
    ): Snapshot = Snapshot(
        policyVersion = policyVersion.coerceIn(1, 1),
        expiresAtEpochMs = expiresAtEpochMs.coerceAtLeast(0L),
        disablePixelCopyValidation = disablePixelCopyValidation,
        vodConnectTimeoutMs = vodConnectTimeoutMs.coerceIn(
            MIN_TIMEOUT_MS,
            MAX_CONNECT_TIMEOUT_MS,
        ),
        vodReadTimeoutMs = vodReadTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_READ_TIMEOUT_MS),
        allowSourceEngineFallback = allowSourceEngineFallback,
    )

    private const val PREFS = "playback_remote_policy"
    private const val KEY_POLICY_VERSION = "policy_version"
    private const val KEY_EXPIRES_AT = "expires_at_epoch_ms"
    private const val KEY_DISABLE_PIXEL_COPY = "disable_pixel_copy"
    private const val KEY_VOD_CONNECT_TIMEOUT = "vod_connect_timeout_ms"
    private const val KEY_VOD_READ_TIMEOUT = "vod_read_timeout_ms"
    private const val KEY_SOURCE_ENGINE_FALLBACK = "source_engine_fallback"
    private const val MIN_TIMEOUT_MS = 5_000
    private const val MAX_CONNECT_TIMEOUT_MS = 30_000
    private const val MAX_READ_TIMEOUT_MS = 60_000
    private const val DEFAULT_VOD_CONNECT_TIMEOUT_MS = 15_000
    private const val DEFAULT_VOD_READ_TIMEOUT_MS = 20_000
}
