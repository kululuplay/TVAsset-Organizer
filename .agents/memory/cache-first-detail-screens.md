---
name: Cache-first detail screens
description: VOD/Series detail screens must render Room cache first, then refresh network in background
---

# Cache-first detail rendering

Detail screens (VodDetailActivity, SeriesDetailActivity) must show the locally
cached record **immediately**, then enrich/refresh from the network in the
background — never block the first paint on a remote detail call.

**Why:** Xtream detail endpoints (`getVodInfo`, `getSeriesInfo`) can take ~20s on
real boxes (slow provider servers, huge series JSON). The basic VOD record
(name/poster/streamUrl) is already cached by `refreshVod`, and episodes are cached
after the first `getSeasons`, so the screen + a working Play button can appear at
once. A user reported ~20s blank/spinner before this was fixed.

**How to apply:**
- Repo exposes network-free cache reads: `getVodCached(id)`, `getCachedSeasons(id)`
  (shared `cachedSeasons()` helper also backs `getSeasons` offline fallback).
- Bind cache first; run the enrich/refresh call after, re-binding on success.
- Show a spinner only on a cold open with no cache. When cache is already on
  screen, the background refresh updates the DB but must NOT disrupt the view.
- Network failure must never close/blank an already-shown page.
