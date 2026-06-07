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
2. **Direct rendering OFF.** Keep `--no-mediacodec-dr` + `--no-omxil-dr` (the
   baseline had them). VLC then reads decoded frames into its own pictures and
   renders via the `android_display` vout onto the SurfaceView.
   **Why:** the OPPOSITE (DR on / opaque direct render) FROZE on this Amlogic box
   — VLC logged `libvlc decoder: output: 17 unknown` then
   `libvlc decoder: dequeue_in timeout: no input available for 2secs` (frozen
   video, audio kept playing). A 1.1.x build that turned DR ON regressed this; the
   1.1.0/1.1.1 comment claiming "DR-off reintroduces green (A/B-confirmed)" was
   WRONG — that earlier green was the TextureView, not DR.
3. **Do NOT force a display chroma.** No `--android-display-chroma=RV32`. Forcing
   RV32 pushed VLC onto an unrecognized byte-buffer format (`output: 17 unknown`)
   and caused the same `dequeue_in timeout` freeze. The baseline never set it.
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

**How to apply:** Live = `VlcPlayerEngine.kt` + `ExoPlayerEngine.kt`
(+`view_exo_player.xml`); VOD = `VodPlayerActivity.kt` (own libVLC instance, same
rules). If green/freeze is reported again, diff the current libVLC options against
the v1.0.1 baseline and remove whatever was added (chroma override, DR-on,
deinterlace) before inventing anything new. Re-tune one option at a time.
ExoPlayer FFmpeg audio extension is NOT available here (no Maven prebuilt).
