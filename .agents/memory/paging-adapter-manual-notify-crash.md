---
name: PagingDataAdapter manual-notify crash
description: Why you must never call notifyItemRangeChanged/notifyDataSetChanged on a Paging adapter to refresh overlay UI state
---

Never call `notifyItemRangeChanged(0, itemCount)` (or `notifyDataSetChanged()`)
on a `PagingDataAdapter` to refresh decoration/overlay state (resume bars,
watched ticks, adult masking) on resume.

**Why:** Paging dispatches its own async diffs/page loads. A manual range-notify
races those updates and the RecyclerView throws
`IndexOutOfBoundsException: Inconsistency detected. Invalid item position N`
during the next layout pass -> the process dies. In this app that crash
relaunched the user to the Dashboard (looked like a navigation bug, not a crash).
Trigger here: returning from a detail screen fires the grid Activity's
`onResume` -> `refreshWatchState()` -> the manual notify, while lazy Room writes
were invalidating the PagingSource.

**How to apply:** To repaint visible cards from external state, re-bind ONLY the
currently attached holders directly — iterate `recyclerView.childCount` /
`getChildViewHolder(getChildAt(i))`, safe-cast to your VH, and call `bind()` on
a stored `boundItem`. No adapter notifications. This also avoids stealing D-pad
focus. The two Paging grids are the Movies (VOD) and Series poster grids.
