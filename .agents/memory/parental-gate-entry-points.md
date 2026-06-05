---
name: Parental-gate entry points
description: Every content-open funnel that must call PinLockHelper.guard for adult content
---

Adult content (PinLockHelper.looksAdult / Channel|VodItem|Series.isAdult) is PIN-gated
at the point a user OPENS content, not by hiding it. The guard lives at these funnels —
**any new playback/detail entry point must also be wrapped** or it becomes a bypass:

- `HomeActivity.openPlayer()` — single funnel for Live click, preview-card, AND number-zap.
- `VodActivity` / `SeriesActivity` poster `onClicked`.
- `SearchActivity` live/movie/series `onClicked` (search is a direct access path).
- `DashboardActivity.resume()` — continue-watching; gated by title only (ContinueItem has no category).

**Why:** the PinLockHelper infrastructure existed long before it was actually called —
adult content was ungated. Gating must be applied at every funnel, or one missed path
defeats the whole feature.

**How to apply:** wrap the open/startActivity body in
`PinLockHelper.guard(this, isAdult = X.isAdult()) { ... }`. guard prompts only when
`settings.lockAdult && hasPin()`. Defaults: lockAdult=true, getPin() falls back to
SettingsStore.DEFAULT_PIN "0000" (so hasPin() is true by default) — locked out of the box.
