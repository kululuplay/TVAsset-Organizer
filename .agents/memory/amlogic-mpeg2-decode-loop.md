---
name: Amlogic MPEG2 hardware decode-failure loop
description: Why SD MPEG2 channels infinitely re-fail on Amlogic and how the controller escalates to software
---

SD MPEG2 channels (e.g. 720x576, OMX.amlogic.mpeg2.decoder) on Amlogic render a
frame, play ~1.5s, then fail with ERROR_CODE_DECODING_FAILED. The reconnect path
restarted the SAME hardware decoder, and the brief first frame reset the retry
counter every cycle → infinite loop, never escalating off hardware.

Fix (two layers, both needed):
- **Proactive**: ExoPlayerEngine routes `DeviceCaps.isAmlogic && VIDEO_MPEG2` to
  software the moment the format is seen (in maybeFlagGreenProneProfile →
  onVideoInvalid), before the glitch. SD MPEG2 is light, so SW is fine.
- **Reactive safety net**: a quick-hardware-decode-failure counter in
  PlayerController. Decoder-specific Exo errors come through a dedicated
  `onDecodeError` (Reason.DECODE); a plain ERROR on a hardware stage that only
  `playedBriefly()` is also counted. After MAX_QUICK_DECODE_FAILURES it forces
  the stream onto VLC_SW.

**Why the counter must NOT reset on a brief first frame:** that was the original
bug — onPlaying/onVideoOutput immediately reset the reconnect window, so each
1.5s loop looked like a fresh recovery and escalation never fired.

**How to apply:** recovery is only credited after surviving STABLE_PLAYBACK_MS
(stableHandler), via onPlaybackProgress — NOT on the first frame. The counter
resets on a new channel (play()), a genuine stage change (startStage target !=
stage), and the stable window. A same-stage reconnect KEEPS the count so the loop
escalates after a couple of failures. Decoder error classification = Media3
ERROR_CODE_DECODING_FAILED / DECODER_INIT_FAILED / DECODER_QUERY_FAILED /
DECODING_FORMAT_UNSUPPORTED / DECODING_FORMAT_EXCEEDS_CAPABILITIES.
