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

## Per-pass enrichment jobs must be cancellable (stale-overwrite trap)

The cache-first flow calls `showItem()` TWICE in the normal case (once for the
cached record, once for the enriched/detailed one). Each `showItem` kicks off
secondary enrichment coroutines (cast row, "Similar"/related rail). If those are
fire-and-forget `lifecycleScope.launch {}`, BOTH passes race:

- pass 1 (cached) has `tmdbId=null`/`cast=null` → falls back to a name-search.
- pass 2 (detailed) has the real `tmdbId` → accurate lookup.

Whichever finishes LAST wins `submitList`. If the slower cached pass lands last it
overwrites the accurate cast/similar with wrong-movie/name-search results (or an
empty fallback that hides the row).

**Fix:** hold a `Job?` per enrichment stream (e.g. `castJob`, `similarJob`),
`?.cancel()` it at the top of each loader, then reassign the new launch. Because
the cache-first `load()` runs sequentially, pass 2 always cancels pass 1's job
before relaunching, so the accurate result wins.

**Where:** done in `VodDetailActivity` (Movies). `SeriesDetailActivity` is EXEMPT —
verified single-pass: `loadHeader()` is its only caller of `loadCast()`/`loadSimilar()`
and runs exactly once from cache (`getSeriesCached`), with no second network
"detailed" re-render. So there is no race there; do NOT add castJob/similarJob to
Series detail — it would be dead defensive code.
