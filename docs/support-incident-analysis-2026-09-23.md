# Playback incident analysis

This extends the support playback dashboard with channel comparisons, bounded incident windows, startup stages, an explicit player report action and operator before/after measurements.

## Interpretation

- Compare a content fingerprint, not a display title. Fingerprints must omit account credentials and distinguish provider/content kind/content ID. Similar labels are not sufficient evidence that devices received the same source.
- Channel comparison uses fresh observations. Problems on several devices suggest investigating a shared path; mixed results suggest comparing the affected device, network, account permissions and delivery route. Neither result proves the panel or device is the cause.
- Incident windows reuse the player's existing observations. They retain approximately 20 seconds before and 10 seconds after a threshold or report, within strict memory/disk/server limits. A partial window remains visibly partial; power loss or disconnection can prevent completion.
- Source opening includes network connection, TLS and waiting for the server. First-byte timing starts at Media3 preparation. First byte to first frame includes demuxing, buffering and rendering. These are not separate DNS, panel processing or decoder benchmarks. Failed/repeated initial transfers invalidate the split; missing VLC measurements remain unknown.
- The player report sends a diagnostic ticket with session, content fingerprint and incident identifiers, using the existing authenticated support client. It does not attach raw player logs or start screen/audio capture.
- An operator intervention is a timestamped note that an action was performed. It does not execute a command on the customer device. The dashboard compares two-minute windows on the same installation/content using counter deltas and reports measured changes, never causal proof of a permanent repair.
- Comparison excludes observed pauses, cumulative hidden pauses, session changes, counter resets, stale deliveries and large sampling gaps. Both sides require sufficient valid intervals. Older clients without pause counters cannot supply a verified comparison.

## Delivery

Deploy the additive backend before the client APK. Earlier support clients remain compatible; they cannot provide missing incident/startup/content-identity data retrospectively. A preview APK uses a separate package and development signature. Production publication and its 100% rollout follow the repository release procedure.

Validation must include Kotlin behavior tests, actual Kotlin envelope validation by Node, isolated PostgreSQL ingestion/retention/ordering tests, authenticated API/CSRF checks and browser checks of empty, stale, partial and comparable states. Synthetic fixtures verify the diagnostic machinery, not customer playback quality.

## Verified server deployment

Deployed on 2026-09-23 at 10:32 UTC to the existing support service. The staged archive SHA-256 is `2b896351ef9401034b27ccb9d59606cce574fd49e08b0d13481c63113135ec5e`. All 61 support tests passed with real PostgreSQL in isolated schemas before the swap, with no skipped tests. The three database suites and authenticated HTTPS checks passed after the swap, including incident completion, channel comparison, startup fields, intervention idempotency/CSRF and exact synthetic fixture cleanup.

All 21 existing tickets were preserved. The support service and both other existing services remained active. Deployed analysis, dashboard, store and schema hashes match the archived source. Code and database backups are retained at `/var/backups/kululu-support/update-20260923T103216Z.21jZ38p1`. No proxy, TLS or service configuration was changed. At verification there were zero customer telemetry devices, so new customer evidence still requires delivery of the updated Android client.

## Client and UI validation

The final Android JVM suite passed 748 tests without failures, errors or skips. Nine actual Kotlin-produced playback/incident envelopes passed server validation and immutable-completion checks. `testDebugUnitTest lintDebug assembleDebug` succeeded; lint has zero errors and 438 warnings. Startup regressions cover actual render timestamps, invalid timestamps, interrupted startup, engine fallback, same-engine retry and cumulative pauses.

Local browser fixtures verified mixed and insufficient channel evidence, expanded incident points, startup stages, comparable/insufficient/waiting intervention states and ticket-to-incident navigation. These are synthetic functional checks. No physical-device quality or overhead measurement was performed: the ADB device list was empty. The preview package is separate from production and does not activate customer rollout.
