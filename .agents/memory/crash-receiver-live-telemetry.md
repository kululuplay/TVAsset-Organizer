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
