# Repository Workflow Notes

## GitHub access

- This repository is connected to the authorized GitHub Connector in ChatGPT/Codex.
- A missing local `gh` CLI is not a blocker for publishing or merging work.
- Use local `git` for staging, commits, and branch pushes; use the GitHub Connector for repository metadata, pull-request creation, check/status inspection, and merging.
- Request `gh` installation only if both the GitHub Connector and the existing Git remote authentication are demonstrably unavailable for the required action.

## Production releases

- Publishing a new production version includes enabling its in-app rollout for all users (`rolloutPercent: 100`, `paused: false`). The user requested this as the default for every future release.
- Activate rollout only after the signed APK has passed verification and the matching public GitHub release and APK are available. Never activate preview builds or a failed publication.
- The Android release workflow activates each newly published version. Verify the public `update-rollout.json` endpoint after publishing. An explicit user request to pause or limit a rollout overrides this default; unrelated builds must not undo a pause.
