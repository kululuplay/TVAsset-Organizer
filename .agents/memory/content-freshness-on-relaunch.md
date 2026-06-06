---
name: Content freshness on app relaunch
description: How/why already-opened VOD & Series categories get force-resynced on every cold start.
---
VOD and Series items are lazy-loaded per category and cached with a persistent Room `loaded` flag, so once a category is opened it is normally never re-fetched. That means new movies/series the provider adds inside an already-opened category would never appear.

**Rule:** On every cold start, `SplashPrefetch` (background, after `essentialDone`) force-resyncs all categories where `loaded=1` (DAO `loadedIds()`) via `refreshLoaded{Vod,Series}Categories` → `refresh*Category(force=true)`. Live channels + the category LISTS already refresh fully on each start, so new channels and brand-new categories are covered separately.

**Why:** Product requirement — users must always get the freshest channels/movies/series when they exit and re-enter. Balanced against the 300k-catalog lazy-load design by only re-syncing the bounded set the user actually browsed, not the whole catalog.

**How to apply:** `force=true` bypasses the `loaded` short-circuit and upserts (REPLACE on id) so cached items never flicker — only genuinely new items appear. Run the loaded-sweep BEFORE the unforced `prefetchFirst*` warm-up, otherwise the first category is fetched twice in one run. No timestamp throttle by design ("always" on relaunch); it is best-effort background work so it never blocks the splash. If a provider ignores series `category_id`, this sweep can get heavy — revisit with a per-category throttle only if field logs show excessive traffic.
