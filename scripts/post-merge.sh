#!/bin/bash
# Post-merge setup for Kululu IPTV (com.iptv.player).
#
# This is a source-only Android (Kotlin/Gradle) project: the release APK is
# built by GitHub Actions, not in this environment. A full local Gradle build
# exceeds the post-merge time budget, and there are no JS/Python dependencies
# to install nor runtime migrations to apply (Room migrations run on-device at
# app start). So there is nothing to set up after a merge — this script just
# performs a quick, idempotent sanity check on the project layout and exits.
set -e

if [ ! -f "IptvPlayer/settings.gradle.kts" ] && [ ! -f "IptvPlayer/settings.gradle" ]; then
  echo "WARNING: IptvPlayer Gradle project not found at expected path." >&2
fi

echo "Post-merge: no setup required (Android source-only; APK built via CI)."
