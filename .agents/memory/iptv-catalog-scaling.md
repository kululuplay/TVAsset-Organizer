---
name: IPTV catalog scaling (Paging 3 + FTS + lazy series)
description: How Kululu IPTV scales to 300k+ items — what is paged, what is not, and the FTS lockstep contract.
---

# Catalog scaling architecture

For very large Xtream/M3U catalogs on low-RAM Android TV boxes:

- **Movies & Series are paged** (Paging 3: PagingSource -> Pager -> Flow<PagingData>.cachedIn in the VM). Adapters are PagingDataAdapter; Activities use `submitData` + `loadStateFlow` (empty-state + scroll-to-top on `refresh is NotLoading`).
- **Live is NOT paged.** It stays a ListAdapter with the full in-memory list.
  **Why:** number-zap needs the complete channel set in memory; Live is already category-scoped so the working set is bounded.

## FTS lockstep contract (the non-obvious part)
- Search uses **standalone FTS4 tables** (`vod_fts`/`series_fts`/`channels_fts`) holding only `id` + `name`, joined back to content by id. They are NOT external-content — the repository maintains them manually.
- **Maintenance must mirror the content table and run inside `db.withTransaction`** or search can see a half/empty index on interrupt:
  - Live: full clear + reinsert of channels AND channels_fts in one transaction.
  - Movies/Series per-category: `upsertAll` + `fts.deleteByIds(newIds)` + `fts.insertAll` in one transaction (FTS4 has no uniqueness, so delete-before-insert prevents duplicate hits).
- Per-category merge **deliberately keeps previously-cached rows** (never clears the category) — provider-side removals persist until a full resync. FTS stays in lockstep with content, so there are no orphan search rows beyond orphan content rows. This is intentional caching, not a bug.
- `toFtsQuery()` sanitizes the MATCH expression — always route user search text through it.

## Lazy series
- `series_categories` table mirrors `vod_categories` (`loaded` flag). `selectCategory` downloads a category on demand with an inFlight guard + `refreshing` spinner. No init full-series refresh; splash/syncAll prefetch the first category only.

## Migration
- Room v8->9 is additive/non-destructive: creates FTS tables + `series_categories` and back-fills from existing rows. The migrated `series_categories` schema must match `SeriesCategoryEntity` exactly (id TEXT PK, name TEXT, position INTEGER, loaded INTEGER) or Room throws an identity mismatch at runtime. `fallbackToDestructiveMigration` is kept only as a safety net for <8.
