---
name: Amlogic libVLC HW greens on ALL non-UHD profiles
description: Why VLC_HW green fallback must be hardware-gated and override HARDWARE decoder mode
---

On Amlogic boxes the libVLC hardware-video underlay greens out on **every** non-UHD
profile (1080i@25, 576p, 1080p@50…), not just the classic 1080p@50/60 H.264 case.
The green is a display-plane/compositor failure: the decoder decodes fine, audio +
position advance normally, and libVLC's high-level API exposes **no** signal that
distinguishes green from good (displayedPictures still increments; "output: 17
unknown" is the normal opaque-MediaCodec-DR output, not a green marker). So green is
NOT runtime-detectable — detect the **SoC** instead.

**Rule 1 — detect hardware, not green.** `DeviceCaps.isAmlogic` (MediaCodec list
name contains "amlogic", Build.HARDWARE/BOARD fallback, cached). On Amlogic, flag
the whole non-UHD libVLC hardware path as green-prone (`maybeRouteByProfile` →
`onVideoInvalid`). Non-Amlogic keeps the narrow 1080p@≥49fps heuristic. UHD always
stays on hardware (no CPU can software-decode 4K).

**Rule 2 — green overrides DecoderMode.HARDWARE.** In `PlayerController.nextStage`,
a `Reason.VIDEO` (green) failure from EXO or VLC_HW must drop to VLC_SW *regardless*
of decoder mode. Without this, "VLC + Hardware" sets `softwareAllowed=false` and the
user is stuck on a permanent green screen with no escape. Plain ERROR/AUDIO failures
still honor the HARDWARE "no software" preference.

**Why:** field logs (ExoPlayer+HW and VLC+HW) showed `output: 17 unknown` on 1080p,
1080i and 576p; VLC+HW never escalated because the profile heuristic only matched
1080p@50 AND HARDWARE mode forbade the software stage. ExoPlayer remains the box's
working hardware-video path (different pipeline); libVLC HW video is unusable here.

**How to apply:** any change to the fallback ladder must preserve both rules, and
the green→SW escalation must remain monotonic (guarded by `triedStages`) so it never
loops back to EXO within a single stream.
