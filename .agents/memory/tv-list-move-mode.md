---
name: TV list "move mode" reorder pattern
description: How to do D-pad drag-to-reorder of a RecyclerView row on Android TV without focus escaping or stale badges.
---

# Android TV "move mode" reorder (RecyclerView + ListAdapter)

Pattern used by the Content Manager (category + channel editors): long-press a row
to enter move mode, D-pad up/down reorders, OK/Back commits.

## Rule 1 — trap ALL D-pad nav at the activity while moving
Handle reorder in `Activity.dispatchKeyEvent`, and while `movingId != null`
**consume LEFT/RIGHT/UP/DOWN/CENTER/BACK** (return true) so focus cannot leave the
list. If you only consume UP/DOWN, LEFT/RIGHT move focus off the list while move
mode is still active and subsequent UP/DOWN silently reorder the wrong row in the
background.
**Why:** activity dispatchKeyEvent is the top of the dispatch chain; returning true
there prevents the event from ever reaching the focused view.

## Rule 2 — moving badge won't rebind via submitList
The "Moving" indicator state lives on the adapter (`var movingId`), not in the item
data class. When you only toggle move mode (no reorder), `submitList(sameList)`
DiffUtil sees identical contents → no rebind → badge never appears/disappears.
Fix: on enter/exit move mode call a targeted `notifyItemChanged(pos)` for that one
row (NOT from a focus listener, NOT notifyDataSetChanged). During actual reorder,
`submitList` move-animates the row and its ViewHolder binding (badge) persists, so
only enter/exit need the explicit notify.

## Focus retention
Reorder steps use `submitList(list){ ... }` commit callback + `post{ findViewByPosition(pos)?.requestFocus() }`.
Enter/exit (no list change) restore focus the same way after the notifyItemChanged.
