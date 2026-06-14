---
name: Live playback stall/startup watchdog
description: Why event-driven recovery alone can't survive long live sessions, and the progress-watchdog design that fixes the ~2h silent freeze.
---

# Live stall / startup watchdog

PlayerController recovery used to be ENTIRELY event-driven (libVLC EndReached /
EncounteredError, Exo errors) plus a startup no-frame check. A long single-channel
live session would play fine then SUDDENLY freeze/cut after ~2h, recoverable only by
killing the app.

**Root cause:** a half-open / silently-dropped upstream connection (NAT / load-balancer
idle or max-connection-age timeouts, or Xtream per-connection limits — these commonly
sit at ~1-2h) leaves the engine BLOCKED reading with NO EOF and NO error event. So
nothing fires, `:http-reconnect` never triggers (it needs a detected drop), and the
picture just freezes. There was no mid-stream progress/stall watchdog.

**Fix (the right ROOT fix — do NOT try to solve it mainly with libVLC socket options):**
poll the engine playback clock and force a fresh reconnect when it stops advancing.

- `PlayerEngine.playbackPositionMs(): Long = -1` — VLC `mediaPlayer.time`, Exo
  `currentPosition`. The clock advances while frames flow (incl. from cache during a
  top-up) and FREEZES once a silent network stall drains the buffer.
- Dedicated `watchdogHandler` (NOT mainHandler — its `removeCallbacksAndMessages(null)`
  calls would clobber the poll) + a `watchdogGen` guard so an already-dequeued post
  becomes a no-op.
- **Mid-stream stall poll:** armed on first confirmed frame (`onPlaybackProgress`),
  polls every 3s. Progress = `pos >= 0 && pos > lastPos + 250ms`. NEVER false-trigger
  on `pos < 0` (unknown → treat as progress). No advance for 15s → `engageReconnect()`
  (full release+recreate = fresh socket → server's per-conn limit resets). Re-arms on
  the next confirmed stable playback → protects each subsequent window → indefinite
  stability.
- **Startup/reconnect-attempt timeout:** armed in `startStage` (after `startEngine`)
  and the `play()` fast-zap branch; if `playbackConfirmed` is still false after 22s
  (a socket that opens but delivers no data, firing no error), call
  `handleFailure(Reason.ERROR)` so the normal decode-ladder / reconnect sequencing runs
  (NOT a direct reconnect — preserves the ladder and avoids fighting the fast path).
- Cancel the watchdog at the top of `play()` and `engageReconnect()`, and in
  `pause()/stop()/release()`. Keep it inside the existing 45s RECONNECT_WINDOW.

**Gotcha — `rebind()` (preview↔fullscreen hand-off) is a restart boundary too.** It
replays on the SAME engine with `reset=true`, which resets the engine clock toward 0.
If you don't also `cancelWatchdog()` + set `playbackConfirmed=false` + clear the stable
timer around that replay, the stale large position baseline reads the near-zero clock as
"no progress" and false-reconnects ~15s after every hand-off. Any path that replays
WITHOUT going through `play()`/`startStage()` must reset watchdog state the same way.

**Why these thresholds:** 15s stall is long enough not to fight a brief hiccup that
libVLC/`:http-reconnect` rides out, short enough the user isn't stuck staring; 22s
startup is generous so a slow-but-healthy start (connect + ~3s caching + first frame)
never trips. Do NOT touch the 3000ms VLC live-caching cap or Amlogic surface/decode
constraints to chase this.
