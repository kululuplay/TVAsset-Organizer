# GitHub-only in-app update rollout

The Android app reads `update-rollout.json` directly from this repository's
`main` branch and evaluates the device cohort locally. No application backend,
database, account, or Replit deployment is required.

## Default: every verified release is live for all users

Publishing a production version means enabling it for the whole fleet. This is
the repository policy (see `AGENTS.md`): the Android release workflow runs
`scripts/activate_release_rollout.py` right after the signed APK has passed
verification and the public GitHub release with its APK exists, and that script
writes

```json
{
  "schema": 1,
  "targetVersion": "1.5.89",
  "stableVersion": "1.5.88",
  "rolloutPercent": 100,
  "paused": false,
  "emergency": false,
  "salt": "release-1.5.89"
}
```

There is no staged 5 -> 25 -> 50 -> 100 ramp by default. Staging is an explicit
operator choice (below).

What the activation script does on every new publication:

- refuses drafts, prereleases, a version that is not the latest release, or an
  APK whose SHA-256 differs from the verified build;
- moves the previous `targetVersion` into `stableVersion` (the last known-good
  release the app may still offer) and mints a new `salt`;
- sets `rolloutPercent: 100`, `paused: false`, `emergency: false`;
- writes with the file's SHA (compare-and-swap) and reads the policy back, so a
  concurrent edit fails instead of being overwritten.

Verify the public endpoint after every publication:
`https://raw.githubusercontent.com/kululuplay/TVAsset-Organizer/main/update-rollout.json`.

## Manual overrides

The workflow only activates a version it just published (`gh release view`
finds nothing for that tag). Re-running the workflow for an existing version
skips activation. When the script is run by hand:

```sh
GH_TOKEN=... python3 scripts/activate_release_rollout.py \
  --repo kululuplay/TVAsset-Organizer --version 1.5.89 \
  --verified-apk KululuIPTV-v1.5.89.apk [--percent N] [--force]
```

- `--percent N` (default 100) writes `rolloutPercent: N` for a staged rollout.
  Raise it by re-running with a higher value; the cohort is deterministic per
  device, so devices admitted at 25% remain admitted at 50%.
- An operator hold survives an accidental re-run: if the policy for the SAME
  `targetVersion` has `paused: true` or `emergency: true`, the script exits with
  an error and changes nothing. `--force` is the explicit way to lift that hold
  from the script. A pause on an OLDER target never blocks a new release.
- To stop a rollout immediately, edit the file on `main` and set
  `paused: true`. Devices that have not installed the target are offered
  `stableVersion` instead (when it is newer than what they run).
- `emergency: true` opens the exact target to every device regardless of
  `paused` and `rolloutPercent`, and offers nothing else.

## Schema

| Field | Meaning |
| --- | --- |
| `schema` | Always `1`. Unknown keys or another schema fail closed. |
| `targetVersion` | The release being distributed. May be a not-yet-published version so the policy can be prepared ahead of time. |
| `stableVersion` | Last known-good release, strictly older than `targetVersion`. Set automatically to the previous target on activation. Any release at or below it is always offered, regardless of `paused` or `rolloutPercent`. Omit or `null` to offer no fallback. |
| `rolloutPercent` | 0-100. Share of devices admitted to `targetVersion`. Deterministic per device and salt. |
| `paused` | `true` holds every device off `targetVersion` (they keep, or are offered, `stableVersion`). |
| `emergency` | `true` admits every device to the exact `targetVersion` only. |
| `salt` | 1-64 characters mixed into the cohort hash; a new salt reshuffles cohorts. |

## How the app decides

For the newest published production release `candidate` (prereleases, drafts,
non-`x.y.z` tags and, from client 1.5.97, releases whose minimum Android API
is above the device are skipped, see below) the app fetches the policy and:

- `candidate == targetVersion`: allowed when `emergency`, otherwise when not
  `paused` and the device's cohort bucket is below `rolloutPercent`; else held.
- `candidate < targetVersion` (policy pre-armed for a future release): allowed
  only when `candidate <= stableVersion`; otherwise held. `emergency` does not
  admit a candidate that is not the target.
- `candidate > targetVersion` (release newer than the policy): held.
- A held candidate falls back to offering `stableVersion` when it is newer than
  the installed build; otherwise the update is deferred.

The app fails closed if the policy is unavailable, malformed, oversized, or
older than the newest release. Android cannot silently downgrade an
already-installed higher version.

