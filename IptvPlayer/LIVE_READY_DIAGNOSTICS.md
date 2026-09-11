# Live readiness diagnostics and VOD seek observations (2026-09-08)

## Scope

This is an opt-in diagnostic change, not a playback fix or release. No buffering,
decoder, routing, source URL, account, or connection-ownership policy is changed.
Existing unrelated support-desk working changes are preserved.

## Observed installed baseline

ADB device reports Xiaomi MiTV-AYFR0, Android 11, installed version 1.5.86 / 130.
The user identifies the live channel as Kanal D HD and reports microstutters and
loading/reconnecting text flashing several times per second.

- A 25-second PID-filtered log capture at 17:37:45–17:38:09 (device UTC+03 clock)
  contained 320 native AudioTrack timestamp-correction messages, no logged
  dropped-video-frame events, and no player errors/reconnect events. This is a
  bounded observation, not proof that the network is always healthy.
- AudioFlinger shows the same PCM16 stereo 48 kHz track/session, with 16,400
  frames ready and zero underrun/flush counters in the inspected snapshots.
- Media metrics separately prove rapid Java pause/start operations on that same
  track, e.g. 17:39:20.349 pause, .365 start, .372 pause, .389 start. A stable track
  identity excludes repeated recreation, **not** repeated pause/resume.
- At 17:41:57 the playback log reported `audioClock=ADVANCING codec=MPEG_AUDIO`.
- Cached Media3 1.8.1 code shows renderer readiness can cause BUFFERING/READY and
  stop/start all renderers. Audio pending data uses Media3's clock estimate, so a
  full native PCM queue does not establish the audio renderer's readiness.
- Home preview loading UI is shown immediately on buffering callbacks. This
  explains visible flicker during such transitions but does not establish which
  renderer/source condition causes the transitions.

## Enabled only for diagnostic builds

Set `LIVE_PLAYBACK_DIAGNOSTICS=1` in the build process environment. The APK version
name becomes `1.5.86-diag1`; versionCode remains 130. When the variable is unset or
not exactly `1`, diagnostics are disabled and the normal version name is retained.

Current-media Analytics events record audio/video readiness, playback state,
loading/playing/playWhenReady, numeric error/HTTP codes, monotonic event time,
callback delay, media position, buffered duration, suppression reason, audio
codec, actual video-frame age, and cumulative event counts. No source addresses,
account credentials, format objects, channel names, or error bodies are logged.

The observer emits at most four lines per rolling second for the first 120
seconds of each stream attempt. Repeated event keys have a 250 ms emission gap.
Suppressed event counts remain in the next emitted snapshot. A one-second sampler
is canceled on stream reset/release and stops at expiry. Frame tracking performs
no per-frame logging or additional network/file/surface reads.

For the physical test, capture the application PID with `PlaybackLog:D` and
`*:S`; retain lines prefixed `liveDiag`. Do not filter out readiness/sample lines.
Use event labels and cumulative counts together: throttling intentionally omits
some individual edge timestamps, and state snapshots reflect callback-processing
time. Compare the same channel/source/device before and after instrumentation.

## VOD: separate read-only investigation

- At 17:48:04, VLC hardware video counters stopped while audio/input advanced;
  the VOD controller selected its bounded software fallback.
- At 17:50:54, VLC logged `cannot seek (to offset 8908480)` and
  `unable to read KaxCluster during seek`, together with missing reference-clock
  and HTTP stream cancellation/closed messages. This proves a failed Matroska
  byte seek/read, **not** unsupported server Range. Teardown can also cancel
  streams, and existing logs do not correlate seek dispatch/landing with teardown.
- `requestSeek` already resets the liveness monitor. There is nevertheless a
  code-level race: a delayed seek retry does not recheck that its target is still
  current before issuing the native call. Retry scheduling checks the target only
  after an unsuccessful post. Rapid newer seeks can be overwritten by old retries.
- The roughly 400 ms retry budget is shorter than the 8-second native-operation
  timeout. A busy owner can exhaust retries; completion currently acknowledges the
  native call, not successful arrival at the requested media position.

No VOD code was changed. To resolve it, independently test command coalescing,
busy-owner redispatch, seekability/landing acknowledgment, stale snapshots and
HTTP 200/206/Content-Range behavior using an authorized test stream. Do not open a
second customer stream to probe Range while a single-connection account is playing.

## Verification and installation boundary

- Twelve focused JVM diagnostic-gate tests passed from current source using
  `work/Run-LiveReadyDiagnosticUnitTests.ps1` (Kotlin compiler + JUnit from cache).
- Independent static review found no blocking issue in the observer/lifecycle,
  opt-in flag, rate bounds, and logged fields. This is not device acceptance.
- The full offline Gradle `testDebugUnitTest lintDebug assembleDebug assembleRelease`
  run failed after four minutes at `compileReleaseJavaWithJavac`: access was denied
  to SDK build-tools 34.0.0 `core-lambda-stubs.jar`, with cascading generated-binding
  missing-class errors. Kotlin daemon access was also denied; its fallback compiler
  reached release Java compilation without reporting a diagnostic API compile
  error. Full Gradle tests, lint, APK packaging and device validation did not pass.
  Do not use stale pre-existing APK outputs as evidence of this build.
- The installed APK's production certificate was verified. The corresponding
  private signing key is not present in the audited local work/outputs or build
  environment. Local debug signing does not match the installed app.
- Do not uninstall or clear app data to bypass signature mismatch. A test APK
  must be signed with the existing production identity before a data-preserving
  update. The production Android CI workflow can publish releases and must not be
  invoked for diagnostic signing.

## Isolated diagnostic CI

The separately approved `.github/workflows/android-diagnostics.yml` runs only on
pushes to `agent/live-ready-diagnostics-v1.5.86` in this repository. It has read-only
repository permissions and no release, tag, main-branch or dispatch trigger. The
existing production workflow does not match pushes to this diagnostic branch.

The job runs unit tests and lint before accessing signing credentials. It decodes
the production key into a restricted temporary file, signs the APK, removes that
exact key file, and verifies package `com.iptv.player`, version code `130`, version
name `1.5.86-diag1`, the installed APK's public certificate fingerprint, v1/v2
signatures and MPEG decoder/license resources. The APK is a seven-day Actions
artifact, not a release. Signing secrets are confined to the decode/sign steps;
neither the APK nor reports include the keystore.

The six existing signed-APK verifier tests passed. Embedded Bash and Python
syntax checks and a static trigger/credential/artifact review passed. CI and
device acceptance must still pass; these checks do not establish an APK build.

Local preparation and a diagnostic-branch commit are authorized. Do not interpret
this as permission to merge or publish a release. No device install, app-data
reset or server deployment has been performed during preparation. Install only a
successfully built, independently signature-verified diagnostic artifact using a
data-preserving update; never use old APK outputs or bypass a signer mismatch.
