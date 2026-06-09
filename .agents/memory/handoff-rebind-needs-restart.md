---
name: Preview<->fullscreen hand-off must restart, not bare-reattach
description: Why the seamless surface rebind has to re-issue play() on the same engine, or video goes black with audio
---

The live preview <-> fullscreen single-connection hand-off re-homes the running PlayerController's engine onto the other screen's container via `PlayerController.rebind()` (detach video -> 250ms gap -> attach to new container).

**Bug:** a BARE re-attach (no new play) leaves the decoder's video output bound to the OLD, now-destroyed SurfaceView. Result: audio keeps playing (surface-independent) but NO frames composite -> black picture (worst on the Amlogic MediaCodec underlay). And libVLC emits no new `Vout` (and Exo no new first-frame) on attach-only, so `onVideoOutput`/`onVideoResumed`/`onPlaying` never fire -> the fullscreen hand-off cover AND Home's preview channel-logo never clear. A channel zap "fixed" it precisely because zap calls `play()`, which rebuilds the output.

**Rule:** after the delayed `attachVideo(newContainer)`, re-issue `eng.play(currentUrl)` on the SAME engine (same stage). This recreates the video output on the new surface and emits a fresh frame event. Single-connection is preserved because every `engine.play()` stops the current stream before starting. The engine listener stays attached across rebind, so a restart that greens still falls through the controller's fallback ladder.

**Why:** mid-playback SurfaceView swap does not re-establish decoder output on the new surface; ExoPlayer needs an explicit `stop()` before `setMediaItem/prepare` for a deterministic renderer reset (a bare PlayerView re-add can stay audio-ready/video-stalled).

**How to apply:** both hand-off directions call `rebind()` (forward: PlayerActivity adopt; reverse: HomeActivity.reAdoptHandedBackPreview), so fixing `rebind()` covers both. Decouple UI from the frame event too: Home arms a `HANDOFF_LOGO_TIMEOUT_MS` (~6s, mirrors the fullscreen cover) to force-clear the preview logo if no frame event arrives, cancelled the moment `onPlaying`/`onVideoResumed` fires. Hand-off is now a reconnect boundary, not a pure surface move — old "no reconnect" comments are stale.