v1.5.83 was the one-time bootstrap release that installed this client-side
gate; v1.5.82 and older builds cannot enforce this GitHub policy.

## Minimum Android version (`Min-Android-API`)

`update-rollout.json` deliberately has no Android-version field: every client
checks the policy against a strict key allowlist, so a new key would make the
whole fleet fail closed. A release's minimum Android API travels in the GitHub
release itself.

- The release workflow writes `Min-Android-API: <api>` as the first line of
  the release body (`Min-Android-API: 23` for Android 6.0), taken from `minSdk`
  in `IptvPlayer/app/build.gradle.kts`. `IptvPlayer/release-notes/v<version>.md`
  stays human-only; the line is prepended when the body is staged.
- `scripts/verify_android_upgrade.py` fails the release when the line is
  missing, when it differs from the signed APK's `sdkVersion`, or when the
  APK's minSdk is lower than the previous release's (minSdk only ever rises).
- Clients from 1.5.97 read the line. When it is absent (releases published
  before 1.5.97, or a hand-written body) they assume API 23 for 1.5.96 and
  newer and API 21 for anything older.
- A release whose minimum API is above the device is skipped and the next
  older production release becomes `candidate`, subject to the same rollout
  gate (its `stableVersion` fallback is also limited to installable
  releases). A device that already runs the newest release it can install
  reports "up to date". The launch prompt never announces such a release,
  the About screen never downloads it, and the APK validator refuses a
  package whose manifest requires a newer Android before the verified-digest
  shortcut can accept it ("This update requires Android N or newer").
- "Later" on the launch prompt is remembered per version: the same version is
  announced again after 24 h at the earliest. Manual checks are unaffected.

Clients older than 1.5.97 ignore the line and take the newest production
release regardless of its minimum API; their system installer then refuses
the package ("App not installed"), which is what Android 5 devices on 1.5.95
saw with 1.5.96. Raising `minSdk` again is therefore only clean for the fleet
once the devices below the new minimum run 1.5.97 or newer.

## Automatic emergency brake (release health gate)

The crash receiver aggregates per-session playback QoE (`docs/qoe-dashboard.md`)
and exposes `GET /api/release-health` (`docs/release-health.md`). The workflow
`.github/workflows/release-health.yml` runs `scripts/release_health_gate.py`
every 30 minutes:

- it looks at the current `targetVersion` only while its release is younger
  than 72 h and the policy is not already `paused`;
- verdict `degraded` (crash-free sessions below 97 %, or clearly worse than the
  previous version on crash-free rate, stall rate or start-up time, with at
  least 200 sessions) → it sets **only** `paused: true` on `main` (commit
  "Auto-pause rollout vX: …", compare-and-swap on the file SHA) and opens or
  updates the issue "Rollout auto-paused: vX" with the metrics;
- `healthy` / `insufficient` → nothing happens.

It is a brake, not an accelerator: it never raises `rolloutPercent`, never
clears a pause and never touches `emergency`. Lifting an auto-pause is the
same manual step as lifting an operator pause:

```sh
GH_TOKEN=... python3 scripts/activate_release_rollout.py \
  --repo kululuplay/TVAsset-Organizer --version 1.5.90 \
  --verified-apk KululuIPTV-v1.5.90.apk --force
```

If the version is still degraded and still younger than 72 h, the next gate run
pauses it again — publish a fix instead of forcing repeatedly. The gate needs
the repository secrets `RELEASE_HEALTH_URL` and `RELEASE_HEALTH_KEY` (the
latter also set on the crash receiver); without them the workflow fails
visibly and the rollout is unaffected.

The app's local playback recovery remains automatic; every other fleet rollout
change is still an explicit GitHub policy edit.

## Release body length

Clients before 1.5.76 show the GitHub release body verbatim inside the update
dialog; its notes pane cannot scroll and a body longer than a few lines pushes
the Update button off screen, so those devices can never update (most of the
fleet sat on 1.5.62 until the 1.5.97/1.5.98 bodies were shortened by hand on
2026-09-25). The release workflow therefore publishes only the **first
paragraph** of `IptvPlayer/release-notes/v<version>.md` after the
`Min-Android-API` line, and `scripts/verify_android_upgrade.py` rejects a body
longer than 600 characters. Keep that first paragraph a short, customer-facing
sentence; the rest of the file stays in the repository for maintainers.
