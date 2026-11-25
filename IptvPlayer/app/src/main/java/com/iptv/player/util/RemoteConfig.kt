/*
 * RemoteConfig.kt
 * The operator's lever that needs no release: one small JSON file in the public
 * release repository, next to update-rollout.json, carries the signed remote
 * playback policy and the active announcement. Both used to arrive in the
 * heartbeat response of a server that is not hosted any more. Read when the
 * app comes to the front and every 30 minutes while it stays there; a missing
 * or unreadable file changes nothing (a stored policy runs out at its own
 * expiry). See docs/playback-policy.md.
 */
package com.iptv.player.util

import android.content.Context
import com.iptv.player.data.ServiceLocator
import com.iptv.player.update.UpdateConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject

object RemoteConfig {

    private const val TAG = "RemoteConfig"
    const val URL =
        "https://raw.githubusercontent.com/${UpdateConfig.OWNER}/${UpdateConfig.REPO}/main/remote-config.json"

    private const val INTERVAL_MS = 30L * 60L * 1_000L

    /** A quick trip to another app and back must not fetch again. */
    private const val MIN_GAP_MS = 5L * 60L * 1_000L

    /** A signed policy text is at most 64 KB (PolicySignature); anything larger is not ours. */
    private const val MAX_BYTES = 96L * 1024L

    @Volatile private var loopJob: Job? = null
    @Volatile private var lastFetchAtMs = 0L

    /** Begin (or keep) the refresh loop. Safe to call repeatedly. */
    fun start(context: Context) {
        if (loopJob?.isActive == true) return
        val app = context.applicationContext
        loopJob = ServiceLocator.appScope.launch {
            while (isActive) {
                if (System.currentTimeMillis() - lastFetchAtMs !in 0 until MIN_GAP_MS) {
                    runCatching { refresh(app) }
                        .onFailure { Logger.w(TAG, "remote config fetch failed: ${it.message}") }
                }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Stop the loop when the app goes to the background. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun refresh(context: Context) {
        val request = Request.Builder().url(URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        val text = ServiceLocator.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val source = response.body?.source() ?: return
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) return
            source.readUtf8()
        }
        val root = JSONObject(text)
        lastFetchAtMs = System.currentTimeMillis()
        runCatching { HeartbeatReporter.applyPlaybackPolicy(context, root) }
        val announcement = root.optJSONObject("announcement")
        if (announcement == null) {
            AnnouncementCenter.clear()
        } else {
            AnnouncementCenter.update(announcement.optLong("id"), announcement.optString("message"))
        }
    }
}
