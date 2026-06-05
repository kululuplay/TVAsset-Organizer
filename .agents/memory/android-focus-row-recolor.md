---
name: Focus-driven row recoloring on Android
description: Why child views inside a focusable row don't pick up the row's focused/selected color and how to fix it.
---

# Focus-driven row recoloring (focusable parent + ColorStateList children)

When a focusable container (LinearLayout, etc.) swaps its background on
`state_focused` / `state_selected` (e.g. dark → solid white for the active
row), its child `TextView`/`ImageView` do NOT automatically adopt those states.
A `@color/...` selector with `state_focused`/`state_selected` on a child stays
on its default branch, so labels/icons keep their light color and become
invisible on the white active row.

**Fix:** add `android:duplicateParentState="true"` to each child that uses the
focus-aware color selector (label text color, icon `imageTintList`). Then the
child mirrors the parent's focused/selected/pressed state and the selector
flips correctly.

**Why:** focus/selection state lives on the focusable view (the row), not its
descendants. Caught in Settings master-detail redesign review — without it the
white active-row labels were unreadable.

**How to apply:** any list/row pattern where the row (not the child) is the
focusable element AND child fg colors must invert on focus. Multicolor icons
that must stay as-is (e.g. flags) should instead get `imageTintList = null` in
code so duplicateParentState doesn't recolor them.
