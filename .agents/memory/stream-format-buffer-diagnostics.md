---
name: Stream format / buffer / diagnostics settings
description: How the live stream-format, buffer-size, and diagnostics-overlay settings are wired through the player.
---

- **Live stream format (TS/HLS)** is a play-time URL rewrite, NOT a re-sync. Xtream
  live URLs are baked with `.ts` in Room at sync. `PlayerActivity.applyStreamFormat`
  swaps only a path `.ts`/`.m3u8` extension to the chosen one; VOD/series real
  containers are left untouched.
  **Why:** re-syncing the whole catalog to flip a container is wasteful and the URL
  shape is deterministic.
  **How to apply:** always split off `?query`/`#fragment` (CDN tokens) before
  matching/replacing the extension, then re-append — providers commonly suffix live
  URLs with tokens and a raw `endsWith(".ts")` silently misses them.

- **Buffer size (LOW/NORMAL/HIGH)** carries concrete values on the `BufferMode` enum:
  one libVLC `networkCachingMs` + four ExoPlayer DefaultLoadControl durations. Thread
  it through PlayerController into both engine constructors. NORMAL == the previous
  hardcoded values (3000ms VLC; 2000/8000/1000/1500 Exo) so default behaviour is
  unchanged.

- **Diagnostics overlay** reads `PlayerEngine.getStreamInfo()` (default null). Exo via
  `player.videoFormat` (safe). VLC via `currentVideoTrack`: width/height/frameRate
  num/den are proven-safe; `track.codec` MUST be read through an `Any?`-typed helper
  (Int fourcc on some libVLC versions, String on others) or it won't compile across
  versions. Overlay refresh uses a Handler posting every 1s — cancel it in both
  `hideStats()` and `onDestroy()`.
