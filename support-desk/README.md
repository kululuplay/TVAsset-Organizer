# Kululu Support Desk — self-hosted

Dedicated customer reports, requests and automatic playback health observations, independent of the old Replit telemetry receiver. The dashboard can correlate a support ticket with its installation's recent playback measurements. It does not control a device remotely.

## Deployment

- Public origin: **https://212.95.41.130:8443** (a valid IP SAN certificate, not a self-signed certificate).
- Service: `kululu-support`, loopback `127.0.0.1:5086`, source `/opt/kululu-support`.
- PostgreSQL database/role: `kululu_support`. Existing application databases are not used.
- Admin and DB configuration: `/etc/kululu-support/service.env` (root-only, not in Git).
- Separate randomly generated admin password: `/etc/kululu-support/admin-access.txt` (root-only).
- Existing `kstream-tv` / `kululu-play` services and hostname certificate files are preserved.

Copy only `support-desk`, `package.json` and `package-lock.json` into the isolated deployment directory. As root, run `bash support-desk/deploy/prepare-tls.sh`, then `bash support-desk/deploy/install.sh`. Check availability before opening the service externally. TLS setup installs an isolated Certbot 5.4 environment, not a replacement for the host's existing Certbot installation.

IP certificates are short-lived. `kululu-support-renew.timer` checks twice daily and reloads Nginx after a successful renewal. Port 80 must remain reachable for the HTTP-01 challenge; clients use HTTPS on 8443. Check timer failures and certificate expiry operationally. Do not disable TLS validation or substitute HTTP to work around renewal errors.

Useful checks:

```sh
systemctl status kululu-support kululu-support-renew.timer
journalctl -u kululu-support --since '1 hour ago'
curl --fail https://212.95.41.130:8443/healthz
node support-desk/deploy/verify-live.js
```

The last command runs PostgreSQL tests in randomly named test schemas and an HTTPS smoke test with synthetic records. It removes only the exact test installation and its records afterwards. Do not run against another database. The schema additions for playback are idempotent and run at service startup; take a database backup before deployment.

## Data and access

- Device identity is a random per-installation UUID plus 256-bit secret; only the secret hash is stored on the server. No API secret is shared across all APKs. Authorization checks apply to history and acknowledgement endpoints.
- Free-text requests and diagnostic log attachments are manual; logs require confirmation. Playback observations upload automatically while the app is in the foreground, through the separate bounded/cancellable HTTPS client. Redirects are disabled. The bundled public ISRG Root X1 supplements old system trust stores only for this client; hostname and chain verification stay enabled.
- Diagnostic uploads contain a bounded redacted log, device/Android/app versions and configured player settings. IPTV credentials, full stream URLs and user account names are not deliberately included. Avoid entering personal or secret information into free-text requests; automated masking cannot recognize every possible secret format.
- Log attachments are scrubbed after 90 days, hourly. Request text, ticket codes and moderation history remain until operator deletion. Unused installations with no tickets or active playback records expire after 30 days.
- Capacity is bounded transactionally: 100,000 installations, 100,000 tickets, 10,000 retained log attachments at <=128 KiB each. Daily global quotas and per-IP/per-device limits supplement those caps. A full store returns a 503 error, never a false success. Operators should export/archive old tickets before caps are reached.
- Admin: scrypt password hashes, expiring in-memory sessions, HttpOnly/Secure/SameSite cookies, Origin+CSRF checks for mutations, CSP, plain-text rendering of untrusted reports, and status-change audit rows. Service restarts invalidate admin sessions.
- Public registration is intentionally open to support customers; rate limits/caps are abuse controls, not proof that an uploader is an authenticated IPTV subscriber.

Back up this dedicated database regularly with `pg_dump` as an authorized administrator, encrypt backups and store them separately from this VPS. Backups need their own retention policy; log scrubbing does not erase historical backups. No off-host backup destination has been configured by this task.

## Playback health

The **Oynatma sağlığı** view lists devices by random installation code, model, app/Android version and recent content label. Settings on the device displays the same support code and explains that technical playback measurements are sent. Ticket details link to the associated device.

