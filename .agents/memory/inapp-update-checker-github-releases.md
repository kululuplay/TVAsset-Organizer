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

**Downloaded-APK cleanup (storage swell):** `AboutActivity.downloadApk` streams
each update to `getExternalFilesDir` as `update-<timestamp>.apk` and only deletes
the file on failure/cancel — a SUCCESSFUL download is kept and never cleaned up,
so the timestamped name left a fresh ~30-40 MB APK behind on EVERY update and the
app's on-device size kept growing. Fix: purge existing `update-*.apk` in that dir
before each download (keeps at most one). **How to apply:** if you change the
download path/filename, keep a purge-before-download step or storage swells again.

**Immutable releases (CI publish gotcha):** GitHub now serves published releases as
IMMUTABLE — their assets cannot be deleted or overwritten. `softprops/action-gh-release`
re-running on an EXISTING release tries to delete+re-upload the asset and fails with
"Cannot delete asset from an immutable release". Fix (in place): a `gh release view
v<ver>` guard step (id `rel`) gates the publish step with
`steps.rel.outputs.exists == 'false'`, so re-running CI on the same version is a no-op
and only a versionName bump creates a fresh release. The publish step needs
`permissions: contents: write` and `GH_TOKEN` for the guard's `gh` call.
