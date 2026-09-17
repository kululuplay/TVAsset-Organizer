# Release health gate

An automatic **emergency brake** for the GitHub-only in-app rollout
(`UPDATE_ROLLOUT.md`). It never opens a rollout and never lifts a pause; it only
sets `paused: true` when the fleet's playback QoE for the version being
distributed is measurably worse than the floor or the previous release.

```
GitHub Actions (every 30 min)             crash-receiver
release-health.yml ──► release_health_gate.py ──► GET /api/release-health?version=X&hours=24
        │                                              (X-Kululu-Ops-Key)
        │  verdict == "degraded"
        ├──► PUT contents/update-rollout.json  {..., "paused": true}   (CAS on file SHA)
        └──► issue "Rollout auto-paused: vX"   (or a comment on the open one)
```

## Endpoint: `GET /api/release-health`

- Auth: header `X-Kululu-Ops-Key`, compared timing-safe with env
  `RELEASE_HEALTH_KEY` on the crash receiver. Key unset → `503
  release_health_disabled` (the gate then fails loudly instead of silently
  passing). Wrong key → `401`. Rate limited per IP (60/h).
- Query: `version` (`x.y.z`, required), `hours` (1–168, default 24).
- Response:

```json
{
  "version": "1.5.90", "windowHours": 24,
  "sessions": 812, "crashReports": 3,
  "crashFreeSessionRate": 0.988, "stallSessionRate": 0.12,
  "rebufferSecPerHour": 9.4, "ttffP50Ms": 900, "ttffP90Ms": 2100, "engineSwitchRate": 0.03,
  "baseline": { "version": "1.5.89", "windowHours": 168, "sessions": 6210, "crashReports": 18,
                "crashFreeSessionRate": 0.991, "stallSessionRate": 0.11,
                "ttffP50Ms": 880, "ttffP90Ms": 2000, "...": "same fields" },
  "verdict": "healthy", "reasons": [],
  "thresholds": { "MIN_SESSIONS": 200, "MIN_CRASH_FREE": 0.97, "CRASH_FREE_DROP": 0.02,
                  "STALL_RATIO": 1.5, "STALL_MARGIN": 0.02, "TTFF_RATIO": 1.5, "MIN_BASELINE": 50 }
}
```

Metric definitions (all from `qoe_sessions`, see `docs/qoe-dashboard.md`):

| Field | Definition |
| --- | --- |
| `sessions` | QoE session summaries received for `version` in the window. |
| `crashReports` | Rows in `crash_reports` for `version` in the window. |
| `crashFreeSessionRate` | `(sessions without failure codes − crashReports) / sessions`. A crashed app never sends its session summary, so crash reports are subtracted from the clean count. |
| `stallSessionRate` | Sessions with `rebuffer_count > 0` / sessions. |
| `ttffP50Ms` / `ttffP90Ms` | `percentile_cont` over `time_to_first_frame_ms`. |
| `baseline` | The most-used **older** version in the prior 7 days (`null` when none), same metrics over 168 h. |

## Verdict rules

Implemented as the pure `evaluateReleaseHealth(current, baseline)` in
`crash-receiver/telemetry-store.js`; the constants are `RELEASE_HEALTH` at the
top of that section and are echoed in the response as `thresholds`.

1. `insufficient` — `sessions < 200`. Nothing is concluded.
2. `degraded` — any of:
   - `crashFreeSessionRate < 0.97` (absolute floor, applies without a baseline);
   - `crashFreeSessionRate < baseline − 0.02`;
   - `stallSessionRate > baseline × 1.5 + 0.02`;
   - `ttffP90Ms > baseline × 1.5`.
   The relative rules only apply when a baseline with ≥ 50 sessions exists.
3. `healthy` — otherwise. `reasons` lists every rule that fired.

## The gate script (`scripts/release_health_gate.py`)

Environment: `RELEASE_HEALTH_URL` (https origin of the crash receiver),
`RELEASE_HEALTH_KEY`, `GITHUB_TOKEN`, `GITHUB_REPOSITORY`.

1. Reads `update-rollout.json` from `main` through the contents API and takes
   `targetVersion`.
2. Skips (exit 0, nothing written) when the policy is already `paused`, when
   the tag `vX` has no published release (pre-armed policy), or when the
   release is older than **72 h** — by then the fleet has it and pausing would
   only take `stableVersion` away from stragglers.
3. Calls the endpoint. `healthy` / `insufficient` → logs and exits 0.
4. `degraded` → writes the policy back with only `paused: true` changed,
   using the file SHA (compare-and-swap; a concurrent operator edit makes the
   run fail rather than overwrite), commit message
   `Auto-pause rollout vX: <reasons>`; then opens the issue
   **"Rollout auto-paused: vX"** (label `rollout`) with the metrics table, or
   comments on it when it is already open.

Exit code is 0 in every decided case; only API/network errors (including a
503 from a receiver without `RELEASE_HEALTH_KEY`) fail the job so they show up
in the Actions tab.

Workflow: `.github/workflows/release-health.yml` — `schedule: */30 * * * *`
plus `workflow_dispatch`, `permissions: contents: write, issues: write`,
secrets `RELEASE_HEALTH_URL` and `RELEASE_HEALTH_KEY`.

## After an auto-pause

Devices that have not installed `targetVersion` are offered `stableVersion`
(see `UPDATE_ROLLOUT.md`). Investigate with the panel's QoE section and
`/api/qoe/summary`, fix or accept, then lift the hold by hand:

```sh
GH_TOKEN=... python3 scripts/activate_release_rollout.py \
  --repo kululuplay/TVAsset-Organizer --version 1.5.90 \
  --verified-apk KululuIPTV-v1.5.90.apk --force
```

The gate will re-check the same version on its next run; if it is still
degraded within 72 h of publication it pauses again, so a genuinely broken
build cannot be forced through by repeated `--force` alone — publish a fix.

## Tests

- `node --test crash-receiver/qoe-release-health.test.js` — verdict rules and
  metric shaping.
- `python -m pytest scripts/test_release_health_gate.py` — decision logic and
  fake-HTTP runs of the whole script (pause + issue, comment on existing issue,
  skips, CAS conflict, auth/URL guards).
