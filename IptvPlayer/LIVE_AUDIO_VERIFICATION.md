# Live audio stability correction (unreleased)

## Observed baseline

Live ADB diagnostics on a Xiaomi MiTV-AYFR0 (Android 11), app 1.5.84:

- An AC3 channel rendered video and started its audio clock, then two underruns
  218 ms apart triggered a complete Exo -> VLC software transition.
- A separate 1080p H.264 channel with unsupported audio used full VLC software
  decoding at roughly 228–287% process CPU (four-core device).
- A working 1080p H.264/AAC channel stayed on Exo hardware video at roughly
  20–50% process CPU. These are different streams, not a bitrate/FPS-controlled
  benchmark. They are baseline observations, not measurements of this patch.
- The unsupported track's exact codec was absent from the old diagnostics; it
  must not be assumed to be AC3 or MP2.

## Changes

- Underruns alone no longer cause AC3 decoder fallback. A bounded observation
  window now needs fresh sink-clock evidence of at least six seconds without
  meaningful audio progress plus continuing video/input and repeated underruns.
- Real sink/codec errors and the existing bounded PCM fallback remain active.
  Network-wide starvation is not evidence of an audio decoder failure.
- Actual PCM playout progress clears the episode. Pause, flush, discontinuity,
  channel replacement, release and stale callbacks cannot carry it forward.
- On constrained/Amlogic compatibility paths, available platform software AC3
  or MPEG layer I/II audio decoders are preferred. Video codec ordering is
  unchanged. AAC, EAC3, explicit passthrough, secure and tunneling paths retain
  their default selection. Vendor audio remains an initialization fallback.
- Only previously learned schema-4 full-software routes are re-evaluated on
  upgrade. Existing Exo/VLC hardware routes and user settings are retained;
  newly proven software routes can still be remembered.
- Unsupported audio diagnostics now include closed codec category, numeric
  channel/rate/support and selection state, including unselected tracks. No
  URLs, track labels, credentials or raw exception text are added.

## Automated checks

New regression tests cover the observed 218 ms burst, actual/stale/missing sink
clock evidence, recovery/reset/expiry, sink delegation, decoder ordering and
route-cache migration. Run from this directory:

```
./gradlew testDebugUnitTest lintDebug assembleRelease --no-daemon
```

On a restricted Windows host, Kotlin's in-process compiler can be selected with
the quoted argument `'-Pkotlin.compiler.execution.strategy=in-process'`.
Build results must be recorded separately; adding a test does not mean it passed.

### Local result, 2026-09-05

- Debug and release Kotlin compilation completed with existing project warnings.
- Full Gradle test/lint/release run stopped at `compileDebugJavaWithJavac` with
  `java.nio.file.AccessDeniedException` on the generated debug `R.jar`. Kotlin's
  default daemon also hit a restricted local marker directory; in-process
  compilation resolved that separate issue, but not the Java/resource access
  failure. Full Gradle tests, lint and APK assembly are **not verified**.
- Independently compiled the five focused JUnit suites against the newly built
  debug Kotlin classes and cached dependencies: **36 tests passed** (underrun
  monitor, decoder ordering, AC3 stall policy, sink delegation and route memory).
- `git diff --check -- IptvPlayer` passed. No APK was installed on the attached
  device; no commit, merge, version bump or release was performed.

## Device acceptance before publication

1. Install a correctly signed candidate by an approved method without removing
   the existing app or clearing its accounts/settings.
2. Run the affected AC3 channel for at least 15 minutes. Short underruns should
   recover without EXO -> VLC_SW, and video should remain hardware-decoded.
3. Repeat on the affected unsupported-audio channel. Check `audioCandidate`
   before selecting any additional decoder implementation.
4. Verify 1080p AAC/EAC3, channel zapping, fullscreen/preview, background/resume,
   temporary network loss, actual silence and single-connection ownership.
5. Repeat on the legacy Fire Stick and a newer device. Compare CPU, dropped
   frames and rebuffer counts using the same stream, network and time window.

## Limits

This patch does not bundle a new FFmpeg audio extension. If Android supplies no
compatible audio decoder, the existing bounded VLC fallback is still needed;
hardware video for that case is not yet guaranteed. VLC hardware safety guards
are not disabled. Earlier generic timeout reports and long-duration resource
leaks are not proven fixed by this audio correction. No universal codec/device
compatibility claim should be made without device testing.
