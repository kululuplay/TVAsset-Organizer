/*
 * Telemetry.kt
 * Single source of truth for the Kululu crash-receiver base URL and shared ingest
 * key. Both the crash uploader (CrashReporter) and the live-device heartbeat
 * (HeartbeatReporter) read from here, so rotating the URL or key only changes one
 * place. The key is NOT a real secret — it ships inside the APK and is
 * extractable; it only deters casual spam (rotate via CRASH_INGEST_KEY on the
 * server AND here if abused).
 */
package com.iptv.player.util

object Telemetry {
    /** Published Replit crash-receiver service. */
    const val BASE_URL = "https://asset-organizer-kululuaydin.replit.app"

    /** Stamped on every report/heartbeat; must match the receiver's CRASH_INGEST_KEY. */
    const val INGEST_KEY = "kululu-crash-ingest-v1-7f3ab9c2"

    /** Crash report ingest (uploaded on the next launch after a crash). */
    const val CRASH_ENDPOINT = "$BASE_URL/api/crash"

    /** Live-device heartbeat ingest (sent periodically while foregrounded). */
    const val HEARTBEAT_ENDPOINT = "$BASE_URL/api/heartbeat"
}
