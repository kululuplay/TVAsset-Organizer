---
name: Resume lookups must not crash detail screens
description: Why series/movie detail screens must guard resume DB lookups, and why iterating on Room schema needs a version bump.
---

# Resume lookups must never block a detail screen from opening

Detail screens (series/movie) load resume metadata in onCreate coroutines. An
uncaught throwable there kills the process and boots the user back to the
dashboard ("home"), making the screen look like it "won't open".

**Rule:** Wrap resume/season loading in try/catch that rethrows
CancellationException and swallows the rest — resume is a non-essential overlay,
not a precondition for viewing content.

**Why:** A real crash happened where tapping any series threw the user to home.
The asymmetry was the clue: movie detail used a resume query touching only the
original `contentId`/`positionMs` columns (worked), while series detail queried
the newer `seriesId` column. On a device whose `resume` table still had the old
schema, the series query threw a SQLite "no such column" and crashed.

**Two-part fix to remember:**
1. Bump the Room `@Database` version whenever you change the resume (or any)
   entity's columns/indices — even mid-development. The DB uses
   `fallbackToDestructiveMigration`, so a version bump cleanly recreates the
   table with the full schema. Same version + changed schema = stale columns on
   already-installed devices (or an identity-hash crash) — exactly this bug.
2. Guard the detail-screen loaders so a transient/stale-cache failure degrades
   gracefully instead of crashing navigation.

**How to apply:** Any new screen that reads resume/position data on load must
guard it; any entity column/index change must bump the DB version in lockstep.
