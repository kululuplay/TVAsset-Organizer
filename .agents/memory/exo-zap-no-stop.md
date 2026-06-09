---
name: ExoPlayer zap must reset() on channel change (resolution reconfigure)
description: Why the EXO channel-change/zap path must exo.stop() before re-prepare; the no-stop optimization froze differing-resolution channels.
---

On Amlogic boxes, `ExoPlayer.stop()` fully tears down and recreates the OMX
hardware decoder (~600ms): logcat shows `AmlogicVideoDecoderAwesome2`
stop/tearDown/dtor then a fresh `makeComponentInstance`. To avoid that stutter
the fast-zap path once swapped streams with `setMediaItem+prepare` and NO
`stop()` (`reset=false`).

**That no-stop optimization caused a worse bug:** a no-stop swap can leave the
Amlogic OMX codec locked to the PREVIOUS channel's geometry. Symptom: enter
fullscreen on a 1080p channel and every 1080p plays but 720x576 channels FREEZE
(and vice-versa) — the channel that freezes is always the one whose resolution
DIFFERS from the resolution active when the codec was last configured. The new
frames can't render into the wrongly-sized decoder.

**Rule:** the engine `play()` takes a `reset` flag. The channel-change/zap path
(`PlayerController.play` fast-zap reuse branch) AND the preview<->fullscreen
hand-off (`PlayerController.rebind`) both pass `reset=true`, so ExoPlayer
`stop()`s before `setMediaItem+prepare` and rebuilds the video codec for the new
stream's resolution. Only a brand-new/fallback engine start uses `reset=false`
(nothing to reset).

**Why:** correctness beats the ~600ms zap gap — a clean decoder reconfigure is
far better than a permanently frozen picture on every channel whose resolution
differs from the one fullscreen started on. The `reset` does NOT recreate the
SurfaceView (only the decoder/renderer onto the same attached surface), so it
does not reintroduce the Amlogic green frame. libVLC is unaffected — it always
`stop()`s + builds a fresh `Media` every `play()` (single-connection contract),
so `reset` is a no-op there and VLC zaps already reconfigure resolution.

**How to apply:** keep the zap path `reset=true`. Do NOT "optimize" it back to a
no-stop swap to shave the zap latency — that is exactly what froze
mixed-resolution zapping. If a faster same-resolution path is ever wanted, it
must detect the resolution is unchanged before skipping the reset, not skip
unconditionally.
