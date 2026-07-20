---
name: libVLC blocking stop = ANR on slow network
description: Why all blocking libVLC calls run on the shared VlcOps thread, and the ordering/suppression guards that pattern needs.
---

# libVLC blocking stop() → ANR on slow network

**The rule:** never call libVLC `MediaPlayer.stop()` / `release()` (or the
stop→setMedia→play sequence) on the main thread. They are SYNCHRONOUS and block
until the input/demuxer thread winds down — on a stalled/slow network that is
many SECONDS. Route them through the shared `VlcOps` singleton HandlerThread
(one app-wide FIFO thread, never quit). ExoPlayer/Media3 calls are async and
MUST stay on main — do not move them.

**Why:** field report: on slow internet, a channel that failed to open froze
the whole app (ANR). Every `play()` stops first (single-connection contract)
and the failure ladder re-enters play()/stop() on retries, so the main thread
blocked over and over. VLC-Android's own app runs player ops off the UI thread;
libVLC's API is thread-safe from any thread (view attach/detach stays on main).

**How to apply — the pattern needs ALL of these guards:**
- **One shared FIFO thread** (not per-engine): serializes old-engine release
  before next-engine play across instances → single-connection preserved.
  PlayerController's engine-swap trampolines its delayed create through VlcOps
  (`postDelayed { VlcOps.post { mainHandler.post(create) } }`).
- **Seq token (opsSeq)**: play/stop/release bump it on main; queued runnables
  no-op when superseded (fast zap must not serially execute N blocked stops).
  Re-check AFTER the stop too — don't start a channel the user zapped away from.
- **Event suppression (pendingOps)**: while a stop/play is queued/executing,
  drop MediaPlayer events — they belong to the PREVIOUS media; a stale
  EncounteredError would otherwise fire the failure ladder for the old channel.
  Pair every increment with try/finally decrement (skip paths included).
- **release() split**: main = setEventListener(null) + detachViews + null
  fields; worker = stop/release/release on captured locals.
- **Controller release() must bump startGeneration**: a create parked on the
  VlcOps queue survives `mainHandler.removeCallbacksAndMessages(null)`; without
  the bump it later builds a ghost engine into a destroyed screen.
- **VOD path mirrored** (separate libVLC setup): onDestroy + UHD-escalation
  teardown on VlcOps; escalation rebuild and the initial setMedia+play chained
  on the same FIFO so they run after the old connection closed.
- Quick native getters (time, currentVideoTrack, delay setters) are fine on
  main/event threads.

**Residual accepted gap:** cross-activity VLC teardown → brand-new EXO start
(e.g. exiting VOD straight into a live channel on Exo) can briefly overlap two
connections; fixing it would make first-start async everywhere — not worth it.
