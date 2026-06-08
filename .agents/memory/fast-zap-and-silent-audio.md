---
name: Fast zap (engine reuse) & VLC silent-audio self-heal
description: Why same-stage zaps reuse the live engine, and why VLC silent-audio self-heals in-engine instead of swapping to ExoPlayer
---

# Fast channel zapping via engine reuse
PlayerController.play() reuses the alive engine when the next stream would start
on the SAME stage (`engine != null && stage == initialStage()`); it just calls
`engine.play(newUrl)` instead of release+recreate.

**Why:** every zap otherwise tore down the engine, waited the 250ms
ENGINE_SWAP_DELAY_MS surface-handoff guard, and built a brand-new LibVLC/ExoPlayer
instance — slow. Reuse keeps the SurfaceView attached, so it's near-instant AND
sidesteps the swap-gap green frame (no surface teardown).

**How to apply:**
- Reuse is gated to steady state. If the current stream had already fallen back to
  a different stage (e.g. VLC_SW while initial is VLC_HW), play() drops through to a
  full restart so the new channel still gets the whole hardware-first ladder. Don't
  widen the gate to "any alive engine".
- VLC reuse REQUIRES stop-before-start: VlcPlayerEngine.play() must `mp.stop()`
  before swapping `mp.media` (single-connection contract). On a fresh engine that
  stop() is a harmless no-op.
- ExoPlayer reuse is free — setMediaItem replaces the current item.
- play() still does `mainHandler.removeCallbacksAndMessages(null)` first, so pending
  delayed creates/retries are cancelled before reuse; startGeneration guard only
  matters on the swap (non-reuse) path.

# VLC silent-audio: in-engine self-heal, NOT an engine swap
When a VLC stream comes up with audio present but no track selected
(`audioTrack == -1` while `audioTracks` has a real `id != -1` entry), re-select the
first real track a short delay after Playing. One-shot per stream (audioHealAttempted).

**Why:** the literal "VLC silent → switch to ExoPlayer" request is a trap on Amlogic
boxes — ExoPlayer greens the underlay video there, so the swap would trade silence
for a green screen. The common 5.1-too-many-channels silence is already fixed by the
forced stereo downmix (`--stereo-mode=1` / `:stereo-mode=1`). So a controller-level
VLC→EXO ladder edge was deliberately NOT added.

**How to apply:** keep audio recovery inside VlcPlayerEngine (re-select track); never
emit onAudioUnavailable from the VLC path on Amlogic. The check only acts when
`audioTrack == -1`, so it can't false-fire during normal playback (VLC auto-selects a
positive id then).
