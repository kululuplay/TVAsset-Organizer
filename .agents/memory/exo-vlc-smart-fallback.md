---
name: ExoPlayer→libVLC smart auto-fallback
description: How the live player auto-recovers from green-screen and silent-audio decode failures.
---

# Smart auto-fallback ladder (live playback)

PlayerController runs a per-stream stage ladder: EXO → VLC_HW → VLC_SW.
- ExoPlayer is tried first.
- Reason mapping on failure: green/blank video → VLC_SW (software); silent
  audio or hard error → VLC_HW first.
- `DecoderMode` setting overrides the start: SOFTWARE starts at VLC_SW;
  HARDWARE blocks VLC_SW entirely (no software stage).
- `triedStages` guards against re-entering a stage (no loops).

**Why:** Real sticks show GREEN SCREEN on interlaced 1080i and NO AUDIO on
eac3/aac. FFmpeg Exo audio extension is infeasible here (no Maven prebuilt), so
libVLC's software path is the only safety net.

## Detection signals (ExoPlayerEngine)
- Silent audio: a `C.TRACK_TYPE_AUDIO` group exists but none `isSelected`.
  This is **debounced** — `onTracksChanged` schedules a recheck (~1.2s) that
  only fires `onAudioUnavailable()` if the player is still `STATE_READY`.
  **Why:** track selection transiently reports no selected audio while the
  selector settles; firing immediately caused false fallbacks.
- Video failure: **no first frame after READY** within a timeout → onVideoInvalid.
  There is NO pixel-green sampling anymore — ExoPlayer now outputs to a
  **SurfaceView** (can't getBitmap), and the SurfaceView fix removes the green
  cause at source (see green-screen Amlogic memory). A green-but-rendering frame
  is the one case no longer auto-detectable; relies on the surface fix holding.
- Passthrough OFF (default) forces PCM via
  `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` so Dolby/DTS that the TV
  falsely advertises as passthrough-capable still gets decoded to audible PCM.

## Surface / VLC rules (critical — see android-green-screen-vlc-texture.md)
- BOTH engines output to **SurfaceView**, never TextureView (Amlogic underlay).
- Keep direct rendering ON (do NOT add `--no-*-dr`).
- Auto-deinterlace (`--deinterlace=-1`, mode yadif / bob on software).
- RV32 display chroma. `:no-spdif` (PCM) when passthrough off. `--avcodec-hw=none`
  + `setHWDecoderEnabled(false,false)` when forcing software.

**How to apply:** keep ExoPlayerEngine/VlcPlayerEngine ctors and PlayerController
stage logic in sync. PlayerController's new ctor params (decoderMode,
allowPassthrough) are defaulted so MiniPlayerView's named-arg call still builds.
Diagnostics: PlaybackLog writes to external files dir (FileProvider-exposed),
shareable from Settings. VodPlayerActivity uses its own libVLC instance — apply
the same surface/DR/deinterlace rules there too.
