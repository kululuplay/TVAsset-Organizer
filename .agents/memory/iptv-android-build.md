---
name: IPTV Android build constraints
description: Non-obvious constraints for the com.iptv.player Android app (source-only, CI-built)
---
# IPTV Android player — build/release gotchas

- **No local compile.** Source lives under `IptvPlayer/`; APK is built by GitHub Actions (repo kululuplay/TVAsset-Organizer). Verify correctness by static reasoning + ripgrep, never by running gradle here.
- **Release build runs R8** (`isMinifyEnabled = true`). Any class referenced only by name from the manifest (e.g. Cast `OptionsProvider` meta-data) MUST have a `-keep` rule in `proguard-rules.pro`, or it is stripped/renamed and crashes at runtime in release only.
  **Why:** debug builds don't minify, so these breakages are invisible until the CI release APK.
- **String resources are split** across many `strings_*.xml` files per locale (en/tr/de/fr/nl/ar). When adding keys, keep 6-locale parity AND check for cross-file duplicate `name=` (Android fails the build on dup keys across files in the same `values*` dir).
- **rg gotcha:** `-oh` triggers ripgrep help; use `-oI` for only-matching without filename.
