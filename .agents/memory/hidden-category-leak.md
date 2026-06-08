---
name: Hidden-category leak (Movies/Series grids)
description: Why hiding a category in Content Manager can still leak its content into the VOD/Series grids, and the two-layer fix.
---

# Hidden-category content can leak two ways

**Rule:** Content Manager "hidden" categories must be excluded on BOTH the catalog
queries AND the selected-category path.

1. Catalog queries: the DAO paging queries for recent / all-by-name/rating/year /
   search must take `hidden: List<String>` and use
   `WHERE (categoryId IS NULL OR categoryId NOT IN (:hidden))`. The repo passes the
   hidden set through; the grid ViewModel `combine`s `settings.hiddenCategories(type)`
   into the paging flow.
2. Selected-category path: `pagingVodByCategory(catId)` / `pagingSeriesByCategory`
   are intentionally UNfiltered (you explicitly opened that category). So a category
   that becomes hidden *while still selected* (or a stale restored selection) keeps
   leaking. Guard it in the VM `items` `when`: if `catId in hidden`, fall back to the
   filtered "all" grid.

**Why:** the by-category branch bypasses the hidden filter by design; hiding happens
in a separate screen, and on plain resume (not recreation) the selection isn't
re-derived, so only re-running the flow against the new hidden set closes the gap.
The `hidden` set is already in the combine tuple, so the guard is free.

**How to apply:** any new grid query path that can show a single category must either
filter by `hidden` or be guarded by a `catId in hidden` fallback. Activity-side, when
restoring a prior selection, reselect whenever the restored target differs from the
current selection (covers a now-hidden/removed prior id), not only when it was null.
