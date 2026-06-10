---
name: Fire TV keep-screen-on / standby
description: Why the app drops to standby while watching and where keep-screen-on must be held
---

# Fire TV / Android TV standby while watching

The app dropping to standby/screensaver after ~5-10 min of playback is the
**system** screensaver/sleep, NOT any in-app timer. The cure is holding
keep-screen-on while content is on screen.

**Rule:** every video playback surface must hold keep-screen-on.
- Live fullscreen (`activity_player.xml`) and VOD (`activity_vod_player.xml`)
  use declarative `android:keepScreenOn="true"` on the root. Catch-up plays
  through `VodPlayerActivity`, so it's covered by the VOD root.
- The Home live preview is play-state-gated: set `previewVideo.keepScreenOn`
  true on play (onPlaying/onVideoResumed), false on stop/fatal — so Home only
  pins the display awake while a preview is actually rendering.

**Why:** no playback screen held `FLAG_KEEP_SCREEN_ON`. View/window
keep-screen-on only applies while that window is visible, so it auto-releases
on background — no wake-lock to leak.

**Trap — dead code that misleads standby debugging:** `PlayerScreenGuard`
(claims in its header to manage keep-screen-on for the players), `IdleWatcher`,
`ScreensaverActivity`, and `MiniPlayerView` all exist but are **never
instantiated**. Do not assume keep-screen-on is handled because
`PlayerScreenGuard` exists, and do not chase an in-app screensaver as the cause.

**How to apply:** when adding a new screen that renders video for long stretches
(e.g. a trailer/WebView player, a new mini-player), add keep-screen-on there too;
declarative XML on the player root is the lowest-risk form (no imports, auto
released on background).
