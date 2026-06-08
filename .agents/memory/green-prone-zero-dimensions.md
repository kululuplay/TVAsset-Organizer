---
name: Green-prone routing must ignore 0x0 dimensions
description: Why the libVLC green-prone software-fallback fires wrongly at startup and drops 4K HEVC to an unplayable software decode.
---

# Green-prone routing must wait for real video dimensions

**Rule:** The VLC green-prone routing decision must bail when the current video
track reports `width <= 0 || height <= 0`, NOT just when fps is unknown.

**Why:** On the first Playing/Vout event (and before the video track is parsed)
libVLC frequently reports `0x0`. The Amlogic branch of the heuristic is
`greenProne = !isUhd`, and `0x0` is `!isUhd == true`, so it fires a premature
software fallback. For a genuine 4K HEVC stream this is fatal: software cannot
decode 4K at high bitrate in real time, so the stream stutters/black-screens.
Symptom in logcat: `green-prone HW profile 0x0@0.0fps -> software fallback`
immediately followed by `fallback VLC_HW --VIDEO--> VLC_SW`, then an OMX HEVC
decoder spinning up at 3840x2160.

**How to apply:** In `VlcPlayerEngine.maybeRouteByProfile()` return early on
non-positive dimensions WITHOUT calling `claimRouting()` (so `greenCheckDone`
stays false and the scheduled rechecks / Buffering / Playing events re-run once
the true resolution is known — 4K then reads as `isUhd` and stays on hardware).
ExoPlayer's equivalent (`maybeFlagGreenProneProfile`) is already safe because it
requires `h264 && is1080p && highFps` all true, which `0x0` can never satisfy.
