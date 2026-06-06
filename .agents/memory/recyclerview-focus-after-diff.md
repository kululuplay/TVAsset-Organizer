---
name: D-pad focus after ListAdapter diff
description: Why requestFocus on a RecyclerView row must wait for submitList's commit callback (and post)
---

When moving D-pad focus to a specific RecyclerView row right after updating its
ListAdapter, do NOT call requestFocus immediately after `submitList(list)`.

**Why:** `ListAdapter` diffs asynchronously on a background thread; the rows are
not attached yet when `submitList` returns, so `findViewHolderForAdapterPosition`
returns null and focus falls back to the RecyclerView itself (wrong row / lost
selection highlight).

**How to apply:** Put the focus logic inside the `submitList(list) { ... }` commit
callback, then `recyclerView.post { findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }`.
Used for the Live TV initial category auto-focus and the category↔channel
drill-down return-focus. Keep one-time guards (e.g. an `initialSelectionDone`
flag) INSIDE the callback — it fires on every emission.
