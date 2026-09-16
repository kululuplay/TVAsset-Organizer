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

For the newest published production release `candidate` (prereleases, drafts
and non-`x.y.z` tags are skipped) the app fetches the policy and:

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

Without a central health/telemetry service there is no truthful way to aggregate
fleet crash rates and automatically edit this GitHub policy. The app's local
playback recovery remains automatic; a fleet rollout pause is an explicit GitHub
policy change.
