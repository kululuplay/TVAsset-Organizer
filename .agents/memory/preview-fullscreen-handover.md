---
name: Preview -> fullscreen seamless hand-off
description: How the live preview controller is transferred to the fullscreen player without breaking the single-connection contract
---

# Live preview -> fullscreen hand-off

Going fullscreen from an in-panel live preview must NOT release+reconnect the
stream. Instead the running PlayerController is transferred from HomeActivity to
PlayerActivity intact.

## The transfer pattern
- The engine surface is re-homed, not recreated: PlayerEngine has
  `detachVideo()`/`attachVideo(container)` (default no-op); Vlc re-attaches the
  SAME VLCVideoLayout, Exo re-homes the SAME PlayerView. Playback never stops.
- PlayerController.`rebind(newContainer)` = reassign context/container then
  `detachVideo()` -> wait ENGINE_SWAP_DELAY_MS -> `attachVideo()`. The gap is
  mandatory: a SurfaceView tears its surface down async, so attaching in the same
  pass greens the fresh surface on Amlogic.
- ServiceLocator is the one-shot park slot: `handOverLiveController` /
  `consumePendingLiveController` (returns-and-clears). Exactly one controller is
  ever parked, owned by exactly one screen.

## Single-connection safety (the easy thing to break)
- HomeActivity.openPlayer hand-off path: null out previewController/previewingChannel
  and set `handingOverPreview=true` BEFORE launching, so onStop does NOT
  `release()` the connection the player now owns.
- PlayerActivity adopt path (EXTRA_ADOPT_PREVIEW): set callback + rebind, set
  currentChannel + overlay, and DO NOT call play(). If the channel can't resolve,
  `release()` the adopted controller so the hand-off can't orphan a stream.

## Caption vs EPG decoupling (bug 1)
Browsing channel names must not stop the preview. showInfo() no longer stops the
preview on focus change. While previewing, the on-video caption (number/logo/
title/now-playing) is LOCKED to the playing channel via its own
captionJob/captionPrograms; the EPG list + catchup hint follow the focused
channel via currentPrograms.

**Why:** the provider enforces one stream/socket; an orphaned preview connection
or a double-play would drop both. The raw-URL note: adopted stream keeps the
preview's raw streamUrl (no applyStreamFormat) on purpose — re-formatting would
force the reconnect this whole feature exists to avoid.
