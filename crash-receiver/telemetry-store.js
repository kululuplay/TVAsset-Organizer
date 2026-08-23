"use strict";

/*
 * Closed-schema persistence for heartbeat telemetry.
 *
 * Generic stability events keep the compact columns used by the operations
 * panel. playback_qoe additionally carries a structured JSONB payload, but only
 * fields enumerated below can cross this boundary. This prevents a future app
 * bug from persisting URLs, credentials or arbitrary exception text.
 */

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const TOKEN_RE = /^[A-Z0-9_]{1,64}$/;

const CONTENT_KINDS = new Set([
  "LIVE_TV",
  "RADIO",
  "VOD_MOVIE",
  "VOD_EPISODE",
  "CATCH_UP",
]);
const ENGINES = new Set(["EXO_PLAYER", "VLC", "UNKNOWN"]);
const TRANSPORTS = new Set(["HLS", "MPEG_TS", "DASH", "PROGRESSIVE", "UNKNOWN"]);
const END_REASONS = new Set([
  "USER_STOP",
  "COMPLETED",
  "REPLACED",
  "BACKGROUND",
  "FATAL_FAILURE",
  "APP_SHUTDOWN",
]);
const FAILURE_CATEGORIES = new Set([
  "NETWORK",
  "AUTHORIZATION",
  "SOURCE",
  "FORMAT",
  "DECODER",
  "OUTPUT",
  "DRM",
  "TIMEOUT",
  "RESOURCE",
  "CANCELLED",
  "UNKNOWN",
]);
const FAILURE_PHASES = new Set([
  "RESOLVE",
  "CONNECT",
  "OPEN_SOURCE",
  "STARTUP",
  "PLAYBACK",
  "SEEK",
  "TRACK_SELECTION",
  "SHUTDOWN",
  "UNKNOWN",
]);
const FAILURE_COMPONENTS = new Set([
  "TRANSPORT",
  "MANIFEST",
  "CONTAINER",
  "VIDEO",
  "AUDIO",
  "SUBTITLE",
  "DRM",
  "PLAYER",
  "UNKNOWN",
]);
const RETRY_ADVICE = new Set([
  "WAIT_FOR_NETWORK",
  "RETRY_SAME_ROUTE",
  "TRY_ALTERNATE_TRANSPORT",
  "TRY_ALTERNATE_DECODER",
  "TRY_ALTERNATE_ENGINE",
  "DO_NOT_RETRY",
  "UNKNOWN",
]);
const AUDIO_CODECS = new Set(["AC3", "E_AC3", "AAC", "MPEG_AUDIO", "OTHER", "UNKNOWN"]);
const AUDIO_DECODERS = new Set(["HARDWARE", "SOFTWARE", "UNKNOWN"]);
const AUDIO_SINK_EVENTS = new Set(["CLOCK_STALL", "UNDERRUN", "SINK_ERROR", "CODEC_ERROR"]);
const AUDIO_OUTPUT_MODES = new Set(["PCM", "PASSTHROUGH"]);

function clip(value, max) {
  if (value == null) return null;
  const text = String(value);
  return text.length <= max ? text : text.slice(0, max);
}

function integer(value, min, max) {
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number < min || number > max) return null;
  return number;
}

function eventId(value) {
  if (typeof value !== "string") return null;
  const normalized = value.trim().toLowerCase();
  return UUID_RE.test(normalized) ? normalized : null;
}

function enumValue(value, allowed) {
  return typeof value === "string" && allowed.has(value) ? value : null;
}

function tokenList(value, allowed = null) {
  if (typeof value !== "string") return null;
  if (value === "") return "";
  const parts = value.split(",");
  if (parts.length > 8) return null;
  const safe = [];
  for (const part of parts) {
    if (!TOKEN_RE.test(part) || (allowed && !allowed.has(part))) return null;
    safe.push(part);
  }
  return safe.join(",");
}

function httpStatusList(value) {
  if (typeof value !== "string") return null;
  const parts = value.split(",");
  if (parts.length > 8) return null;
  const safe = [];
  for (const part of parts) {
    if (part === "") {
      safe.push("");
      continue;
    }
    const status = integer(part, 400, 599);
    if (status == null) return null;
    safe.push(String(status));
  }
  return safe.join(",");
}

