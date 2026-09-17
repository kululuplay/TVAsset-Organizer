# Playback QoE (session summaries)

The app aggregates each playback session into one small summary and drains
them with its heartbeat (`POST /api/heartbeat`, field `qoe`). The crash
receiver stores them in `qoe_sessions` and renders the **"Oynatma kalitesi
(QoE)"** section on the panel (`/`) plus `GET /api/qoe/summary` for tooling.

## Wire format (heartbeat request)

```json
{
  "deviceId": "…", "appVersion": "1.5.90", "model": "AFTT", "manufacturer": "Amazon",
  "qoe": [
    {
      "schema": 1,
      "session_id": "b4c1…",              "content_kind": "LIVE",
      "started_at_epoch_ms": 1758000000000, "ended_at_epoch_ms": 1758000060000,
      "initial_engine": "EXO",            "final_engine": "VLC_HW",
      "transport": "HLS",                 "capability_fingerprint": "9f3a…",
      "end_reason": "USER_STOP",
      "session_duration_ms": 60000,
      "time_to_ready_ms": 800,            "time_to_first_frame_ms": 1200,
      "rebuffer_count": 2,                "rebuffer_duration_ms": 3000,
      "engine_switch_count": 1,
      "rendered_frames": 1500,            "dropped_frames": 12,
      "failure_codes": "HTTP_503,DECODER_INIT",
      "failure_categories": "NETWORK,DECODER"
    }
  ]
}
```

Limits (`sanitizeQoeSession` in `crash-receiver/telemetry-store.js`): at most
20 summaries per beat, each ≤ 4 KB serialised. Required: `schema` = 1,
`session_id` (≤ 64 chars `[A-Za-z0-9_.:-]`), `session_duration_ms` (0 … 7 days).
`content_kind`, engines, `transport`, `end_reason` are short tokens
(`[A-Za-z0-9_-]{1,32}`, stored upper-cased) so a new engine name needs no server
deploy. `capability_fingerprint` is hex ≤ 64 (a `cap-v1-` prefix is stripped).
Failure lists: ≤ 16 comma-separated codes, each `[A-Za-z0-9_.-]{1,48}`.

An invalid item is dropped silently; an invalid optional field is dropped on
its own; the beat never fails because of `qoe`. Duplicates on
`(device_id, session_id)` are ignored (`ON CONFLICT DO NOTHING`), so the app may
resend until it gets a 200. Device fields (`appVersion`, `model`,
`manufacturer`) are taken from the beat, not the item.

## Storage

Table `qoe_sessions` (created in `initDb`): the fields above with typed caps,
plus `device_id`, `app_version`, `model`, `manufacturer`, `received_at`;
`UNIQUE (device_id, session_id)`; indexes on `received_at`,
`(app_version, received_at)`, `(model, received_at)`. Retention
`RETENTION_QOE_DAYS` (default 60) in the daily retention tick.

## Metrics

One SQL select list (`QOE_AGGREGATE_COLUMNS`) feeds every grouping, so the
panel, `/api/qoe/summary` and `/api/release-health` agree:

| Metric | Definition |
| --- | --- |
| Sessions | `count(*)` |
| Crash-free (proxy) | sessions with an empty `failure_codes` / sessions |
| Stall rate | sessions with `rebuffer_count > 0` / sessions |
| Rebuffer s/h | `sum(rebuffer_duration_ms)/1000` per hour of `sum(session_duration_ms)` |
| TTFF p50 / p90 | `percentile_cont(0.5 / 0.9) WITHIN GROUP (ORDER BY time_to_first_frame_ms)` (NULLs ignored) |
| Engine switch rate | sessions with `engine_switch_count > 0` / sessions |

Groupings: app version, device model ("manufacturer model", top 15 by
sessions), final engine; plus the worst 10 models by stall rate among models
with ≥ 20 sessions. The panel shows the last 24 h and the last 7 d; the worst
models table uses the 7-day window. Every query is bounded by the
`received_at` index and a `LIMIT`.

## `GET /api/qoe/summary?hours=24`

Panel auth (HTTP Basic). `hours` 1–720, default 24. Returns
`{ windowHours, totals, byVersion[], byModel[], byEngine[], worstModels[] }`
where each entry carries `sessions, cleanSessions, crashFreeSessionRate,
stallSessions, stallSessionRate, rebufferSecPerHour, playbackHours,
engineSwitches, engineSwitchRate, ttffP50Ms, ttffP90Ms` (rates are `null`
when there are no sessions).
