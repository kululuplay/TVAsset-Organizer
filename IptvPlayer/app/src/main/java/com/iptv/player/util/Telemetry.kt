/*
 * Telemetry.kt
 * Single source of truth for the Kululu crash-receiver base URL and shared ingest
 * key. Both the crash uploader (CrashReporter) and the live-device heartbeat
 * (HeartbeatReporter) read from here, so rotating the URL or key only changes one
 * place. The key is NOT a real secret — it ships inside the APK and is
 * extractable; it only deters casual spam. Rotate CRASH_INGEST_KEY on the server
 * and TELEMETRY_INGEST_KEY in the build environment together if it is abused.
 */
package com.iptv.player.util

import com.iptv.player.BuildConfig

object Telemetry {
    /** Published Replit crash-receiver service. */
    const val BASE_URL = "https://asset-organizer-kululuaydin.replit.app"

    /** CI-injected value matching the receiver's CRASH_INGEST_KEY. */
    val INGEST_KEY: String = BuildConfig.TELEMETRY_INGEST_KEY

    /** Optional field telemetry is disabled in builds without an injected key. */
    val isEnabled: Boolean get() = INGEST_KEY.isNotBlank() && BASE_URL.startsWith("https://")

    /** Crash report ingest (uploaded on the next launch after a crash). */
    const val CRASH_ENDPOINT = "$BASE_URL/api/crash"

    /** Live-device heartbeat ingest (sent periodically while foregrounded). */
    const val HEARTBEAT_ENDPOINT = "$BASE_URL/api/heartbeat"

    /** Network-quality (peering) test result upload, shown per device in the panel. */
    const val NETTEST_ENDPOINT = "$BASE_URL/api/nettest"

    /** Deterministic cohort decision for the newest immutable GitHub release. */
    const val UPDATE_POLICY_ENDPOINT = "$BASE_URL/api/update-policy"

    /** One-button privacy-safe diagnostics report; returns a short support code. */
    const val SUPPORT_REPORT_ENDPOINT = "$BASE_URL/api/support-report"

    /** User request / complaint submitted from the Home dialog, shown in the panel. */
    const val REQUEST_ENDPOINT = "$BASE_URL/api/request"

    /** This device's own request history (shown in the Home İstek & Şikayet dialog). */
    const val REQUESTS_MINE_ENDPOINT = "$BASE_URL/api/requests/mine"

    /** Acknowledge resolved-request popups so the server stops re-sending them. */
    const val REQUESTS_ACK_ENDPOINT = "$BASE_URL/api/requests/ack"
}
