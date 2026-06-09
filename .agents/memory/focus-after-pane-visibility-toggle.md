---
name: D-pad focus after GONE->VISIBLE pane toggle
description: Why restoring focus to a non-first RecyclerView row needs bounded-retry, not a single post()
---

When a left-pane RecyclerView is toggled `GONE -> VISIBLE` (e.g. returning from the channel list to the category list) its relayout is still PENDING. A single `recyclerView.post { findViewHolderForAdapterPosition(pos)?.itemView ?: recyclerView }.requestFocus()` runs BEFORE the holder at `pos` is laid out, so the holder is null and the fallback `recyclerView.requestFocus()` gives focus to the FIRST visible child.

**Symptom:** focus always snaps back to the TOP row instead of the row the user left from. The bug is MASKED for pos=0 lists (channel list always lands on item 0 anyway) and only visible when restoring a non-first row (category 5 -> jumps to category 1).

**Rule:** restore focus with a bounded-retry helper that re-posts until `findViewHolderForAdapterPosition(pos)` is non-null, then focuses `holder.itemView`; only fall back to `list.requestFocus()` after exhausting retries (~10).

**Why:** `post()` fires once on the next message loop, which can precede the visibility-driven layout pass; the null-holder fallback to the RecyclerView itself silently picks child 0.

**How to apply:** share one `focusRow(list, pos, attempts)` for every "land focus on a specific row" call so the masked pos=0 paths and the broken non-zero paths use the same reliable mechanism.

## Channel list: restore on RETURN (not a pane toggle)

The channel list isn't toggled GONE/VISIBLE for fullscreen — the whole activity backgrounds and `onStart` re-subscribes. The `channels` StateFlow replays on every re-subscribe, so its `submitList` commit re-runs and RecyclerView resets D-pad focus to row 0. Drilling IN (`enterChannelView -> focusFirstChannel`) correctly lands on row 0; only RETURNING (back from fullscreen player / Settings / Catch-up) needs the row restored.

**Rule:** track `lastFocusedChannelId` in `ChannelAdapter.onFocused`; set a one-shot `pendingChannelFocusRestore=true` in `onStart()` guarded by `inChannelView`; consume it in the `channels` `submitList` commit (post-diff) via `indexOfFirst { it.id == lastFocusedChannelId }` + the shared `focusRow(...)`. Restore in the commit callback (after the last rebind), NOT in `onStart` directly — a direct restore can be undone by the submitList rebind that fires afterward.

**Why:** StateFlow always replays current value to the new collector `repeatOnLifecycle` creates on each START, so the list rebinds (and focus resets) on every return even with identical data.

**Watch:** restore is unconditional once flagged; if focus was intentionally on the right pane on return it could be pulled back to the list. Acceptable here because the user is in the list on return (the reset itself proves focus is in the list, just on the wrong row).
