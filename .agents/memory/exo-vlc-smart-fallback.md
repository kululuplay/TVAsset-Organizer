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
  There is NO pixel-green sampling — ExoPlayer outputs to a **SurfaceView**
  (can't getBitmap).
- **Green-but-rendering is NOT runtime-detectable**: on the Amlogic green-prone
  profile the HW decoder DOES push a (green) frame, so `onRenderedFirstFrame`
  fires and the no-frame watchdog is satisfied — it never triggers. Any
  "confirmatory runtime symptom" idea fails for the same reason.
- **So detect it by FORMAT instead**, deterministically, at playback start:
  H.264 + (height≥1080 || width≥1920) + frameRate ≥ 49 → fire `onVideoInvalid`
  one-shot → controller routes THIS stream to VLC_SW; everything else stays HW.
  Exo reads `Format` in `onVideoInputFormatChanged` (gate on
  `MimeTypes.VIDEO_H264` — safe). libVLC reads `mediaPlayer.currentVideoTrack`
  (`width`/`height`/`frameRateNum`/`frameRateDen` — proven, VodPlayerActivity
  uses width/height) on the `Vout`/`Playing` event. Guard one-shot per stream
  (`greenCheckDone`, reset in play()); skip when already forceSoftware.
  **libVLC has NO codec gate**: `Media.Track.codec` field type isn't safely typed
  across libvlc-android versions (don't risk it); resolution + fps≥49 already
  isolates the failing profile. **Constants are per-class** — each engine needs
  its own `private const GREEN_PRONE_*` in ITS companion (referencing the other
  engine's private companion = unresolved reference, fails kspDebugKotlin).
  **Why fps≥49:** broadcast 1080i50 is signaled at ~25fps frame rate (works on
  HW); only progressive 1080p50/60 reports ≥49. This is the only discriminator
  available — neither Media3 Format nor libVLC VideoTrack exposes interlace.
  **Edge:** a 1080i stream signaled at field rate (50) would false-trigger SW;
  confirm against a real device log before tightening/raising the threshold.
- Passthrough OFF (default) forces PCM via
  `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` so Dolby/DTS that the TV
  falsely advertises as passthrough-capable still gets decoded to audible PCM.

## Surface / VLC display rules — DEFER to android-green-screen-vlc-texture.md
That note is the single source of truth for SurfaceView/DR/chroma decisions and
is NEWER than this file. Current state (verified in code): DR is **ON** on the
VLC HW path (the old `--no-mediacodec-dr`/`--no-omxil-dr` flags are gone — DR-off
greens the Xiaomi compositor; watch for the `output: 17`/`dequeue_in timeout`
freeze it once caused). Still true here:
- BOTH engines output to **SurfaceView**, never TextureView (Amlogic underlay).
- **No** `--android-display-chroma=RV32` (froze video).
- **No** deinterlace on the HW path; only `--deinterlace=1` + bob when forcing
  software (raw frames).
- `:no-spdif` (PCM) when passthrough off (audio-only, unrelated to the freeze).
  `--avcodec-hw=none` + `setHWDecoderEnabled(false,false)` when forcing software.

**How to apply:** keep ExoPlayerEngine/VlcPlayerEngine ctors and PlayerController
stage logic in sync. PlayerController's new ctor params (decoderMode,
allowPassthrough) are defaulted so MiniPlayerView's named-arg call still builds.
Diagnostics: PlaybackLog writes to external files dir (FileProvider-exposed),
shareable from Settings. VodPlayerActivity uses its own libVLC instance — apply
the same surface/DR/deinterlace rules there too.
