---
name: Keep-screen-on / no standby (app-wide)
description: Why the app went to standby while open and how no-sleep is enforced app-wide on all devices
---

# No screensaver / sleep while the app is open (all devices)

The app dropping to standby/screensaver after ~5-10 min is the **system**
screensaver/sleep, NOT any in-app timer. The user requires NO screensaver/sleep
on **all** devices (Fire TV, Sony, generic Android TV, phones/tablets), app-wide
— not just on player screens.

**Rule (current policy):** keep-screen-on is held **app-wide** in
`BaseActivity.onCreate` via `window.addFlags(FLAG_KEEP_SCREEN_ON)`. Nearly every
Activity extends `BaseActivity`, so the whole app stays awake while foreground.
`CrashRecoveryActivity` deliberately bypasses `BaseActivity` (for the
locale/density override), so it sets the same flag directly.

**Do NOT** add `clearFlags(FLAG_KEEP_SCREEN_ON)` anywhere — it defeats the
app-wide hold. Two such calls were removed (Home fullscreen-spike collapse,
Diagnostics peering finally). Per-screen `android:keepScreenOn` on the player
layouts and the Home preview's view-level `previewVideo.keepScreenOn` toggles
are now redundant but harmless and were left in place.

**Why it's safe:** `FLAG_KEEP_SCREEN_ON` is a window attribute, not a wake lock
— it only has effect while the window is visible and is dropped automatically
when the app is backgrounded, so nothing leaks and there's no background battery
drain. A client-set window flag is preserved across view-level `keepScreenOn`
toggles, so `previewVideo.keepScreenOn = false` does NOT clear the
BaseActivity-held window flag.

**Limits to set expectations:** the flag only blocks the inactivity
screensaver/sleep. It cannot stop HDMI-CEC power-off, the remote power button, or
an explicit "Sleep" from a TV's quick-settings. On phones/tablets it keeps the
display lit on every screen until the app is exited (real battery cost) — this is
what the user asked for.

**Trap — dead code that misleads standby debugging:** `PlayerScreenGuard`
(its header claims to manage keep-screen-on for the players), `IdleWatcher`,
`ScreensaverActivity`, and `MiniPlayerView` all exist but are **never
instantiated**. Don't assume keep-screen-on lives in `PlayerScreenGuard`, and
don't chase an in-app screensaver as the cause.
