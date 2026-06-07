---
name: VOD vs live TV playback paths diverge
description: VOD and live TV use two separate libVLC setups that must be kept in lockstep for decode/anti-stutter tuning.
---

VOD (movies/series) and live TV use **two independent playback code paths**:

- Live TV: `PlayerController` → `VlcPlayerEngine` (also has the ExoPlayer ladder + DecoderMode fallback).
- VOD: `VodPlayerActivity` builds its own `LibVLC`/`MediaPlayer` directly (no controller, no ladder).

**Rule:** any libVLC decode/anti-stutter tuning change on one path must be mirrored on the other, or the two drift and one stutters while the other is smooth.

**Why:** VOD originally hardcoded hardware decode and lacked the live-TV anti-stutter options, so movies stuttered while channels were fine. The stutter-relevant options are `--clock-jitter=0`, `--clock-synchro=0`, `--avcodec-skiploopfilter=all`, `--avcodec-fast`, software-only bob deinterlace, plus DR disabled (`--no-mediacodec-dr`/`--no-omxil-dr`) — see the Amlogic green-screen note for the display rules.

**How to apply:** VOD honours the global `DecoderMode` via `forceSoftware = getDecoderMode() == SOFTWARE`; read the setting BEFORE constructing `LibVLC` (decoder mode shapes the option list). Default DecoderMode is SOFTWARE.