function putIf(target, key, value) {
  if (value != null) target[key] = value;
}

/** Parse the remotely configurable playback kill-switches as a closed schema. */
function parsePlaybackPolicy(raw) {
  if (typeof raw !== "string" || !raw.trim()) return null;
  let source;
  try {
    source = JSON.parse(raw);
  } catch (_) {
    return null;
  }
  if (!source || typeof source !== "object" || Array.isArray(source)) return null;
  const policy = {};
  policy.policyVersion = integer(source.policyVersion, 1, 1) ?? 1;
  policy.ttlSeconds = integer(source.ttlSeconds, 300, 604_800) ?? 7_200;
  for (const key of ["disablePixelCopyValidation", "allowSourceEngineFallback"]) {
    if (typeof source[key] === "boolean") policy[key] = source[key];
  }
  putIf(
    policy,
    "vodConnectTimeoutMs",
    integer(source.vodConnectTimeoutMs, 5_000, 30_000),
  );
  putIf(
    policy,
    "vodReadTimeoutMs",
    integer(source.vodReadTimeoutMs, 5_000, 60_000),
  );
  return Object.keys(policy).length > 2 ? Object.freeze(policy) : null;
}

/** Return only the privacy-reviewed playback_qoe v1 fields. */
function sanitizePlaybackQoe(source) {
  if (!source || typeof source !== "object") return null;
  const schema = integer(source.schema, 1, 1);
  const sessionId = eventId(source.session_id);
  if (schema !== 1 || !sessionId) return null;

  const payload = { schema, session_id: sessionId };
  putIf(payload, "content_kind", enumValue(source.content_kind, CONTENT_KINDS));
  putIf(
    payload,
    "started_at_epoch_ms",
    integer(source.started_at_epoch_ms, 0, Number.MAX_SAFE_INTEGER),
  );
  putIf(payload, "initial_engine", enumValue(source.initial_engine, ENGINES));
  putIf(payload, "final_engine", enumValue(source.final_engine, ENGINES));
  putIf(payload, "transport", enumValue(source.transport, TRANSPORTS));
  if (
    typeof source.capability_fingerprint === "string" &&
    /^cap-v1-[0-9a-f]{64}$/.test(source.capability_fingerprint)
  ) {
    payload.capability_fingerprint = source.capability_fingerprint;
  }
  putIf(
    payload,
    "ended_at_epoch_ms",
    integer(source.ended_at_epoch_ms, 0, Number.MAX_SAFE_INTEGER),
  );
  putIf(payload, "end_reason", enumValue(source.end_reason, END_REASONS));

  for (const key of [
    "session_duration_ms",
    "time_to_ready_ms",
    "time_to_first_frame_ms",
    "rebuffer_duration_ms",
  ]) {
    putIf(payload, key, integer(source[key], 0, Number.MAX_SAFE_INTEGER));
  }
  for (const key of [
    "rebuffer_count",
    "engine_switch_count",
    "discarded_failure_count",
  ]) {
    putIf(payload, key, integer(source[key], 0, 1_000_000));
  }
  for (const key of ["rendered_frames", "dropped_frames"]) {
    putIf(payload, key, integer(source[key], 0, Number.MAX_SAFE_INTEGER));
  }

  putIf(payload, "failure_codes", tokenList(source.failure_codes));
  putIf(
    payload,
    "failure_categories",
    tokenList(source.failure_categories, FAILURE_CATEGORIES),
  );
  putIf(payload, "failure_phases", tokenList(source.failure_phases, FAILURE_PHASES));
  putIf(
    payload,
    "failure_components",
    tokenList(source.failure_components, FAILURE_COMPONENTS),
  );
  putIf(
    payload,
    "failure_retry_advice",
    tokenList(source.failure_retry_advice, RETRY_ADVICE),
  );
  putIf(payload, "failure_http_statuses", httpStatusList(source.failure_http_statuses));
  putIf(
    payload,
    "audio_failure_codecs",
    tokenList(source.audio_failure_codecs, AUDIO_CODECS),
  );
  putIf(
    payload,
    "audio_failure_decoders",
    tokenList(source.audio_failure_decoders, AUDIO_DECODERS),
  );
  putIf(
    payload,
    "audio_failure_sink_events",
    tokenList(source.audio_failure_sink_events, AUDIO_SINK_EVENTS),
  );
  putIf(
    payload,
    "audio_failure_output_modes",
    tokenList(source.audio_failure_output_modes, AUDIO_OUTPUT_MODES),
  );
  if (typeof source.final === "boolean") payload.final = source.final;
  return payload;
}

