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
- **Post-login landing is `DashboardActivity`** (gradient-tile launcher), NOT `HomeActivity`. SplashActivity + LoginActivity route to Dashboard; `HomeActivity` is the 3-pane *Live browser* reached via the Live tile.
  **Why:** user wanted a Smarters-style launcher home. No dedicated Favorites/Search screen exists yet — those tiles/buttons route to HomeActivity as a stopgap.

## Locale parity: base values/ can drift BEHIND translations
The base `values/strings.xml` (English, no values-en folder) is the runtime fallback.
A real bug occurred where base had 137 keys while every translated locale (tr/de/fr/nl/ar)
had 166 — 28 keys existed only in translations. In the default/English locale, any
R.string ref to a missing key throws Resources.NotFoundException at runtime (crash),
even though R.* compiles fine (R is the union of all locales).
**Why:** new strings were added to translations but the base was forgotten.
**How to apply:** parity check must be BIDIRECTIONAL and include base — compare base key
set against EACH locale both ways (comm -13 and comm -23), not just locale-vs-base.
When adding strings to base, copy the EXACT positional placeholders from a translation
(e.g. %1$d / %2$s, \n in diag_device_format) so String.format won't crash.
