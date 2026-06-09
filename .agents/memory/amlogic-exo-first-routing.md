---
name: Amlogic deterministic EXO-first routing
description: Why Amlogic boxes must pick the start engine by hardware detection, not runtime green detection
---

On Amlogic boxes the libVLC hardware underlay greens out on EVERY non-UHD profile (VLC logs `output: 17 unknown`). The reactive runtime green detection (keyed on libVLC `currentVideoTrack` width/height/fps) is UNRELIABLE there — `currentVideoTrack` frequently reads 0x0/null, so the "green-prone -> software fallback" routing never fires and the box stays green on every channel.

**Rule:** route the start engine deterministically by `DeviceCaps.isAmlogic`, do NOT rely on runtime track reads to escape green.
- AUTO on Amlogic -> start ExoPlayer (MediaCodec->SurfaceView, the box's working hardware-video path, no green; EXO renders to its own Surface so the libVLC format-17 issue doesn't apply).
- Explicit VLC choice on Amlogic -> start VLC_SW (only non-green libVLC path).
- EXO audio/error fallback on Amlogic -> VLC_SW, NEVER VLC_HW (VLC_HW greens).

**Why:** field logcat showed 44s + many zaps with AUTO starting VLC_HW, green every channel, zero fallback lines. User picked EXO-first (hardware quality, avoids the macroblocking that full-software-default causes on weak sticks).

**How to apply:** in `PlayerController.initialStage()` the `DeviceCaps.isAmlogic` branch must sit AFTER the explicit-EXOPLAYER and SOFTWARE-decoder branches but BEFORE the generic VLC_HW `else`. `DeviceCaps.isAmlogic` returns false in pure-JVM unit tests (no Android Build/MediaCodecList), so non-Amlogic ladder tests keep the VLC_HW behavior.
