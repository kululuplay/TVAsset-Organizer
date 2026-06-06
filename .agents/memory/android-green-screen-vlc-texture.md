---
name: Android TV green-screen / doubled-image on libVLC
description: How libVLC video output is set up to avoid green screen and doubled/ghosted image on low-end Android TV sticks.
---

On some Android TV panels/sticks libVLC showed a solid green picture (audio fine) and/or a doubled/ghosted image on Live TV and VOD.

Current rule (supersedes the earlier "switch live to TextureView" decision):

1. **Single video surface only.** Attach the media player with `mp.attachViews(layout, null, /*subtitles=*/false, /*useTextureView=*/false)` — a plain SurfaceView, no TextureView, and no separate subtitle surface. Embedded subtitles still render (blended onto the single video surface). Two surfaces (TextureView + hardware decode, or an extra subtitle surface) were the cause of the doubled image, and TextureView + hardware decode was re-triggering green frames on cheaper sticks.
2. **Keep the direct-rendering options** `--no-mediacodec-dr` and `--no-omxil-dr` plus `--avcodec-hw=any` (hardware decode, automatic). These fix most green-frame cases by copying decoded frames out instead of pushing them straight to the surface.
3. **Per-stream hardware→software fallback as the safety net.** Each stream starts at hardware-auto. If it raises `EncounteredError`, or fires `Playing` but no `Vout` (voutCount>0) appears within ~6s (audio-only / blank / green frame), restart the *same* stream once forcing software decode (`setHWDecoderEnabled(false,false)` + `:avcodec-hw=none`). Post the restart off the VLC event thread. Only after software also fails is the error surfaced. This replaces the old TextureView crutch for the panels that motivated it.
4. **Surface lifecycle.** Guard attach with a `viewsAttached` flag so `attachViews` is never called twice without `detachViews`. Detach on pause / onStop and re-attach on resume / onStart, so navigating between Live and VOD never leaves a stale, double-attached surface behind.

**Why:** The green frame is the decoder/overlay color path; the doubled image is two attached surfaces. The watchdog can detect "no video output" but cannot detect a green-but-present frame, so the option/surface changes (steps 1–2) are the real green-screen fix and the SW fallback (step 3) covers true HW-decode failures.

**How to apply:** Live engine = `VlcPlayerEngine.kt`; VOD = `VodPlayerActivity.kt`. Both use the single-SurfaceView attach, the vout watchdog + error fallback, and detach/attach across the lifecycle. The VLC engine's internal SW fallback runs *before* it reports onError, so it doesn't fight `PlayerController`'s Exo→VLC fallback or retry/backoff. ExoPlayer path still uses `app:surface_type=texture_view` (unchanged).
