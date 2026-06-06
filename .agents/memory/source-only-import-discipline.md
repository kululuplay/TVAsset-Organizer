---
name: Source-only build — explicit cross-package imports
description: This Android repo can't compile locally; scan new cross-package symbol usages for explicit imports before finishing, since CI shows only generic "Compilation error".
---

This project is source-only here: APK + GitHub Release are built by GitHub Actions on push. There is no local compiler, so unresolved-reference errors are NOT caught until CI runs.

**Rule:** Kotlin requires an explicit `import` for every symbol used from another package (no wildcards in this codebase). Whenever you add a usage of a class/object from a different package, add its import in the same edit, and grep the file's import block to confirm.

**Why:** A series feature shipped with `SeriesEntity`/`SeriesDao` used in `AppDatabase.kt` + `MediaDaos.kt` but never imported, and a logging change used `Logger` in `IptvRepository.kt` without importing it. CI failed at `:app:kspDebugKotlin` with only a generic "Compilation error. See log for more details" — the actual unresolved-reference lines were not in the attached failure tail, so the cause was non-obvious.

**How to apply:** Before declaring an Android task done, for each file you touched, grep its `^import` block and verify every newly-referenced cross-package symbol (entities, DAOs, util singletons, media3 `C`, etc.) is imported. Treat a CI `kspDebugKotlin` failure as "look for a missing import / unresolved reference first," not a Room/KSP config problem.
