---
name: Preview->fullscreen hand-off black gap
description: Why expanding the live preview to fullscreen shows a black-with-audio gap, and how to cover it.
---

Going from the live preview (mini player) to fullscreen adopts the running
PlayerController and calls `rebind()` = `detachVideo()` -> ENGINE_SWAP_DELAY_MS ->
`attachVideo()`. That tears down and recreates the video surface, so AUDIO keeps
playing but VIDEO is black for ~2-3s until the new surface gets its first decoded
frame (live TS must wait for the next keyframe). Nothing covered that gap because
`rebind()` never fires `onBuffering()`.

**Fix pattern:** cover the gap with the existing buffering indicator on the adopt
branch and clear it on a REAL video-output signal — not `onPlaying`.

**Why not onPlaying:** `onPlaying` fires on audio/cache state (libVLC Buffering 100%
/ Exo STATE_READY) before any picture is on the surface, so it would clear the cover
while still black. The reliable "a frame is actually on screen" signal is libVLC
`MediaPlayer.Event.Vout` and ExoPlayer `onRenderedFirstFrame`.

**How to apply:** engine emits `PlayerListener.onVideoOutput()` (default no-op) from
those two events; PlayerController forwards to `Callback.onVideoResumed()` (default
no-op so other Callback implementers don't break); the fullscreen activity shows the
indicator before `rebind()` and clears it in `onVideoResumed()`, with a safety
timeout in case the event never arrives. The keyframe wait itself is not removable
without stream control — covering the gap is the UX fix, not eliminating it.

## Reverse hand-off (return-to-Home dead preview)
**Symptom:** After exiting fullscreen with BACK the Home preview stayed blank —
the fullscreen activity released its (adopted) controller in onDestroy and Home
had already cleared its own preview on the forward hand-over, so nothing restarted.

**Fix:** symmetric reverse hand-off. On BACK from a player that adopted the preview,
park the still-playing controller (+ current channel id) and set a `handingBack`
flag so onStop/onDestroy skip pause()/release(). Home.onStart() consumes the parked
controller, rebind()s it onto the preview surface, restores previewingChannel/caption.

**Why it's lifecycle-safe:** BACK order is Player.onPause -> Home.onStart(adopts) ->
Player.onStop -> Player.onDestroy, so Home owns the controller before the player's
stop/destroy run; the `handingBack` guards stop the player from pausing/releasing it
out from under Home. Forward park (channelId=null, consumed in PlayerActivity.onCreate
when EXTRA_ADOPT_PREVIEW) and reverse park (consumed in Home.onStart) never overlap.
Single-connection preserved: no reconnect, just a surface rebind. Show the channel
logo until onVideoResumed() (real Vout/first-frame) since onPlaying may not refire.