function occurredAt(value) {
  const epoch = integer(value, 0, 8_640_000_000_000_000);
  if (epoch == null) return null;
  const date = new Date(epoch);
  return Number.isNaN(date.getTime()) ? null : date;
}

function prepareRows(events, context) {
  const rows = [];
  for (const source of Array.isArray(events) ? events.slice(0, 50) : []) {
    if (!source || typeof source !== "object") continue;
    const type = clip(source.type, 40);
    if (!type) continue;
    const qoe = type === "playback_qoe" ? sanitizePlaybackQoe(source) : null;
    rows.push({
      occurredAt: occurredAt(source.t),
      deviceId: clip(context.deviceId, 128),
      appVersion: clip(context.appVersion, 500),
      versionCode: integer(context.versionCode, -2_147_483_648, 2_147_483_647),
      manufacturer: clip(context.manufacturer, 500),
      model: clip(context.model, 500),
      device: clip(context.device, 500),
      androidVersion: clip(context.androidVersion, 500),
      apiLevel: integer(context.apiLevel, -2_147_483_648, 2_147_483_647),
      type,
      severity: clip(source.sev, 16),
      nowPlaying: clip(source.ch, 200),
      nowPlayingKind: clip(source.kind, 40),
      engine: clip(source.engine || (qoe && qoe.final_engine), 24),
      stage: clip(source.stage || (qoe && qoe.end_reason), 24),
      details: clip(source.detail, type === "anr" ? 8192 : 500),
      eventId: eventId(source.event_id),
      payload: qoe,
    });
  }
  return rows;
}

/**
 * Insert a bounded batch and return only IDs proven present for this device.
 * Retried IDs are acknowledged after the idempotent conflict, while a failed
 * insert/select rejects and therefore produces no accidental client ACK.
 */
async function persistTelemetryEvents(db, events, context) {
  const rows = prepareRows(events, context);
  if (!rows.length) return [];

  const values = [];
  const tuples = [];
  let parameter = 1;
  for (const row of rows) {
    tuples.push(
      `(${Array.from({ length: 18 }, () => `$${parameter++}`).join(",")})`,
    );
    values.push(
      row.occurredAt,
      row.deviceId,
      row.appVersion,
      row.versionCode,
      row.manufacturer,
      row.model,
      row.device,
      row.androidVersion,
      row.apiLevel,
      row.type,
      row.severity,
      row.nowPlaying,
      row.nowPlayingKind,
      row.engine,
      row.stage,
      row.details,
      row.eventId,
      row.payload == null ? null : JSON.stringify(row.payload),
    );
  }

  await db.query(
    `INSERT INTO telemetry_events
      (occurred_at, device_id, app_version, version_code, manufacturer,
       model, device, android_version, api_level, type, severity,
       now_playing, now_playing_kind, engine, stage, details, event_id, payload)
     VALUES ${tuples.join(",")}
     ON CONFLICT DO NOTHING`,
    values,
  );

  const ids = [...new Set(rows.map((row) => row.eventId).filter(Boolean))];
  if (!ids.length) return [];
  const persisted = await db.query(
    `SELECT event_id::text AS event_id
       FROM telemetry_events
      WHERE device_id = $1 AND event_id = ANY($2::uuid[])`,
    [context.deviceId, ids],
  );
  const sent = new Set(ids);
  return persisted.rows
    .map((row) => eventId(row.event_id))
    .filter((id) => id && sent.has(id));
}

module.exports = {
  eventId,
  parsePlaybackPolicy,
  prepareRows,
  sanitizePlaybackQoe,
  persistTelemetryEvents,
};
