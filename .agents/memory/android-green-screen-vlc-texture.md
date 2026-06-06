---
name: Android TV green-screen on live VLC
description: When green-frame-with-audio persists on libVLC live playback despite disabling direct rendering, switch to TextureView.
---

On some Android TV panels libVLC shows a solid green picture (audio fine) for live channels.

The rule (in order of escalation):
1. First mitigation: VLC options `--no-mediacodec-dr` and `--no-omxil-dr` (disable hardware direct rendering). This fixes most cases, including VOD (H.264).
2. If green still persists for **live** streams (often different/HEVC codecs) even with those options, the SurfaceView hardware-overlay presentation path is the culprit. Fix: attach the VLCVideoLayout with `useTextureView=true` — i.e. `mediaPlayer.attachViews(layout, null, subtitles, /*useTextureView=*/ true)`. TextureView routes decoded frames through the GPU and bypasses the overlay/color path.

**Why:** `--no-*-dr` only changes how decoded frames reach the surface, not the surface type. Some panels still mishandle YUV overlays on a SurfaceView; TextureView (GPU sampling) avoids it entirely. The ExoPlayer path already used `app:surface_type=texture_view` for the same reason.

**How to apply:** VOD path (`VodPlayerActivity`) works on SurfaceView so it was left alone; only the live engine (`VlcPlayerEngine`) was switched to TextureView. Tradeoff: TextureView uses slightly more GPU/power. If an edge device regresses with TextureView, a runtime SurfaceView/TextureView toggle would be the next step.