- Measurements include startup/first-frame delay, buffering count and duration, observed playback state, known rendered/dropped frames, last frame age, available buffer, player/transport, classified failures/HTTP status, network type/connectivity, and available RAM. Safe video format and actual decoder type are included where the engine exposes them; a hardware preference is not reported as proof of hardware decoding.
- The client reuses existing playback measurements. Ordinary foreground reporting runs every 30 seconds. Incidents can wake the uploader, with at most two attempts per 10 seconds; offline backoff still applies. A lightweight in-memory snapshot every two seconds builds incident windows without querying native players, running speed tests or adding background uploads.
- Offline storage is bounded to 32 envelopes / 256 KiB. Retries keep immutable sample IDs and use exponential backoff up to 15 minutes. Only a matching acknowledgement removes a successfully delivered sample. Unsuitable/expired samples cannot block the queue indefinitely.
- The authenticated ingestion endpoint is `POST /api/v1/playback` (32 KiB, 16 sessions, closed schema, per-installation and global limits). Administrative list/detail endpoints are `GET /api/admin/playback` and `GET /api/admin/playback/:installationId` under existing admin session authorization.
- A fresh report is at most two minutes old, considering both source and receipt time. Missing frame counters, paused/ended sessions, stale reports and clock skew never imply healthy video. Diagnoses describe measured evidence and suggested checks; they cannot establish that a panel or Wi-Fi caused a failure on their own.
- Timeline retention is **up to** seven days, 2,000 samples per device and 100,000 globally; capacity can shorten history. Detail shows the latest 100 entries and discloses limits. Separate bounded receipts keep retries idempotent after timeline trimming. Late replay cannot replace a newer current state or reopen a finalized session.
- No screen/audio capture, GPS, Wi-Fi password, raw media URL or IPTV password is sent. Content labels and device strings are length-limited and scrubbed; measurement history is still customer activity metadata and must remain restricted to authorized support staff.

The dashboard refreshes every 30 seconds only while its authenticated health view is visible. This is periodic diagnostics, not a live screen feed. A power loss, crash or connection outage can prevent the final event arriving; absence of data remains unknown. Measurements cannot prove lip-sync, subjective picture quality or every sub-second freeze.

### Channel and incident investigation

- **Kanal karşılaştırması** groups fresh live/radio observations by a provider/content fingerprint that excludes account paths, passwords and query parameters. No title-only grouping is performed. `GET /api/admin/playback/channels` is admin-only and paginated.
- **Sorun anları** shows bounded windows (normally 20 seconds before and 10 seconds after) for slow startup, buffering, non-advancing video, playback error or an explicit report. The envelope may contain one incident with at most 31 points. Initial and completed windows share an incident ID; completed evidence cannot be rewound by an older packet. Incomplete/truncated windows are identified.
- **İçerik açılışının adımları** shows initial Media3 transfer opening, first bytes and first rendered frame when actually measured. Network/TLS/server waiting and buffer/demux/decode work are not mislabelled as pure panel or decoder time. Retries before first frame invalidate the split; unavailable VLC timings remain unknown.
- The player's MENU contains **Bu kanal takılıyor** (live) or **Oynatma sorunu bildir** (VOD). Selecting it sends a linked, redacted, no-log diagnostic ticket and displays its receipt or retry action. The session/content/incident identifiers lead from the ticket to the relevant device window.
- **Müdahale takibi** records an operator note and compares two-minute windows for the same installation/content. `POST /api/admin/playback/:installationId/interventions` requires admin authentication, Origin+CSRF and an idempotency UUID. It never sends a remote device command. Counter differences exclude pauses, counter resets, missing pause evidence, session transitions, delayed packets and large gaps; insufficient coverage remains unknown.
- Incidents and intervention notes have seven-day retention and transactional caps: 50,000 incidents / 200 per device; 10,000 interventions / 100 per device. Detail displays the latest 30 incidents and 10 interventions, with truncation disclosed. Before/after results retain bounded baseline evidence and freeze only after the measurement window closes.

## Android rollout boundary

Only an APK containing `PlaybackSupportReporter` sends automatic playback measurements. Existing APKs cannot supply this history retrospectively. Deploy the server/schema before distributing that APK. Manual support uploads from earlier `SupportClient` APKs remain compatible. A local preview build is not a production release and must not activate production rollout.

New request history reads this service. Legacy heartbeat notifications/acknowledgements remain attached to the old receiver so old numeric IDs cannot modify new support tickets. Old records are not silently migrated, and new support tickets do not trigger legacy heartbeat popups.

## Checks

```sh
npm ci --ignore-scripts
npm run test:server
```

By default PostgreSQL tests are skipped locally; set `SUPPORT_TEST_DATABASE_URL` to an isolated test database to include them. On the VPS the verification script supplies the dedicated database URL without printing credentials and tests only temporary schemas. After Android unit tests, run `node scripts/verify_support_contract.js` from the repository root to validate real Kotlin-produced envelopes against the server. Real Android/Fire TV end-to-end and long-duration playback validation require actual devices; server and unit tests do not establish device playback stability.

Optional WebMCP tools mirror visible ticket opening and status changes. They are feature-detected; authentication and CSRF remain server-side. Browser QA of the playback view uses a local synthetic fixture and does not establish production data ingestion or device connectivity.
