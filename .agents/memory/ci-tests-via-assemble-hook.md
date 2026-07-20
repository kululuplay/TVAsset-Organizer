---
name: CI unit tests via assemble dependsOn hook
description: How JVM unit tests run in CI when the GitHub workflow only calls assembleDebug/Release and cannot be edited.
---

# Unit tests chained onto assemble (locked CI workflow)

The GitHub Actions workflow runs ONLY `:app:assembleDebug` / `:app:assembleRelease`
and `.github/workflows/*` cannot be pushed from Replit (no `workflow` scope).
So `app/build.gradle.kts` (top level, after the dependencies block) chains tests
onto the build:

```kotlin
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
}
```

**Why:** a failing unit test must fail the APK build itself — there is no other
CI hook available. Debug-variant tests are enough (pure-JVM, variant-agnostic).

**How to apply:** new tests just go in `app/src/test` and run automatically.
Android types in tests: Mockito-mock Context (`when(ctx.applicationContext)`,
`getSharedPreferences` → `FakeSharedPreferences` in test sources); JUnit 4;
`isReturnDefaultValues = true` is set. No Robolectric. Kotlin `when` must be
backticked. If assemble suddenly fails in CI with no compile error, check the
test report — it may be a test failure, not a build failure.
