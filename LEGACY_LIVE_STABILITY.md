# Legacy Android Live TV stability investigation

Baseline: v1.5.83, main commit `6a8d702` (verified against GitHub).

The customer reports that the same account/device plays correctly in other apps.
No affected device or customer stream was available locally. The defects below
are established from code and regression tests; their frequency on customer
devices still requires playback logs and an A/B test against the released APK.

## Findings and changes

| Trigger in v1.5.83 | Corrected behavior |
| --- | --- |
| A live source ends just before the delayed stability callback; that callback cancels the pending reconnect. | One progress policy owns stability and stall decisions. EOS invalidates the attempt before scheduling recovery, so there is no independent success timer to delete the retry. |
| One frame followed by buffering/freezing earns “stable” after a wall-clock delay and resets recovery budgets. | Stability requires consecutive advancing clock observations outside buffering. Confirmed reconnects remain monitored. Preview and fullscreen only reset automatic retry attempts after sustained progress. |
| Media3 keeps downloading a live stream while READY, with audio advancing but video frozen. | `isLoading` no longer exempts video from freeze detection. Actual playback buffering, pause, and fresh frames still suppress inappropriate decoder recovery. |
| An HLS manifest slides its window and the relative playback position jumps back repeatedly. | The Exo watchdog uses position within the underlying period, including the sliding-window offset, so moving windows do not trigger a false source reconnect. |
| Modest sustained frame drops on 1080p50 cause hardware decoder eviction, potentially routing an old stick into slower software decoding. | Dropped frames remain quality diagnostics. Real codec failures and independently confirmed output failures own decoder fallback. |
| Healthy hardware playback periodically triggers costly GPU readback on compatibility devices. | Initial surface validation remains; continuous healthy-state PixelCopy stops on compatibility devices. Native liveness checks remain active. |
| A channel change clears the health handler while a native PixelCopy result is still pending. | Copy completions use a separate handler on the same Looper, so they still recycle their bitmap. Generation and probe-identity checks prevent stale results from changing the new session. |
| VLC cache-top-up notifications omit a completion event and native picture counters remain all zero. | Sustained clock progress can request a bounded fresh image probe. Loading clears only with fresh changing healthy pixels, not historical first-frame proof or audio-clock movement alone. |
| ADAPTIVE uses LOW's four-second reserve until the engine is replaced. | A live Media3 load control grows restart thresholds in the same player, retains a useful reserve, and respects bounded sample-memory targets. Constrained devices start at NORMAL. Explicit LOW/NORMAL/HIGH time preferences remain. |

The encoded sample-memory target is 24 MiB on compatibility devices and 48 MiB
otherwise. This is not a total application-memory limit: codecs, surfaces,
in-flight samples and UI memory are separate. Reaching the sample target also
allows playback to start, preventing a time-threshold/memory-limit deadlock.

## Verification

Verified locally on 2026-09-05 with JDK 17 and the project's pinned Media3 1.8.1:

- `testDebugUnitTest`: 422 tests, zero failures/errors/skipped tests.
- `lintDebug`: passed, zero errors; 363 warnings remain (no lint suppression or
  baseline was added). An earlier analysis failed during source integration;
  the final source-frozen rerun passed.
- `assembleDebug` and `assembleRelease`: passed. The local release APK is
  unsigned, not a production update package.
- `python scripts/scan_secrets.py` and `git diff --check`: passed.

Final Gradle invocation:

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```

Regression coverage includes the real Media3 load-control adapter and allocator,
real Media3 HLS-style sliding timelines, readiness versus downloading, recovery
timing, and fresh-image versus audio-clock evidence. Pure policies use fake clock
sequences; these are not hardware decoder or network playback tests.

No physical Android device was connected (`adb devices` returned an empty list).
No customer stream, codec-device matrix or 48-hour endurance test was run.
Changes remain local on `agent/legacy-live-playback-stability`; no commit, push,
merge, release or version bump was performed for this patch.

## Customer-device acceptance

Use the same physical device, network, channel and account as the comparison app,
sequentially to respect the provider's single-connection limit. Record:

- Device model, Android/Fire OS version, app version, engine and stream format.
- First-frame time, buffer interruptions and failed/repeated reconnects for live
  TS and HLS, including H.264 1080p50 and supported HEVC variants.
- AC3/EAC3/AAC playback through the actual TV/HDMI audio route.
- Channel changes and preview/fullscreen transitions without losing playback.
- Sustained playback with network interruptions and background/resume cycles.
- A 48-hour run on the affected old stick; compare memory growth, resource errors,
  playback stalls and thermal behavior with the production baseline.

Do not distribute debug-signed/unsigned artifacts as an in-app production update.
Production rollout must use the existing signing and upgrade checks. v1.5.84
rollout is currently paused in GitHub policy; this investigation does not change
that policy or publish a release.

## Remaining investigation

- Media3's release timeout can be reported asynchronously; a separate audit of
  exact source-drain tracking is warranted if logs show release timeouts. This
  patch does not introduce a new provider ownership lock or alter Cast teardown.
- PixelCopy-disabled/unavailable devices with absent native video statistics
  cannot prove video progress from an audio clock. Native events retain authority
  there; clearing a spinner is not proof that the video decoder recovered.
- Provider encoding/timestamps, device firmware, heat and actual network jitter
  still need measured evidence; software tests cannot guarantee every codec and
  old device combination.
