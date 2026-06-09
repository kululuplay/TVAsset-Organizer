---
name: ExoPlayer fast-zap must not stop()
description: Why the EXO fast-zap path must avoid exo.stop(); only the hand-off rebind resets.
---

On Amlogic boxes, `ExoPlayer.stop()` fully tears down and recreates the OMX
hardware decoder (~600ms): logcat shows `AmlogicVideoDecoderAwesome2` stop/tearDown/dtor
then a fresh `makeComponentInstance`. That teardown IS the CH+/CH- channel-zap freeze.

**Rule:** the engine `play()` takes a `reset` flag. Fast-zap reuse and fresh/fallback
starts use `reset=false` (just setMediaItem+prepare on the live player — reuses the
decoder). ONLY the preview<->fullscreen hand-off (`PlayerController.rebind`) passes
`reset=true` to force a deterministic decoder/renderer reset onto the re-attached surface.

**Why:** an earlier unconditional `exo.stop()` (added to fix black-video-with-audio on
hand-off) leaked onto the fast-zap path and stuttered every zap. libVLC is unaffected —
it always stops + builds a fresh Media (single-connection contract), `reset` is ignored.
