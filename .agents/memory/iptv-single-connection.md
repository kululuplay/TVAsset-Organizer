---
name: IPTV single-connection contract
description: How Live TV + VOD players guarantee exactly one open stream connection (the subscription allows only one).
---

# Single open stream connection

Each subscription allows only ONE simultaneous connection. The server counts a
paused-but-connected player as an active slot and shows "this account is in use
on another device". So every player surface must hold at most one open socket and
free it the instant the user leaves.

## Rules (apply to any new player surface, e.g. Catch-up TV)
- **Stop before start.** Before opening a new stream always `stop()` the current
  one first (closes the socket). Never swap the media URL on a still-connected
  player — that briefly holds two connections. This covers the retry path and any
  software-decode restart, not just user zapping.
- **Serialize transitions.** Guard start/stop/release with a single lock
  (`transitionLock` / main thread) so a fast zap or a retry firing mid-switch
  can't interleave and open the new stream before the old one closed.
- **Release on background, not pause.** On `onStop`/background fully `stop()` the
  stream (close the socket) and detach the surface; remember the position and
  re-acquire (`play` from saved position) on `onStart`. Pausing keeps the socket
  (and the slot) alive — that was the original bug.
- **Reuse the engine, swap the Media.** `PlayerController.ensureEngine()` keeps a
  single LibVLC/ExoPlayer instance across zaps and only tears it down when the
  backend actually has to change, so two engines never live at once.

**Why:** fixes false "account in use" lockouts from lingering/background
connections and from overlapping connections during fast channel zapping.

**How to apply:** Live TV = `VlcPlayerEngine` + `PlayerController` +
`PlayerActivity` (`releaseStream`/`reacquireStream`). VOD = `VodPlayerActivity`
(`startPlayback` stops first; `onStop` stops+detaches; `onStart` re-acquires).
Buffer tuning: live network/live-caching ~1500ms (low-latency), VOD network/file
caching larger (~4000ms); keep `--http-reconnect` on both for auto-recovery.
