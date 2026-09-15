# Bounded live playback recovery

## Evidence and scope

The affected installation was confirmed by the customer as **1.5.88**. This fix
was prepared against the matching application version (code 132). Remote main
was `61a64ce1f91e774a82397985b362d4a822810aa6`; the local starting commit
`6fc4ab6` has the same tree.

The reported incident was an indefinitely visible loading message on live
channels. Catalog/EPG responses and short server-side MPEG-TS probes succeeded;
those probes do **not** prove client network delivery, decoder output or the
customer's exact failure. Reopening the application was followed by reported
recovery. The customer-device root cause remains unconfirmed.

Source inspection found an actionable gap: the startup timer began only after
the backend's submission callback. Queue, native cleanup or surface waits before
that callback could remain unbounded. A second retry chain in the Activities
could also restart a session after the controller declared a terminal failure.

## Changes

- Start a 45-second attempt deadline before engine creation/submission. Retain a
  90-second episode deadline across decoder changes, backoff and surface rebinds.
  Submission, buffering, READY and one frame cannot extend these deadlines.
- Cancel deadlines on explicit replacement, suspension and release. Reset the
  recovery budget only after sustained output. Retired callbacks are checked
  against both engine identity and request generation.
- Add a separate 30-second bound to the UI's provider-drain preflight, which runs
  before the controller exists. A timeout preserves uncertain ownership and
  invokes the existing process-recovery/error gate. It is never treated as proof
  that an old connection closed. A late queue completion cannot start twice.
- Keep automatic retries in the controller only. Terminal HTTP denials stop;
  Activities display a manual retry action instead of starting another chain.
  Fatal completion releases the current engine and invalidates pending work.
- Require non-buffering clock progress plus output evidence for recovery. Media3
  checks recent video frame evidence and the selected audio sink's progress;
  VLC checks displayed-picture/audio-output counters when available, retaining
  its existing verified-surface fallback on older builds without video stats.
- Allow 30 seconds for Media3 connect/read operations so a source that takes
  roughly 20 seconds to start has time to recover within the request budget.
- Log an opaque UUID for each attempt, phase, elapsed time, engine and numeric
  HTTP status. No URL, account, password or exception message is added by this
  trace. Media3 sends `X-Kululu-Playback-Attempt` for optional server correlation.
  Both engines retain the exact existing `KULULUPLAY` User-Agent. VLC currently
  correlates locally by attempt and remotely by time; it sends no new ID header.
- Keep live/radio MPEG-TS and existing HLS rejection. No panel service/channel
  restart, production version bump or release/rollout is part of this change.

`CONNECTING` records the client opening/submitting a transport, not proof of a
successful TCP/TLS connection. VLC `FIRST_BYTES` is sampled from native stats,
so it is approximate and may be absent on radio/unsupported stats paths.

## Validation

- Secret scan and whitespace checks passed locally.
- Kotlin compilation succeeded during local validation. Full JVM tests/lint
  could not complete locally: Java `Path.toRealPath()` raises Windows
  `AccessDeniedException` for readable project/SDK files, including after scoped
  read permission was granted. This is not a passing test result.
- Android CI is requested on the isolated fix branch. Its result is tracked in
  the pull request; test XML is retained as an artifact for an auditable count.
- Added 19 regression cases covering missing submission, silent retries,
  repeated READY/buffering, simulated delayed start, rapid channel replacement,
  preview/fullscreen rebind, suspension/resume, retry backoff, terminal cleanup,
  late native completion, remote ownership and output-progress evidence.
  These use fake clocks/fixtures; they are not real-device playback results.

## Physical validation still required before production

Run the following on Fire TV, an older Android TV and Xiaomi hardware using an
authorized test account. Capture client trace and provider connection counts.

| Scenario | Expected result |
| --- | --- |
| Normal TV/radio, including VLC and legacy zero-video-counter builds | Stable audio/video; no false recovery loop |
| Source takes about 20 seconds before sending TS | One bounded attempt can reach actual output |
| No submission callback / no bytes | Fallback or terminal retry UI within the controller's 90-second episode |
| Ready/clock advances but picture or audio stops | No false stable reset; bounded recovery |
| Hold channel up/down, then stop | Only final selected request remains; no stale callback changes it |
| Preview to fullscreen and back during loading | Correct surface and one provider connection |
| Standby/resume, network off/on | No background reopen; a fresh bounded foreground attempt |
| Native stop never completes | Ownership stays blocked; at most the existing rate-limited process recovery |
| HTTP 401/403/404 or unsupported HLS | Terminal outcome; no Activity retry loop |
| Cast owns the connection | Local timeout cannot override remote ownership |
| Retry button after terminal outcome | A new explicit request can start |

The preflight's 30 seconds is separate from the controller's 90 seconds. Existing
native-operation limits may fail earlier. Handler deadlines assume a responsive
main looper; this patch does not claim to resolve arbitrary JNI calls that block
the main thread. Real socket-count, surface, audio and device-counter behavior
cannot be certified by JVM fixtures alone.
