---
name: Live TV preview + catch-up surfacing
description: How the Home Live page previews channels and where catch-up lives, so future work doesn't rebuild it.
---

# Live preview in HomeActivity
- The Home preview card hosts its own `PlayerController` (not `MiniPlayerView`, whose name-bar/close chrome would clash with the caption). A bare `previewVideo` FrameLayout sits behind the poster logo + caption; poster (`infoLogo`) hides on `onPlaying`.
- **Single-connection contract applies to the preview too.** Only one stream/socket exists app-wide, so:
  - Focus changes are debounced (~700ms) before starting preview.
  - `stopPreview()` (release, not pause) before launching full `PlayerActivity`, in `onStop`, and re-schedule in `onResume`.
  - The debounced start coroutine reads settings via `.first()` (suspends), then `currentCoroutineContext().ensureActive()` before constructing/playing — a stale resume after `stopPreview` must NOT re-open the socket while the full player connects.
- `PlayerController.play()` releases+recreates the engine every call (same as live zapping); true engine reuse is not implemented. Accept the churn rather than rewriting core play().

# Catch-up / archive was already built but orphaned
- Full stack exists: `ui/catchup/CatchupActivity` (plays past programs via `VodPlayerActivity`, ExoPlayer/seekable), repo `observeCatchupChannels`/`getCatchupPrograms`/`buildCatchupUrl`, `Channel.catchupDays`, Xtream `tv_archive`/`tv_archive_duration` parsing. Strings live in `strings_features.xml` (per-locale; reuse `catchup_title`).
- It had **no entry point**. Surfaced from the Live page: `KEYCODE_GUIDE`/`KEYCODE_PROG_RED` open it (pre-focus via `CatchupActivity.EXTRA_CHANNEL_ID`), a row badge + a preview hint chip mark archive-capable channels.
- **Why:** before adding any catch-up UI, check this stack first — the timeshift URL format and program-window logic are done; you only wire entry points.
