# GitHub-only staged Android updates

The Android app reads `update-rollout.json` directly from this repository's
`main` branch and evaluates the device cohort locally. No application backend,
database, account, or Replit deployment is required.

v1.5.83 is the one-time bootstrap release that installs this client-side gate.
Do not publish v1.5.84 until v1.5.83 adoption is complete enough for the intended
rollout population: v1.5.82 and older builds cannot enforce this GitHub policy.
Staged releases therefore start with v1.5.84.

The policy is pre-armed for the next release. For example, after v1.5.83:

```json
{
  "schema": 1,
  "targetVersion": "1.5.84",
  "stableVersion": "1.5.83",
  "rolloutPercent": 0,
  "paused": true,
  "emergency": false,
  "salt": "release-1.5.84"
}
```

1. Publish the matching target while it is paused at 0%.
2. Change `paused` to `false` and raise `rolloutPercent` through 5, 25, 50,
   and 100 after observing customer feedback.
3. To stop a rollout immediately, set `paused` to `true`. Devices that have not
   installed the target remain on, or are offered, `stableVersion`.
4. `emergency: true` opens only the exact target to every device.

The app fails closed if the policy is unavailable, malformed, or older than the
newest release. A policy targeting a future version is allowed so it can be
prepared before publishing that release. Android cannot silently downgrade an
already-installed higher version.

Without a central health/telemetry service there is no truthful way to aggregate
fleet crash rates and automatically edit this GitHub policy. The app's local
playback recovery remains automatic; fleet rollout pause is an explicit GitHub
policy change.
