---
name: DataStore vs attachBaseContext locale
description: Why per-app language uses a synchronous SharedPreferences mirror, not DataStore, at attach time
---

# Per-app locale read at attachBaseContext

`BaseActivity.attachBaseContext` must apply the saved UI language *before* views
inflate. DataStore is suspend-only, so reading it there forces `runBlocking` on
the UI/attach path — a real startup-jank risk flagged in review.

**Decision:** `SettingsStore` keeps a tiny `SharedPreferences` mirror
("locale_mirror") written in lockstep by `setLanguageTag`, and exposes a
synchronous `languageTagBlocking()` used *only* by `attachBaseContext`. Everything
else still uses the DataStore-backed `languageTag` Flow / `getLanguageTag()`.

**Why:** DataStore has no safe synchronous read; `attachBaseContext` cannot
suspend. A mirror gives a non-blocking read without stalling the thread.

**How to apply:** Any new setting needed synchronously at attach/inflate time
must be mirrored the same way — do not add `runBlocking` on the attach path. Keep
the mirror write inside the same setter that updates DataStore, or they drift.
