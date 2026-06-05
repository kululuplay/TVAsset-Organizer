---
name: In-app update checker via GitHub Releases
description: How the Kululu IPTV updater finds builds, and why it failed with no releases.
---
The in-app "check for updates" reads GitHub Releases of repo kululuplay/TVAsset-Organizer.

**Why it failed:** the repo had ZERO published Releases (CI only uploaded Actions
*artifacts*, which are NOT releases). `/releases/latest` also 404s when the only
release is a prerelease/draft. App now queries the `/releases` LIST endpoint and
picks the newest non-draft entry, so prereleases count too.

**How updates are published:** the ACTIVE workflow is the repo-root
`.github/workflows/build.yml` (GitHub ignores the nested `IptvPlayer/.github/...`
copy). On non-PR builds it auto-publishes a Release tagged `v<versionName>`.

**Signing — the critical gotcha for sideload updates:** Android only installs an
update *over* an existing app when both APKs share the SAME signing certificate;
otherwise it forces an uninstall (= total data loss). A CI **debug** keystore is
regenerated per runner run → every build has a DIFFERENT signature → the next
update can't install in place. Fix (now in place): a `release` signingConfig in
`app/build.gradle.kts` driven by env vars (`KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/
KEY_PASSWORD`), applied only when present; CI decodes secret `KEYSTORE_BASE64` to a
temp JKS, signs `assembleRelease`, and publishes the **release** APK (falls back to
debug only when secrets are absent). Keystore secrets: `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The keystore must be backed up
forever — losing it makes future in-place updates impossible. Switching existing
users from a debug-signed install to the stable-signed track needs ONE clean
reinstall.

**Auto prompt:** `UpdatePrompt.maybeShow()` runs once per process from
`DashboardActivity.onCreate`, and on "update now" opens `AboutActivity` with
`EXTRA_AUTO_CHECK=true` to reuse its download/install flow.

**How to actually ship an update:** bump `versionName` in `IptvPlayer/app/build.gradle.kts`
before pushing (bump `versionCode` too). The updater compares only numeric version
parts, so same-version pushes reuse tag `v1.0.0` and clients stay "up to date".
No bump = no detected update.
