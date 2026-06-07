---
name: Paging refresh scroll-to-top steals D-pad focus
description: Why TV grids backed by Paging3 must not scrollToPosition(0) on every refresh-settle
---

On Android TV grids (Movies/Series poster grids) backed by Paging3, do NOT call
`recyclerView.scrollToPosition(0)` unconditionally when `loadStateFlow` refresh
settles to `NotLoading`. Gate it with `!recyclerView.hasFocus()`.

**Why:** Movie/series catalogs are lazily downloaded per-category and written into
Room while the user is browsing. Each write invalidates the PagingSource, which
re-runs refresh and re-settles to `NotLoading`. An unconditional scroll-to-top
then yanks the grid to position 0 mid-scroll; the focused child is detached and
focus escapes to the next focusable sibling (the category list on the left). The
user perceives this as "focus jumps back to categories while fast-scrolling."

**How to apply:** Only reset to the top when the user is NOT inside the grid
(i.e., still selecting categories). When focus is in the grid, leave scroll
position alone — a background invalidation must never move it.
