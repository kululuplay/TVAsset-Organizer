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
copy). On non-PR builds it auto-publishes a Release tagged `v<versionName>` with the
**debug** APK attached (debug-signed = installable; the release APK is unsigned and
won't install — no keystore configured).

**How to actually ship an update:** bump `versionName` in `IptvPlayer/app/build.gradle.kts`
before pushing. The updater compares only numeric version parts, so same-version
pushes reuse tag `v1.0.0` and clients stay "up to date". No bump = no detected update.
