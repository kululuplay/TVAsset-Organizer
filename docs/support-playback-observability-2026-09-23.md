# Playback observations in the support centre

Support staff need evidence from the affected device when a channel works elsewhere but a customer reports loading, freezing or failure to open. This change connects a bounded Android observation spool to the dedicated support service and adds **Oynatma sağlığı** with device search, problem filtering, ticket links, measurements and a timeline.

## Evidence and interpretation

| Customer symptom | Evidence now available | Interpretation limit |
| --- | --- | --- |
| Content never opens | STARTING duration, time to ready/first frame, classified HTTP/network/decoder failures | A slow start alone does not identify the provider as the cause. |
| Repeated loading | Current buffering duration, cumulative count/duration, available buffer, connectivity | Wi-Fi versus upstream failure requires comparison with another device/source. |
| Picture freezes while player reports playing | Actual rendered/dropped counter deltas and age of last advancing frame | Missing counters are unknown, not zero; no pixel inspection or screen capture. |
| Device cannot keep up | Dropped frames, RAM pressure, model/OS/app, codec/resolution/format FPS, observed decoder type | Format FPS is not rendered FPS. VLC hardware preference is not proof of actual hardware decoding. |
| Problem occurs during a player fallback | Engine and transport, failure sequence, engine switches, per-session snapshots | Restarted output invalidates old counters/format until a fresh observation arrives. |
| User leaves the app or network disappears | Receipt/source timestamps and stale status; retained observations on reconnect | A crash/power loss may prevent the final event. An offline device cannot report live state. |

Diagnostics describe measured symptoms and suggest checks. They do not claim automatic repair or a certain root cause. Playback selection, buffering policy and decoder recovery decisions are not changed by the support reporting code.

## Delivery and data boundaries

- The client uses the existing support installation UUID and secret, independent of the legacy shared-key/Replit telemetry switch.
- Existing player sampling supplies measurements. A foreground IO worker sends current state every approximately 30 seconds and at most one retained boundary per cycle. No new native polling loop or background network service is introduced.
- Start/failure/end snapshots retain their own session identity and sanitized label. Current state is prioritized after reconnect. Offline retention is at most 32 envelopes / 256 KiB; delivery uses exact acknowledgements, immutable IDs and bounded exponential backoff.
- Server validation is closed and size-limited. Storage is transactional and idempotent, including retries after timeline pruning. Old packets cannot rewind the latest state or reopen a terminal session.
- Measurements remain available for up to seven days, subject to per-device/global caps; detail shows the latest 100. The UI discloses truncation and never treats missing/stale data as healthy.
- No raw stream address, account password, microphone or screenshot is collected. Automatic measurements do contain recent content labels and technical device metadata; access is restricted to the authenticated support team. Settings displays the support code and translated disclosure.

## Verification and release order

1. JVM behavior tests cover observed states, frame progression/reset, pause, unknown metrics, safe format fields, and bounded delivery/acknowledgement/backoff. `PlaybackSupportContractTest` exports real Kotlin envelopes; `scripts/verify_support_contract.js` checks them against the backend validator and diagnosis.
2. Node API/security tests and PostgreSQL integration tests cover access isolation, retries, stale ordering, empty current sessions, transactional caps, history truncation and format storage. `support-desk/deploy/verify-live.js` additionally validates the deployed HTTPS API with exact-UUID synthetic records and removes them afterwards.
3. Browser checks use a loopback synthetic fixture: problem/healthy/stale cards, detail modal, evidence/timeline, filtering, empty search, ticket-to-device link and logout. These are not customer device measurements.
4. Deploy the support service/schema first, then install the preview on an authorized physical stick and compare normal playback, buffering, source denial, pause/restart, network interruption and foreground/background behavior. Validate the support code matches the new installation.
5. Publish a signed production APK only after required checks. Production publication must follow the repository's existing release and 100% rollout verification; a separate preview package must never enable production rollout.

The support service was deployed at `https://212.95.41.130:8443` and verified at 2026-09-23 09:55 UTC. The owner verified the SSH host key through the provider console. Protected code and database backups were taken before updating only the dedicated support application. Existing TLS, Nginx, systemd and other application services were preserved.

Deployment checks passed: staged Node tests (38 passing, two PostgreSQL suites subsequently run against isolated schemas), both PostgreSQL suites, authenticated HTTPS ingestion/idempotency/admin/idle/format checks, and exact synthetic-fixture cleanup. All 21 existing tickets remained; playback tables were empty after cleanup. The deployed playback backend and dashboard hashes matched the prepared artifact. The backup remains under `/var/backups/kululu-support/update-20260923T095433Z.NtOxckt0` with root-only access.

No test stick was connected over ADB and no new production APK was published. Real customer measurements still require distribution of an APK containing the reporter; server deployment does not retrofit older clients. Physical playback validation remains pending.

Verified locally on 2026-09-23: Android preview build and lint completed; 720 JVM tests passed with no failures or skips; all seven Kotlin-to-server contract cases passed. The complete Node suite, including isolated PostgreSQL, passed 52 tests without failures or skips. Browser checks also covered server loss/reconnection; unavailable measurements lose their current/healthy indication. The final Turkish network-message adjustment passed all eight UI regression tests.

## Scale hardening (2026-09-24)

Server-side changes only; no client envelope or endpoint changed, and nothing here was deployed or device-tested by this change.

- Retry receipts no longer fill up into a fleet-wide 503. They are rings like samples: the 500 newest per device and 1,000,000 fleet-wide survive, oldest first. A retry is acknowledged while either its receipt or its retained timeline row exists; an old replay that outlives both becomes ordinary late history and still cannot rewind the current state.
- Uploads no longer run full-table counts under one global lock. Fleet-wide usage (devices, samples, receipts, incidents) is counted, retention-trimmed and ring-pruned at most once a minute per process outside any lock, in bounded batches; each upload takes only a per-installation lock plus bounded per-device index lookups. Ticket and installation capacity checks keep their exact transactional semantics. The only playback refusal left is a new device beyond the 25,000-device cap.
- A device clock ahead of the server is clamped to receipt time on ingest; the stored payload keeps the device's own timestamp. One future-stamped envelope therefore cannot block later, correctly stamped measurements from refreshing the device snapshot.
- Rate-limit tables evict expired, then earliest-expiring keys when full instead of refusing new devices or addresses; operator sign-in has its own table so device traffic cannot crowd out its per-address counters.
- Freshness follows the client cadence. The client now refreshes an idle card (no active playback session) at most every five minutes while active playback keeps the 30-second cadence, so a device whose last snapshot carries an unfinished session is fresh for two minutes and a snapshot with no unfinished session (idle Home screen or a delivered final boundary) is fresh for six. The list/summary SQL, the JSON `freshWindowMs` field and the dashboard apply the same rule, so idle devices no longer flip to "Güncel ölçüm yok" between snapshots. Absence of data still never implies healthy playback.
- The database tests assert that their connection really selects the random throwaway schema and qualify bulk fixture mutations with that schema and fixture installation ids; `verify-live.js` proves the `search_path` option is honoured before running them against the production server.
