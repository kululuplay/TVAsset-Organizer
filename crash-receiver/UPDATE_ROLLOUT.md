# Staged Android updates

Set `UPDATE_ROLLOUT_JSON` on the crash-receiver deployment. The policy controls
only the exact `targetVersion`; unrelated emergency/future versions are not
silently hidden.

```json
{
  "targetVersion": "1.5.83",
  "stableVersion": "1.5.82",
  "rolloutPercent": 10,
  "salt": "release-1.5.83",
  "paused": false,
  "emergency": false,
  "autoPauseEnabled": true,
  "autoPauseMinDevices": 20,
  "autoPauseFailurePercent": 15,
  "autoPauseWindowMinutes": 120
}
```

- Increase `rolloutPercent` from 5 → 25 → 50 → 100 after observing fleet health.
- `paused: true` holds the target immediately and offers `stableVersion` where it
  is still a valid upgrade.
- Automatic rollback means **stopping further rollout**. Android does not permit
  an in-place downgrade for devices that already installed a higher version.
- `emergency: true` bypasses cohort percentage and a manual pause for that exact
  target, but the automatic health circuit breaker still wins.
- The same device remains in the same deterministic cohort for a release.
