---
name: Android TV green-screen / frozen video on libVLC + ExoPlayer (Amlogic underlay)
description: Surface type and libVLC decode/display options that keep hardware video visible (no green/frozen frame) on Amlogic Android TV sticks.
---

Real-device logcat on an Amlogic stick (OMX.amlogic.avc.decoder.awesome2)
pinned the green-screen / frozen-video root causes. The decoder runs fine and
audio is correct PCM stereo, but the picture is green/blank or freezes.

## The rule that actually matters: match the known-good v1.0.1 libVLC options
The user's regression-free baseline (tag v1.0.1 / commit c8e9eac) used a SMALL,
specific set of options. Every green/freeze regression since came from ADDING
options on top of it. When in doubt, restore the baseline decode/display config:

1. **SurfaceView, never TextureView.** Both engines must output to a SurfaceView.
   - libVLC: `mp.attachViews(layout, null, subtitles, /*useTextureView=*/false)`.
   - ExoPlayer: `view_exo_player.xml` `app:surface_type="surface_view"`.
   A TextureView cannot show the Amlogic underlay plane → green/black + audio.
2. **libVLC direct rendering — history is contradictory; DR is now ON on the HW
   path.** The v1.0.1 baseline had `--no-mediacodec-dr` + `--no-omxil-dr` (DR off),
   and DR-on once FROZE this box (`output: 17 unknown` → `dequeue_in timeout`). BUT
   DR-off greens on the Xiaomi compositor (see #3), and the freeze was observed
   *with a forced display chroma also set*. Current direction: drop the chroma
   override AND remove the no-dr flags so the Amlogic decoder renders straight onto
   the SurfaceView underlay (its native HW path). `forceSoftware` sets
   `avcodec-hw=none`, so DR is irrelevant on the software path. **If DR-on freezes
   again** (watch for `output: 17 unknown` + `dequeue_in timeout`), libVLC HW is a
   dead end on this box → rely on ExoPlayer (#3a) or SOFTWARE decode.
3. **Display-chroma override (RV16/RV32) is a DEAD END for HW decode — CONFIRMED.**
   On HARDWARE/MediaCodec decode the frames are opaque native buffers the Amlogic
   compositor paints directly onto its video underlay plane; `--android-display-chroma`
   only affects libVLC's own `android_display` vout, which never touches those
   buffers. Real-stick log (Xiaomi, decoder=HARDWARE) with `--android-display-chroma=RV16`
   set: output STAYED `output: 21 Biplanar 4:2:0 Y/UV` (1920x1080 + 720x576) — the
   override had ZERO effect and the screen stayed green. RV32 additionally froze
   (`output: 17 unknown`). Both removed. Do not reach for a chroma override to fix a
   HW-decode green again.
3a. **ExoPlayer/Media3 is the primary HW-green candidate on Amlogic.** ExoPlayer
   hands the SurfaceView straight to MediaCodec (decode → underlay), which is the
   box's native HW-video pipeline and is exactly what greens under libVLC's
   `android_display` vout. Real-stick result (Xiaomi, decoder=HARDWARE): libVLC HW
   = green from the start (its vout never reaches the underlay); ExoPlayer HW =
   REAL VIDEO for ~1s, then drops to green. So ExoPlayer is the right engine — the
   first frames PROVE the surface/underlay wiring works; only the ~1s drop must be
   fixed. Route to ExoPlayer when libVLC HW greens, not SOFTWARE (which stutters).
   **Routing trap fixed:** `initialStage()` now checks `PlayerMode.EXOPLAYER`
   BEFORE the `DecoderMode.SOFTWARE` default — otherwise the Software default
   short-circuited to `VLC_SW` and ExoPlayer was never reachable.
3b. **ExoPlayer "1 second then green" = SurfaceView resize-recreate (prime
   suspect).** PlayerView's default `resize_mode=fit` resizes the SurfaceView when
   the video size arrives (~1s after first frames) to honour aspect ratio; a
   SurfaceView bounds change tears down + recreates its surface, and the Amlogic
   compositor greens the freshly-created surface — same root cause as the EXO→VLC
   swap green, just self-inflicted by the aspect relayout. Fix: `resize_mode=fill`
   in `view_exo_player.xml` keeps the surface at a CONSTANT size (created once).
   Tradeoff: fill ignores aspect (16:9-on-16:9 is fine; SD 4:3 stretches). Added
   `onVideoSizeChanged`/`onSurfaceSizeChanged`/`onRenderedFirstFrame` +
   AnalyticsListener (decoder name, input format, dropped frames, codec error)
   logging to confirm from the next log whether it's the resize, interlaced field
   decoding, or a decoder error. **If fill does NOT stop the green**, the log will
   show a codec error / dropped-frame burst at the drop = interlaced HW-decode
   fault, not the surface.
4. **No deinterlace on the hardware path.** Software deinterlace (yadif/bob) can't
   touch opaque HW buffers; the Amlogic chip deinterlaces 1080i natively. Only add
   `--deinterlace=1` + `bob` when `forceSoftware` (raw I420) — and even that is
   optional (baseline had none). Never `--deinterlace=-1` on the HW path.
5. **`--no-spdif` is fine and unrelated to video.** It only forces AC-3/E-AC-3/DTS
   to decode to stereo PCM (audible on plain HDMI). Keep it; it does not affect the
   green/freeze problem either way.
6. **Per-stream HW→SW fallback is a safety net only.** It catches a stuck decoder
   via EncounteredError, but a freeze with NO error (the opaque-DR stall here) does
   NOT trip it — so the options above are the real fix, not the ladder.

## Diagnostic signal (how to read the logcat)
The single fastest tell in `E VLC: libvlc decoder: output: …`:
- `output: 17 unknown` = BROKEN — VLC got an opaque/unrecognized buffer (the
  DR-on or forced-chroma drift). Always followed by `dequeue_in timeout: no input
  available for 2secs` and frozen video.
- `output: 21 Biplanar 4:2:0 Y/UV, 1920x1080` = HEALTHY — VLC recognizes the NV12
  output and renders it. Confirmed working on the Amlogic box after restoring the
  v1.0.1 options.
`E VLC: libvlc window: request 0/1/3 not implemented` is BENIGN noise — it appears
in the known-good baseline too; do NOT chase it. Repeated `[Controller] play` =
the USER zapping channels, NOT an auto-restart loop (the controller logs that line
only from `play()`, called solely on channel select; `dequeue_in timeout` is a VLC
internal that never reaches the controller's onError, so it cannot trigger retry).

## Engine-handoff green (different cause than the DR/chroma green)
Symptom: green ONLY on channels that fall back EXO -> VLC (e.g. MP2/AC-3 audio the
ExoPlayer can't decode), while channels that start directly on VLC are fine — and
the VLC log shows a perfectly HEALTHY decode (`output: 21 Biplanar`, audio plays).
Cause: SurfaceView swap race. The controller releases one engine (removes its
SurfaceView) and creates the next engine's SurfaceView + starts playback in the
SAME synchronous pass; the old surface tears down ASYNC on the render thread, so
the Amlogic compositor shows a green frame on the freshly-added surface. Direct-VLC
works because the container started empty (no prior SurfaceView to tear down).
**What's been learned (the swap, not just timing):**
- A ~250ms delay between releasing the old engine's SurfaceView and creating the
  new one (`PlayerController.startEngine`, only when swapping) helps but is NOT a
  guaranteed cure.
- Critical clue: SOFTWARE video after the same swap NEVER greens, but it STUTTERS
  on 1080p (too heavy for this box). HARDWARE after the swap greens. So the real
  culprit is the Amlogic HW video decoder being handed a freshly-swapped surface
  right after ExoPlayer used the same OMX decoder — a decoder/surface handoff
  conflict, not pure timing. Green is NOT auto-detectable (no signal reaches
  onError), so you cannot drive a runtime HW->SW green fallback for it.
- Therefore neither auto path is good: HW-after-swap = green, SW = stutter.
- The clean cure that keeps HARDWARE and avoids green is to AVOID THE SWAP:
  start directly on `VLC_HW` (no ExoPlayer first) -> no swap, hardware decode, no
  green, no stutter (confirmed by the user's clean direct-VLC log). So AUTO mode
  starts on libVLC hardware (only the explicit ExoPlayer player mode starts on Exo);
  AUTO still falls back VLC_HW -> VLC_SW on a real failure. **Why:** ExoPlayer-first
  forces the green-prone swap on the very channels that need libVLC anyway. Tradeoff
  the user accepted: ExoPlayer is no longer the first engine in AUTO.
- Do NOT route the EXO `--AUDIO-->` fallback to VLC_SW (tried it: removes green but
  causes 1080p stutter). Do NOT switch VLC to TextureView — TextureView greens the
  working direct-VLC path on this box.

**How to apply:** Live = `VlcPlayerEngine.kt` + `ExoPlayerEngine.kt`
(+`view_exo_player.xml`); VOD = `VodPlayerActivity.kt` (own libVLC instance, same
rules). If green/freeze is reported again, diff the current libVLC options against
the v1.0.1 baseline and remove whatever was added (chroma override, DR-on,
deinterlace) before inventing anything new. Re-tune one option at a time.
ExoPlayer FFmpeg audio extension is NOT available here (no Maven prebuilt).
