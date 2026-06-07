---
name: Android TV green-screen on libVLC + ExoPlayer (Amlogic underlay)
description: Surface type, direct rendering, and deinterlace rules that keep hardware video visible (no green screen) on Amlogic Android TV sticks.
---

Real-device logcat on an Amlogic stick (OMX.amlogic.avc.decoder.awesome2)
pinned the green-screen root causes. The decoder runs fine and audio is correct
PCM stereo, but `E VLC: libvlc window: request 0/1/3 not implemented` spams and
the picture is green/blank. Cause = the hardware video is composited on a
dedicated UNDERLAY plane and the surface/window handler was wrong.

Rules (all confirmed convergent — new Amlogic logcat AND earlier user A/B):

1. **SurfaceView, never TextureView.** Both engines must output to a SurfaceView.
   - libVLC: `mp.attachViews(layout, null, subtitles, /*useTextureView=*/false)`.
   - ExoPlayer: `view_exo_player.xml` `app:surface_type="surface_view"`.
   A TextureView cannot show the Amlogic underlay plane → guaranteed green/black
   with working audio.
2. **Keep direct rendering ON.** Do NOT add `--no-mediacodec-dr` / `--no-omxil-dr`
   (nor `--no-drop-late-frames`/`--no-skip-frames`). DR-off copies frames out of
   the decoder, which fights the underlay and REINTRODUCES green (A/B-confirmed
   on the user's hardware; the known-good v1.0.0 never had these).
3. **Deinterlace ONLY on the software path — never on hardware.** The HW decoder
   outputs OPAQUE MediaCodec buffers (VLC logs `output: 17 unknown`) and the
   Amlogic chip deinterlaces 1080i natively on the underlay. A software
   deinterlace filter (yadif/bob) CANNOT touch opaque buffers, so the output
   side jams, buffers aren't recycled, and the decoder stalls
   (`libvlc decoder: dequeue_in timeout: no input available for 2secs`) = frozen
   video. Only add `--deinterlace=1`/`:deinterlace=1` + `bob` when `forceSoftware`
   (raw I420). Do NOT add `--deinterlace=-1` on the HW path "just in case" — the
   filter is still inserted and breaks opaque output.
4. **RV32 display chroma** (`--android-display-chroma=RV32`, fallback RV16) to
   match the NV12/biplanar output.
5. **Per-stream HW→SW fallback is the safety net only.** It catches a stuck
   decoder (no first frame / EncounteredError), NOT a green-but-present frame —
   so the surface/DR/deinterlace choices above are the real fix.

**Why:** green-but-present frames are invisible to any watchdog; the surface type
+ DR + deinterlace are what actually make the underlay render. Pixel-sampling
green detection is impossible on a SurfaceView (can't getBitmap), so it was
removed — the SurfaceView fix removes the need for it.

**How to apply:** Live = `VlcPlayerEngine.kt` + `ExoPlayerEngine.kt`
(+`view_exo_player.xml`); VOD = `VodPlayerActivity.kt` (own libVLC instance, same
rules). If green is reported again, re-verify surface type first, then that no
`--no-*-dr` crept back, then deinterlace. Re-tune one option at a time.
ExoPlayer FFmpeg audio extension is NOT available here (no Maven prebuilt).
