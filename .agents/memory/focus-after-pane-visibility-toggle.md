---
name: D-pad focus after GONE->VISIBLE pane toggle
description: Why restoring focus to a non-first RecyclerView row needs bounded-retry, not a single post()
---

When a left-pane RecyclerView is toggled `GONE -> VISIBLE` (e.g. returning from the channel list to the category list) its relayout is still PENDING. A single `recyclerView.post { findViewHolderForAdapterPosition(pos)?.itemView ?: recyclerView }.requestFocus()` runs BEFORE the holder at `pos` is laid out, so the holder is null and the fallback `recyclerView.requestFocus()` gives focus to the FIRST visible child.

**Symptom:** focus always snaps back to the TOP row instead of the row the user left from. The bug is MASKED for pos=0 lists (channel list always lands on item 0 anyway) and only visible when restoring a non-first row (category 5 -> jumps to category 1).

**Rule:** restore focus with a bounded-retry helper that re-posts until `findViewHolderForAdapterPosition(pos)` is non-null, then focuses `holder.itemView`; only fall back to `list.requestFocus()` after exhausting retries (~10).

**Why:** `post()` fires once on the next message loop, which can precede the visibility-driven layout pass; the null-holder fallback to the RecyclerView itself silently picks child 0.

**How to apply:** share one `focusRow(list, pos, attempts)` for every "land focus on a specific row" call so the masked pos=0 paths and the broken non-zero paths use the same reliable mechanism.
