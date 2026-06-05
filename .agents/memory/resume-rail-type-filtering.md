---
name: Continue Watching rail type filtering
description: Why every player launch must carry an explicit resume type, and how the rail excludes non-VOD sessions.
---

# Continue Watching rail must filter by resume type

The resume table is shared by movies, episodes AND catch-up (timeshift) sessions —
they all persist a position so the player can offer resume-on-reopen. But the
Continue Watching rail must show only movies/episodes.

**Rule:** The rail DAO query filters `type IN ('movie','episode')`. Every caller
that launches the player with a resume id MUST also pass an explicit resume type
extra. ResumeKind has MOVIE/EPISODE/CATCHUP; the kind's `raw` string is what gets
persisted and what the query filters on.

**Why:** The player defaults a missing resume type to MOVIE (`ResumeKind.fromRaw`
falls back to MOVIE). So a caller that passes a resume id but no type silently
writes `type="movie"` and pollutes the rail with blank-metadata cards (this is
exactly how catch-up leaked in before the CATCHUP kind was added).

**How to apply:** When adding any new screen that opens the player with resume
support, decide its ResumeKind and pass `EXTRA_RESUME_TYPE`. If it's a session
that should NOT appear on the rail, give it a kind outside ('movie','episode')
(e.g. CATCHUP) — it keeps its resume position but stays off the rail.
