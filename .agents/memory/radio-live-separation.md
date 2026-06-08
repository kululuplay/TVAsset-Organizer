---
name: Radio vs Live TV separation
description: Radio has no provider field; excluding it from Live TV touches more paths than the category list.
---

Radio stations are ContentType.LIVE rows with NO explicit provider/type flag — they
are distinguished only by category name containing `radio`/`radyo`. Persisted as a
backfilled `isRadio` column; DAO queries take a sentinel `radio` param
(-1=all incl. management, 0=live only, 1=radio only).

**Why:** A naive "filter the category list" only hides radios from the browse rails.
Two other paths silently leak radios back into Live TV and caused regressions:

**How to apply:** When scoping radio (or any cross-cutting channel subset), also cover:
- Player zap list — `PlayerViewModel.loadPlaylist()` loads the whole LIVE list; thread
  the radio scope through the player intent or up/down zapping jumps from a radio into
  Live TV channels.
- Home synthetic Favorites/Recent rows — they use cross-section flows
  (observeFavorites/observeRecent), so a favorited/recent radio reappears on Live TV
  unless filtered by `isRadio` in the Home grid source.
- Management queries must stay at radio=-1 so the Content Manager still sees everything.
