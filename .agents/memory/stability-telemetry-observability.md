---
name: Stability telemetry (field-failure observability)
description: How player/ANR/abnormal-exit field failures are captured when they never reach the Java crash handler — and the non-obvious traps when wiring it.
---

Native libVLC SIGSEGV, OOM-kills and ANR freezes never reach the Java
`UncaughtExceptionHandler`, so `crash_reports` stays empty while the fleet is
actually unstable. Stability telemetry fills that gap: typed events
(stall/reconnect/fallback/start_timeout/force_sw/fatal/anr/suspected_abnormal_exit)
are spooled app-side and drained onto the heartbeat into `telemetry_events`.

**Flow:** `StabilityTelemetry` (in-memory ring cap ~50 + disk JSONL spool in
filesDir, off-thread persist, never throws) → `HeartbeatReporter.sendOnce`
snapshots ≤N events + a dropped counter, attaches them to the beat, and only
clears them after HTTP 200 (`confirmUploaded` by identity / `confirmDropped`).
Server ingest is isolated in its own try/catch so a telemetry failure can never
break the beat. Backward compatible: old servers ignore the unknown `events`
field, old apps just never send it.

**Traps (each cost a real fix):**

- **Java-crash race.** `AbnormalExitDetector.detectAndReport` must be told
  whether a Java crash was pending via a flag the caller samples *before*
  `CrashReporter.uploadPendingIfAny` runs. The uploader clears the pending-crash
  marker asynchronously; if the detector reads `Logger.hasPendingCrash()` itself
  (after the uploader started) it can race and mis-attribute a real Java crash as
  a "suspected abnormal exit". Order in `IptvApp.onCreate`: init spool → sample
  `hadPendingCrash` → uploadPendingIfAny → detectAndReport(ctx, hadPendingCrash).

- **API-level floor on atomics.** `AtomicInteger.updateAndGet`/`accumulateAndGet`
  are API 24+. minSdk here is 21, so they NCDFE on older boxes (no desugaring
  guaranteed). Use a manual `get()`/`compareAndSet()` CAS loop instead.

- **Per-type detail cap.** `telemetry_events.details` is TEXT (no DB limit). ANR
  events carry a clipped main-thread stack (~8KB app-side) and must be clipped
  generously server-side (`clip(detail, type==="anr" ? 8192 : 500)`); a flat 500
  cap would shred every ANR stack.

- **Spool overflow visibility.** When the ring overflows it increments a dropped
  counter (lost events). The app sends `eventsDropped` on the beat and the server
  writes ONE synthetic `type="events_dropped"` row (isolated try/catch) so a
  failure storm stays visible even though the individual events are gone.
