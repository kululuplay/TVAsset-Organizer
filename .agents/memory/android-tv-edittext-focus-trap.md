---
name: Android TV EditText D-pad focus trap
description: Why focus gets stuck on a search EditText on Android TV and how to let D-pad escape into content.
---

Symptom: on a TV browse screen with a search `EditText` at the top of a side panel, D-pad focus stays stuck on the search box; user cannot go DOWN into the category list or RIGHT into the content grid.

Causes:
- A focusable `EditText` is the first focusable view, so it grabs focus on entry.
- An `EditText` consumes DPAD_LEFT/RIGHT for text-cursor movement, so geometric focus search to the side never runs.
- `android:nextFocusDown`/`nextFocusRight` are unreliable when they target a `RecyclerView` that is `android:focusable="false"` (focus only lives on its item rows). Explicit nextFocus targets are expected to be focusable; otherwise the system falls back to OEM-dependent geometric search.

Fix (two parts, both needed):
1. On first data load, move focus into content (e.g. first category row) instead of the search box, so the screen is navigable on entry; UP returns to search. Gate this on a `firstLoad` flag so later refreshes don't steal focus while the user is typing.
2. Add a hard guarantee on the EditText: `setOnKeyListener` intercept `ACTION_DOWN` for `KEYCODE_DPAD_DOWN`/`KEYCODE_DPAD_RIGHT`, request focus on the first row/first grid item, and return `true`. Return `false` for all other keys so typing, BACK, UP and IME actionSearch still work.

To focus a row inside a non-focusable RecyclerView, request focus on `findViewHolderForAdapterPosition(0)?.itemView` inside a `post {}` (after layout), falling back to the RecyclerView.

**Why:** dp-level nextFocus + geometric search are device-dependent and the EditText eats LEFT/RIGHT; explicit key interception is the only deterministic escape across TV boxes.
**How to apply:** mirror the exact same code on every browse screen that pairs a search EditText with a category list + content grid (VOD + Series share this layout).
