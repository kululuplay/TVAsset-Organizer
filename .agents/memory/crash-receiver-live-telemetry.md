---
name: Crash-receiver live-device telemetry
description: How the Node crash-receiver tracks live devices + the Postgres schema-migration trap that bit it
---

The `crash-receiver/` Node/Express service (workflow "Crash Receiver", Postgres, deployed at asset-organizer-kululuaydin.replit.app) does TWO things: crash reports (POST /api/crash, app uploads on next launch after a crash) AND live-device telemetry (POST /api/heartbeat, app pings every 60s while foregrounded). Heartbeats UPSERT one row per device into a `devices` table; the panel shows online (last_seen within 3 min), IP, model, app/Android version.

**Postgres schema-migration trap (the important durable lesson):** `initDb()` uses `CREATE TABLE IF NOT EXISTS`, which does NOTHING to an already-deployed table — it will silently NOT add a new column. To add a column to an existing prod table you MUST add an explicit `ALTER TABLE x ADD COLUMN IF NOT EXISTS col TYPE;` in initDb (that's how `ip` was added to `crash_reports`). New tables/indexes are fine with IF NOT EXISTS.
**Why:** the deployed DB persists across redeploys; only the boot-time DDL runs, and CREATE-IF-NOT-EXISTS is a no-op on an existing table.

**Android heartbeat gating:** Android TV / Fire Stick keep idle processes alive for hours, so a bare process-alive loop over-counts "live". Gate the loop on FOREGROUND via `registerActivityLifecycleCallbacks` started-activity counter in IptvApp (start loop 0→1, stop 1→0). Android starts the new activity before stopping the old one, so navigation never flaps the counter. Loop runs on ServiceLocator.appScope (Dispatchers.IO), best-effort runCatching.

**Device id:** Settings.Secure.ANDROID_ID, but fall back to a persisted random UUID (SettingsStore.getOrCreateDeviceId) when it's null/blank or the notorious clone-box value `9774d56d682e549c`, else cheap boxes collapse into one row.

**Shared constants:** base URL + ingest key live in `util/Telemetry.kt` (both CrashReporter and HeartbeatReporter read it) so URL/key rotate in one place. Ingest key ships in the APK — not a real secret, only deters casual spam.

**Rollout caveat:** only devices that install the NEW APK appear as live; existing installs stay invisible until updated. The server must be REPUBLISHED for /api/heartbeat to go live (initDb's ALTER runs on boot).

**Now-playing (panel "Şu an"):** a `NowPlaying` singleton carries title+kind, sent in the heartbeat. Clear MUST use an owner token (the activity instance): VOD next-episode launches a NEW player activity and then finishes the OLD one, so the old screen's onStop would otherwise wipe the value the new screen just set. clear(owner) no-ops unless the caller still owns the slot.

**Remote announcements (panel → all devices):** the /api/heartbeat 200 response carries `{announcement:{id,message}|null}`; the app surfaces it once. Two durable lessons:
- **Parse the heartbeat response 204/null-safe.** Older servers return 204 (no body); gate on isSuccessful, blank-body early-return, runCatching the JSON, treat `announcement:null` as clear. A strict parser would break the whole loop against an old server.
- **Dedup needs BOTH a persisted id AND an atomic in-memory claim.** The heartbeat-thread listener and onActivityResumed both call the show path; their suspending DataStore reads interleave on Main, so a persisted-id check alone double-shows. A `@Synchronized claim(id)` (compare-and-set on an in-memory high-water id) is the real guard; the persisted id only covers process restarts.
- **Re-validate the activity AFTER the suspending DataStore writes, right before AlertDialog.show().** The settings read+write suspend (disk I/O); the activity can be destroyed (BACK/HOME) in between → `WindowManager$BadTokenException`. Re-check isFinishing/isDestroyed/still-front (and wrap show() in runCatching). Safe to skip the show: the id is already persisted, so it simply waits for the next safe resume.
- Suppress the dialog over player/screensaver/trailer screens so it never interrupts viewing; it surfaces on the next safe onActivityResumed.

**Per-device crash counts:** crash reports now send the same `deviceId` (CrashReporter calls the shared `DeviceId.get` via runBlocking on its crash-upload daemon thread); the panel LEFT JOINs crash_reports.device_id to show per-device crash count. `device_id` was added to crash_reports via the same ALTER trap above.

**Geo + shared-IP:** heartbeat does a fire-and-forget IP geolocation (ip-api.com free http) with ip-keyed cache + in-flight set + negative cache, skipping private/loopback; the panel flags an IP shared by >1 distinct device_id (account-sharing signal). Geo is best-effort and conditional `UPDATE ... WHERE ip` so a slow lookup never blocks the heartbeat.
