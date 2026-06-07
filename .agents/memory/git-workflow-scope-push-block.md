---
name: Replit git can't push .github/workflows changes
description: Pushes that modify any .github/workflows file are rejected; do equivalent logic in app code instead.
---

Replit's git OAuth token lacks the GitHub `workflow` scope. Any push whose net diff (remote tip -> pushed tip) touches a file under `.github/workflows/` is rejected with: "refusing to allow an OAuth App to create or update workflow `.github/workflows/build.yml` without `workflow` scope". This silently blocks ALL subsequent pushes (including unrelated commits stacked on top), so releases stop publishing.

**Why:** the CI build + GitHub Release for this repo (kululuplay/TVAsset-Organizer) is driven by `.github/workflows/build.yml`; editing it to change release behavior seems natural but cannot be pushed.

**How to apply:**
- Never modify `.github/workflows/*` as part of normal feature work here.
- If a push is rejected for this reason, revert the workflow file to match `origin/main` EXACTLY (read-only check: `git --no-optional-locks show origin/main:<path> > /tmp/x && diff /tmp/x <path>` must be empty). Net-zero workflow diff makes the push succeed even though intermediate commits touched it.
- Move the desired behavior into app code instead. Example: to stop the update popup showing CI's generic "Automated build from commit <sha>." release body, filter it out in `UpdateChecker` (drop notes starting with "Automated build from commit") rather than blanking the workflow's `body:` field.
- If a workflow change is truly required, the user must apply it via GitHub web or a token that has `workflow` scope.
