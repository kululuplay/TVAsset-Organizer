---
name: Android TV green-screen / doubled-image on libVLC
description: How libVLC video output is set up to avoid green screen and doubled/ghosted image on low-end Android TV sticks.
---

On some Android TV panels/sticks libVLC showed a solid green picture (audio fine) and/or a doubled/ghosted image on Live TV and VOD.

Current rule (this REVERSES the earlier "--no-mediacodec-dr / --no-omxil-dr + no-frame-drop" decision — that combination REINTRODUCED green screen on the user's actual hardware):

1. **Single video surface only.** Attach with `mp.attachViews(layout, null, /*subtitles=*/false, /*useTextureView=*/false)` — a plain SurfaceView, no TextureView, no separate subtitle surface. Two surfaces (TextureView + HW decode, or an extra subtitle surface) caused the doubled image. This part has held across every revision.
2. **Use the known-good v1.0.0 (commit 57e9100) decode options, NOT the direct-rendering ones.** Instance: `--clock-jitter=0`, `--clock-synchro=0`, `--avcodec-hw=any`, `--avcodec-skiploopfilter=all`, `--avcodec-fast`. Per-stream: `setHWDecoderEnabled(true, /*force=*/true)` + `:clock-jitter=0` + `:clock-synchro=0`. Buffer `network/live-caching=3000`. Do NOT add `--no-mediacodec-dr` / `--no-omxil-dr` / `--no-drop-late-frames` / `--no-skip-frames` — on this box they bring the green screen back.
3. **Per-stream hardware→software fallback stays as a safety net.** Each stream starts hardware-auto; on `EncounteredError`, or `Playing` with no `Vout` within ~6s, restart the same stream once forcing software (`setHWDecoderEnabled(false,false)` + `:avcodec-hw=none`), posted off the VLC event thread. Only after SW also fails is onError surfaced. This is additive and does not change the HW decode path that matters for green screen.
4. **Surface lifecycle.** Guard attach with `viewsAttached`; detach on pause/onStop, re-attach on resume/onStart so navigating Live↔VOD never leaves a stale double-attached surface.

**Why:** The user repeatedly confirmed v1.0.0 (57e9100) played cleanly and that the "direct-rendering off / no frame drop" tuning kept the green screen. The watchdog can detect "no video output" but NOT a green-but-present frame, so the decode-option choice (step 2) is the real fix; the SW fallback (step 3) only covers true HW-decode failures. Trust the user's known-good build over the theory that `--no-*-dr` is the universal green-screen fix.

**How to apply:** Live engine = `VlcPlayerEngine.kt`. Keep single-connection (stop-before-start), track selection, and the lifecycle/fallback machinery — they are orthogonal to the decode path. If green screen is ever reported again, re-verify these options match 57e9100 before adding anything new; re-tune one option at a time. ExoPlayer path still uses `app:surface_type=texture_view` (unchanged).
