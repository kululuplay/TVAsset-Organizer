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


## Locale parity & the multi-file base gotcha (IMPORTANT)
The base `values/` locale SPLITS strings across MANY files: `strings.xml` PLUS
`strings_settings.xml`, `strings_guide.xml`, `strings_player.xml`, `strings_polish.xml`,
`strings_live.xml`, `strings_dashboard.xml`, `strings_features.xml`. Translated locales
(values-tr/de/fr/nl/ar) similarly use multiple `strings*.xml` files.
**Mistake to never repeat:** counting only `values/strings.xml` made it look like the base
was missing 28 keys vs translations; adding them caused `Duplicate resources` build failure
because they already lived in `strings_settings.xml`/`strings_guide.xml`/`strings_polish.xml`/
`strings_player.xml`.
**How to apply:** ALWAYS glob `values/strings*.xml` (and `values-XX/strings*.xml`) when
checking string parity or dup keys — never a single file. Android fails the build on
duplicate `name=` across ANY two files in the same `values/` folder. Current state: all 6
locales have 219 keys, full parity, no dups.

## aapt: unescaped apostrophe in strings breaks the build
A single raw `'` inside a `<string>` value fails aapt with a cryptic
"Can not extract resource from ParsedResource" / "Failed to compile values resource file
values-XX/values-XX.xml" — NOT a clear apostrophe message, and XML well-formedness checks
still PASS (it's an Android-specific rule, not an XML rule). High risk in fr/it (l', d', etc.).
**How to apply:** apostrophes inside string values must be `\'` (or the whole value wrapped
in "double quotes"). When touching locale strings, scan ALL locales:
`grep -rn "'" values*/*.xml | grep '<string' | grep -v "\\'"` should return nothing.
