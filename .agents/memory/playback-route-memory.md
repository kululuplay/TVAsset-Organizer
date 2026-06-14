---
name: Playback route memory (Tier 2 self-healing)
description: Per-channel "start on the stage that last worked" memory in the live player, and the stable-callback race it exposed.
---

# Playback route memory (Tier 2 self-healing)

`PlaybackRouteMemory` (util, disk-backed JSONL like `StabilityTelemetry`) remembers
which decode stage (`EXO` / `VLC_HW` / `VLC_SW`) a live channel last proved STABLE
on, so the next start jumps straight there instead of re-walking the hardware-first
fallback ladder and re-incurring the same greens / start-timeouts.

**Why:** users on weak/Amlogic sticks waited through the same ladder every single
start for channels that always end up on the same stage.

## Invariants (do not break)
- **Eligible only when `isLive && engine==AUTO && decoder==AUTO`.** Explicit
  engine/decoder choices must behave exactly as before — no memory steering.
- **Never resurrect `VLC_HW` from memory on Amlogic.** Its green is undetectable at
  runtime, so a stale HW entry could strand the user on a green picture. `rememberedStage()` returns null for that combo.
- **Distrust a remembered route that fails BEFORE the stable window.** `handleFailure`'s
  top intercept (`usingRememberedRoute && !memoryIgnoredThisPlay`) marks it failed,
  sets `memoryIgnoredThisPlay`, and restarts from `baseInitialStage()` with a cleared
  ladder. This is the only thing keeping a bad memory from stranding/looping a stream.
  `usingRememberedRoute` is true ONLY when the remembered stage differs from base.
- **Learn on stable, forget on repeated failure.** `markStable` after
  `STABLE_PLAYBACK_MS` (overwrites the entry); `markFailed` drops the entry after 2
  failures. TTL 45d, LRU cap 500. Best-effort, never throws, reads never touch disk.
- **Only `PlayerActivity.startChannel` passes a `routeKey`** (`"<channel.id>|<format>"`).
  Home preview + mini player call `play(url)` with null → no memory (short-lived,
  avoids two controllers racing the same key). `retry()` must forward `currentRouteKey`.

## CRITICAL race the feature exposed
`handleFailure` MUST cancel `stableHandler` as its FIRST line. The stable callback is
armed on first frame for `STABLE_PLAYBACK_MS`; a stage that renders a frame then dies
near the end of that window would otherwise let the callback fire (the delayed
reconnect's `startStage` clears it only later) and **falsely learn a failed stage as
stable** — worst for `remembered==base` plays where the distrust intercept is off.
Also capture `stage` + `currentRouteKey` at ARM time and write those captured values,
not the live fields, so a missed cancel can't record the wrong route.

**How to apply:** any future change to the stable timer, `markStable`, or the failure
path must preserve "cancel the stable timer before doing anything else on failure" and
"the stable callback records only the route it was armed for".

## Deliberately skipped
No unit test added: the PlayerController JVM test harness was removed in the
known-good-baseline revert, and the stable timing uses an Android `Handler`
(needs Robolectric). Cannot compile/run locally, so an unverifiable test was out of
scope. Verify via CI build + field telemetry instead.
