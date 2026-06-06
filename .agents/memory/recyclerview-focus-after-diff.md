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

**Sibling trap — never `notifyDataSetChanged()` from a focus listener.** The
shared `CategoryAdapter` updated its "selected" outline via `setSelected()` →
`notifyDataSetChanged()`, and `setSelected` is called from each row's
`onFocusChange`. On Android TV every D-pad move then rebound the WHOLE list
mid-focus-traversal, so the RecyclerView lost focus and snapped back to the top —
the user could only reach the first 1-2 categories and the rest never scrolled
into view (looked like "categories not loading"). Fix: repaint only the two
affected rows with a **payload** (`notifyItemChanged(pos, PAYLOAD_SELECTION)`)
and handle it in the 3-arg `onBindViewHolder` by flipping `itemView.isSelected`
only — no full rebind, focus preserved. **Rule:** selection/visual-state changes
driven by focus must be targeted + payloaded, never a blanket notify.
