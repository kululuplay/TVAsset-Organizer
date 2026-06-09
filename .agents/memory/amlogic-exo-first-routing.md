---
name: Amlogic AUTO = ExoPlayer + Hardware (proven best path)
description: On Amlogic, AUTO must default to ExoPlayer+HW and only deviate reactively; do NOT pre-emptively scatter channels to libVLC/software
---

Real-device testing on the Amlogic stick (definitive, supersedes earlier
green-screen theories about ExoPlayer):
- **ExoPlayer + Hardware = flawless for EVERYTHING**, including 1080p@50 and UHD/4K.
  The native MediaCodec->SurfaceView pipeline is the box's proper hardware path.
- **libVLC + Hardware** works EXCEPT some UHD channels stutter.
- The old "Amlogic H.264 1080p@50 greens out" finding was a **libVLC vout** problem,
  NOT a MediaCodec one. Do not apply it to the ExoPlayer path on Amlogic.

**Rule:** On Amlogic, AUTO (PlayerMode.AUTO + DecoderMode.AUTO) starts on EXO
(initialStage already does this) and STAYS there. Deviate from ExoPlayer+HW only
REACTIVELY, per single stream:
- genuine decode failure (onDecodeError / no-frame watchdog / quick-decode-failure
  counter) → that one stream to VLC_SW, back to EXO on the next channel;
- the genuinely-broken MPEG2 codec (proactive MPEG2->SW is fine — it actually fails).

**Why pre-emptive routing was the bug:** `ExoPlayerEngine.maybeFlagGreenProneProfile`
proactively bounced H.264 1080p+high-fps (incl. UHD) off ExoPlayer via onVideoInvalid
→ VLC_SW → (UHD can't SW-decode) onSoftwareTooSlow → VLC_HW = libVLC hardware, the
exact path that stutters on UHD. So AUTO turned perfectly-working ExoPlayer channels
into stuttering libVLC ones. Fix: `if (DeviceCaps.isAmlogic) return` before the H.264
heuristic — keep the heuristic only for non-Amlogic explicit-ExoPlayer users.

**How to apply:** never add a PROACTIVE (format-detection, pre-failure) engine/decoder
downgrade on the Amlogic ExoPlayer path except for codecs that genuinely always fail
there (MPEG2). Manual Settings choices (explicit ExoPlayer/libVLC + Hardware/Software)
are unaffected — only AUTO's default heuristics changed.
