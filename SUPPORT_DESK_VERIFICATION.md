# Support desk delivery — 2026-09-05

## Deployed

`https://212.95.41.130:8443` is served by a separate `kululu-support` systemd service, bound internally to `127.0.0.1:5086`, using a new dedicated PostgreSQL database/role. Existing `kstream-tv` and `kululu-play` services remained active throughout verification. No APK release, merge, commit or push was performed.

The TV log buttons now use direct, customer-confirmed uploads; the feedback form and request history use the new per-installation authenticated API. The dashboard has report/request filters, search, diagnostic details and new/reviewing/done status changes. Old Replit telemetry is not migrated; old heartbeat acknowledgements stay on the old endpoint to prevent cross-database ID collisions.

## Verified

- 16 support API/security tests passed locally and on the VPS.
- 12 existing receiver tests passed locally.
- PostgreSQL integration test passed on the dedicated VPS database in an isolated temporary schema: atomic capacity checks, ownership, retry idempotency, status auditing, retention and server-side filtering.
- Live HTTPS test passed: registration, diagnostic upload/receipt, retry receipt equality, redaction, authenticated admin access, CSRF rejection, request status/history and logout. Only exact synthetic test records were removed afterwards; no customer data was changed.
- IP SAN certificate obtained successfully; OpenSSL verified the IP and chain using **only ISRG Root X1**, with no system CA path/store. Certificate issuer: Let's Encrypt YR2.
- Renewal dry run passed. A dedicated twice-daily renewal timer is enabled and reloads Nginx after renewal.
- Public CA resource was downloaded from Let's Encrypt and its DER SHA-256 fingerprint checked: `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6`.
- 11 new Android support policy tests compiled and passed in an isolated JVM/JUnit run: redaction, metadata allowlist, UTF-8 byte cap, exact-payload identity, strict receipts, 10-minute receipt recovery and error descriptions.
- Android debug/release Kotlin compilation completed. The subsequent full Gradle test/lint/package command did **not** complete (see below).
- XML parsing, JavaScript syntax checks and `git diff --check` passed. Static security review found no remaining blocking issue in the reviewed support flow.

## Not verified / environment blocker

The full `testDebugUnitTest lintDebug assembleRelease` run failed at `compileDebugJavaWithJavac` with `java.nio.file.AccessDeniedException` opening the generated `.../processDebugResources/R.jar`. A single-worker retry failed at the same access boundary. Generated Kotlin classes exist, and the same jar can be read from the shell, but that does not establish why the Java compiler is denied. No permissions were relaxed and no access checks were bypassed. The full Android suite, current lint result and APK packaging are therefore **not claimed to pass**. Run the full CI/build before publishing an APK.

No real Android/Fire TV end-to-end upload, browser visual QA, optional browser WebMCP contract check or long-duration playback test was performed. Exact-payload retries are deduplicated; reopening a diagnostic dialog or restarting the process may take a new log snapshot and intentionally create a different report. This work is a diagnostic collection path, not evidence that every old-stick playback failure is resolved.

After the successful VPS smoke tests and final dashboard JavaScript upload, a final read-back attempt was denied by the local execution environment's network permissions (both Node and SSH). The live-service and TLS results above refer to the earlier successful checks; the last byte-for-byte remote JavaScript hash check could not be completed. No attempt was made to bypass that restriction.

## Handoff / operations

Dashboard credentials are separate from SSH/root and are not stored in Git. A root-only server copy is at `/etc/kululu-support/admin-access.txt`; the user's local handoff file is outside the repository with access restricted to that Windows user and SYSTEM. Rotate the SSH password previously shared in conversation; this task did not change it.

See `support-desk/README.md` for service paths, limits, retention, verification and backup guidance. No off-host backup destination or alert recipient was supplied. New installed APKs must include this integration before customer reports appear here; currently installed APKs cannot be changed by deploying the dashboard alone.
