---
name: Player unit tests are pure-JVM, run only in CI
description: How/where the player single-connection tests run, and why they avoid Robolectric
---

# Player single-connection unit tests

The single-open-connection contract (stop-before-start on zap, AUTO Exo→VLC
fallback, retry/backoff, background release/re-acquire) is covered by **pure-JVM
JUnit tests** under `IptvPlayer/app/src/test/`, not instrumentation/Robolectric.

**Why pure-JVM (no Robolectric):** the controller's Handler/Looper and engine
construction were put behind seams so tests need no Android runtime — a
`PlayerScheduler` (default `HandlerScheduler`) makes retry timing deterministic,
and `PlayerController`'s `engineFactory` lets tests inject a fake engine. The VOD
background/foreground ordering was extracted into `VodPlaybackCoordinator` (a
plain state machine the activity delegates to). A shared `ConnectionTracker`
asserts peak open connections never exceeds 1.

**Why this matters / how to apply:**
- There is **no Android SDK in the Replit container** — `./gradlew` and
  `:app:testDebugUnitTest` cannot run locally. Tests only execute in GitHub
  Actions (added a `Run unit tests` step to `.github/workflows/build.yml`).
  Verify test changes by reasoning + CI, not local runs.
- Keep these tests Robolectric-free; if you need new lifecycle/timing coverage,
  extend the seams (scheduler/factory/coordinator) rather than pulling in the
  Android runtime.
