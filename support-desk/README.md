# Kululu Support Desk — self-hosted

Dedicated customer-initiated reports and requests, independent of the old Replit telemetry receiver. No automatic viewing-history collection or remote device controls are included.

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

The last command runs PostgreSQL tests in a randomly named test schema and an HTTPS smoke test with synthetic tickets. It removes only the exact test installation and its records afterwards. Do not run against another database.

## Data and access

- Device identity is a random per-installation UUID plus 256-bit secret; only the secret hash is stored on the server. No API secret is shared across all APKs. Authorization checks apply to history and acknowledgement endpoints.
- APK uploads are manual, require confirmation for logs, use a separate bounded/cancellable HTTPS client and do not depend on a share-sheet target. Redirects are disabled. The bundled public ISRG Root X1 supplements old system trust stores only for this client; hostname and chain verification stay enabled.
- Diagnostic uploads contain a bounded redacted log, device/Android/app versions and configured player settings. IPTV credentials, full stream URLs and user account names are not deliberately included. Avoid entering personal or secret information into free-text requests; automated masking cannot recognize every possible secret format.
- Log attachments are scrubbed after 90 days, hourly. Request text, ticket codes and moderation history remain until operator deletion. Unused installations with no tickets expire after 30 days.
- Capacity is bounded transactionally: 100,000 installations, 100,000 tickets, 10,000 retained log attachments at <=128 KiB each. Daily global quotas and per-IP/per-device limits supplement those caps. A full store returns a 503 error, never a false success. Operators should export/archive old tickets before caps are reached.
- Admin: scrypt password hashes, expiring in-memory sessions, HttpOnly/Secure/SameSite cookies, Origin+CSRF checks for mutations, CSP, plain-text rendering of untrusted reports, and status-change audit rows. Service restarts invalidate admin sessions.
- Public registration is intentionally open to support customers; rate limits/caps are abuse controls, not proof that an uploader is an authenticated IPTV subscriber.

Back up this dedicated database regularly with `pg_dump` as an authorized administrator, encrypt backups and store them separately from this VPS. Backups need their own retention policy; log scrubbing does not erase historical backups. No off-host backup destination has been configured by this task.

## Android rollout boundary

Only a new APK containing `SupportClient` uses this endpoint. Previously installed APKs keep their old share-sheet/Replit behavior. No APK version bump, merge or release is part of this dashboard deployment unless separately requested.

New request history reads this service. Legacy heartbeat notifications/acknowledgements remain attached to the old receiver so old numeric IDs cannot modify new support tickets. Old records are not silently migrated, and new support tickets do not trigger legacy heartbeat popups.

## Checks

```sh
npm ci --ignore-scripts
npm run test:server
```

By default the PostgreSQL test is skipped locally. On this VPS the verification script supplies the dedicated database URL without printing credentials. Real Android/Fire TV end-to-end and long-duration playback validation require actual devices; server and unit tests do not establish device playback stability.

Optional WebMCP tools mirror the visible ticket opening and status changes. They are feature-detected; authentication and CSRF remain server-side. A supported browser WebMCP contract validation context was not used in this task, so those optional tools are not claimed to be runtime-verified. Browser visual QA was not performed; request/response integration tests are separate from that.
