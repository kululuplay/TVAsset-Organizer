---
name: Category list change-animation steals D-pad focus
description: Fast category browsing exiting to Dashboard = live count updates + RecyclerView change animations detaching the focused row.
---

Symptom: on Android TV, browsing the left category rail FAST with the D-pad in
Live TV / Movies / Series pops the whole screen back to the Dashboard.

**Why:** The `categories` flow re-emits while you browse because category COUNTS
update as content lazy-loads into Room. Each re-emit calls `submitList()`, and
RecyclerView's default change animation (DefaultItemAnimator/SimpleItemAnimator)
detaches/rebinds the currently-focused row mid-diff. D-pad focus escapes the rail;
during the reload storm there's no focusable target, so focus falls to null and
the window pops back, exiting to the Dashboard.

**How to apply:** Disable change animations on any focus-bearing RecyclerView whose
rows get content-only diffs from a live-updating flow:
`(list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false`.
The row then rebinds in place and keeps focus. This is the sibling of the existing
rule "selection/visual-state changes from a focus listener must be payloaded, never
a blanket notify" — here the churn comes from submitList diffs, not the listener.
Residual: true STRUCTURAL diffs (insert/remove/reorder of focused item) can still
steal focus; restore focused position/id after submit if that ever surfaces.
