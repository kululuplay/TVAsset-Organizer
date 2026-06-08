---
name: VLC live caching 3s bound
description: libVLC's audio timestamp conversion has a fixed ~3s ceiling; live network/live-caching above it drops audio frames at channel start.
---

# libVLC live caching has a ~3s ceiling

Setting `--network-caching` / `--live-caching` (the user "Buffer size") above
~3000ms on the **live VLC path** makes the audio decoder request frames further
ahead than libVLC's fixed audio timestamp conversion bound. Result: at every
channel start, the audio decoder logs hundreds of
`Timestamp conversion failed (delay <caching_us>, buffering 100000, bound 3000000)`
and drops audio for the first several seconds until the clock catches up.

- `delay` (µs) == the configured live-caching (e.g. delay 5000000 == 5000ms).
- `bound 3000000` (µs) == the ~3s conversion ceiling (constant).
- When `delay > bound`, every audio frame fails conversion.

**Why:** A buffer bump to 5000ms (to fight Wi-Fi underruns) backfired — device
logs showed ~14s of dropped audio at channel start. 3000ms (the prior value) did
NOT produce this signature, so 3000ms is the practical max for libVLC live.

**How to apply:** Clamp the VLC live caching to `MAX_VLC_LIVE_CACHING_MS = 3000`
(`coerceAtMost`) before passing it to BOTH the LibVLC instance options and the
per-Media options. Larger user "Buffer size" tiers may still grow the ExoPlayer
buffer (ExoPlayer has no such bound) — they just stop growing the VLC one. VOD
uses its own caching constant, separate from this. So on the libVLC live path you
cannot fight steady-state underruns by raising caching past 3s; the real levers
are connection quality or routing the channel to the Exo path.
