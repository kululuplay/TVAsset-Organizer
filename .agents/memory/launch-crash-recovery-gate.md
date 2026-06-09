---
name: Launch-crash recovery gate
description: How the app breaks a login->home crash loop and captures the trace on devices we can't reproduce on (Sony/Fire).
---

# Launch-crash recovery gate

When users hit a crash on the login -> splash -> dashboard path (home never opens) on
devices the dev can't reproduce on, the user gets stuck in a relaunch->crash loop and
can never reach in-app diagnostics to send the trace. The fix is a **crash-loop gate**:

- `LaunchCrashGuard` (dedicated SharedPreferences flag, `commit()` synchronously):
  armed in `SplashActivity.runBrandedSplash()` before risky startup, cleared in
  `DashboardActivity.onCreate` via `binding.root.post { markLaunchSucceeded }` (runs
  after the first traversal AND the first onResume, so an onResume crash is still caught).
- If the flag is still set on the next launch, route to `CrashRecoveryActivity` which
  shows `Logger.recentText()` + Share (FileProvider) + Retry.

**Why:** Logger already persists every FATAL to disk, but the user couldn't reach the
share UI inside a crash loop. The gate surfaces it without adb.

**How to apply / gotchas:**
- `CrashRecoveryActivity` extends `AppCompatActivity` directly, NOT `BaseActivity`, so it
  bypasses the app-wide `LocaleManager` density override — if that override is ever the
  crash cause, the recovery screen still opens.
- Best-effort prefetch (`SplashPrefetch.runCatchingCancellable`) must NOT swallow
  `OutOfMemoryError`/`Error` — swallowing OOM leaves the heap exhausted so the next UI
  inflation crashes with a misleading trace (classic low-RAM Fire/Sony "home never opens").
  Rethrow OOM (after logging); swallow only `Exception`. Also set `android:largeHeap`.
- Retry re-arms the guard, so a repeat crash is caught again on the next cold launch.
