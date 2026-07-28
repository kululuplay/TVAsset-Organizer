# Repository Workflow Notes

## GitHub access

- This repository is connected to the authorized GitHub Connector in ChatGPT/Codex.
- A missing local `gh` CLI is not a blocker for publishing or merging work.
- Use local `git` for staging, commits, and branch pushes; use the GitHub Connector for repository metadata, pull-request creation, check/status inspection, and merging.
- Request `gh` installation only if both the GitHub Connector and the existing Git remote authentication are demonstrably unavailable for the required action.
