---
name: SafeGridLayoutManager for Paging grids
description: Why the poster grids use a crash-swallowing GridLayoutManager, and the "held D-pad throws user to home" symptom behind it.
---

The VOD/Series poster grids (`posterGrid`, PagingDataAdapter + GridLayoutManager)
use `SafeGridLayoutManager` (ui/common), a GridLayoutManager subclass that wraps
`onLayoutChildren` in try/catch(IndexOutOfBoundsException) and disables predictive
item animations.

**Why:** holding a D-pad key (fast key-repeat scroll) while a background Room
write (content sync / lazy prefetch) invalidates the PagingSource lands the
diff/notify during a layout pass. RecyclerView's cached item count no longer
matches the adapter and it throws `IndexOutOfBoundsException: Inconsistency
detected. Invalid item position N`. Uncaught, this kills the process and the
launcher relaunches the app at the Dashboard — the user reports being "thrown
back to the home page" when holding the remote. This is distinct from the
notify-during-diff crash already handled by `SeriesAdapter.refreshVisible`
(direct holder rebind instead of notifyItemRangeChanged); the layout-time race
still slips through because the invalidation is driven by Room, not by us.

**How to apply:** any RecyclerView backed by a Paging3 adapter that can scroll
fast on TV should use SafeGridLayoutManager (or the equivalent try/catch on its
layout manager). Swallowing the exception is safe: the data is already consistent
again by the next layout pass, so the grid simply re-renders. Do NOT try to fix
this by throttling key events or pausing sync — the layout-manager net is the
proven, low-risk fix. Wire it into EVERY Paging-backed TV grid: the poster grids
in SeriesActivity/VodActivity and BOTH grids in SearchActivity (movie + series)
all use it; adding a new Paging grid means using SafeGridLayoutManager too.
