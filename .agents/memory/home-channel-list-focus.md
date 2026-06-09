---
name: Channel list focus follows the playing channel
description: Returning from fullscreen must focus the channel actually playing, not the opened/last-browsed row.
---

The currently-playing channel is `previewingChannel` (set both by Home preview
start and by the fullscreen hand-back). It is the source of truth for "what's
playing", NOT `lastFocusedChannelId` (the row the user opened — CH+/- zapping in
fullscreen changes the playing channel without updating it).

**Rule:** when the channel list re-appears (return from player, or drilling back
in), land focus + a centered scroll on the playing channel. A one-shot
`pendingPlayingChannelId` (captured from the hand-back) takes priority over
`lastFocusedChannelId` in the restore.

**Why/how:** the player only zaps within the opened category (EXTRA_CATEGORY_ID),
so the playing channel is always inside Home's selected category — no cross-category
switch needed. The one-shot id must be armed ONLY when `inChannelView` (matching
`pendingChannelFocusRestore`), else it leaks and jumps focus on a later unrelated
return. Center with `LinearLayoutManager.scrollToPositionWithOffset` once the view
holder exists (focusRow already retries via post()).
