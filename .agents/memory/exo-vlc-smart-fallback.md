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

**Why:** Real sticks show GREEN SCREEN on ac3/mp2 1080p50 and NO AUDIO on
eac3/aac. FFmpeg Exo audio extension is infeasible here (no Maven prebuilt), so
libVLC's software path is the only safety net. An FFmpeg-free design was a
hard constraint.

## Detection signals (ExoPlayerEngine)
- Silent audio: a `C.TRACK_TYPE_AUDIO` group exists but none `isSelected`.
  This is **debounced** — `onTracksChanged` schedules a recheck (~1.2s) that
  only fires `onAudioUnavailable()` if the player is still `STATE_READY`.
  **Why:** track selection transiently reports no selected audio while the
  selector settles; firing immediately caused false fallbacks.
- Green/blank video: sample the PlayerView TextureView via `getBitmap`
  (after first frame + delay); also a no-first-frame timeout after READY.
- Passthrough OFF (default) forces PCM via
  `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` so Dolby/DTS that the TV
  falsely advertises as passthrough-capable still gets decoded to audible PCM.

## VLC hardening (both VlcPlayerEngine and VodPlayerActivity)
`--android-display-chroma=RV32` + `--no-mediacodec-dr`/`--no-omxil-dr` +
`attachViews(useTextureView=true)` clears green frames. `:no-spdif` (PCM) when
passthrough off. `--avcodec-hw=none` + `setHWDecoderEnabled(false,false)` when
forcing software.

**How to apply:** keep ExoPlayerEngine/VlcPlayerEngine ctors and PlayerController
stage logic in sync. PlayerController's new ctor params (decoderMode,
allowPassthrough) are defaulted so MiniPlayerView's named-arg call still builds.
Diagnostics: PlaybackLog writes to external files dir (FileProvider-exposed),
shareable from Settings.
