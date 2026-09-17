"use strict";

const crypto = require("node:crypto");

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
  const overrides = sanitizeDeviceOverrides(source.deviceOverrides);
  if (overrides.length) policy.deviceOverrides = overrides;
  return Object.keys(policy).length > 2 ? Object.freeze(policy) : null;
}

// ---- Device-class overrides (playbackPolicy.deviceOverrides) ----
// Mirrors the client's DeviceOverrideMatcher limits so a rule the panel accepts
// is exactly what the app honours: at most 32 rules, regexes <= 128 chars that
// compile, closed sets of match keys and set values. Anything else is dropped
// per rule (never fails the whole policy) — the app applies the same tolerance.
const POLICY_MAX_CHARS = 32 * 1024;
const OVERRIDE_MAX_RULES = 32;
const OVERRIDE_MAX_REGEX_CHARS = 128;
const OVERRIDE_REGEX_KEYS = ["manufacturer", "model", "hardware", "board", "socModel"];
const BUFFER_MODES = new Set(["ADAPTIVE", "LOW", "NORMAL", "HIGH"]);
const PLAYER_MODES = new Set(["AUTO", "EXOPLAYER", "VLC"]);
const OVERRIDE_BOOL_SETS = [
  "tunneling",
  "livePreview",
  "allowSoftwareHdFallback",
  "compatibilityProfile",
];

function sanitizeOverrideRule(rule) {
  if (!rule || typeof rule !== "object" || Array.isArray(rule)) return null;
  const matchSrc = rule.match && typeof rule.match === "object" ? rule.match : {};
  const setSrc = rule.set && typeof rule.set === "object" ? rule.set : {};
  const match = {};
  for (const key of OVERRIDE_REGEX_KEYS) {
    if (matchSrc[key] === undefined) continue;
    const pattern = matchSrc[key];
    if (typeof pattern !== "string" || !pattern || pattern.length > OVERRIDE_MAX_REGEX_CHARS) {
      return null;
    }
    try {
      new RegExp(pattern, "i");
    } catch (_) {
      return null;
    }
    match[key] = pattern;
  }
  putIf(match, "sdkMin", integer(matchSrc.sdkMin, 1, 99));
  putIf(match, "sdkMax", integer(matchSrc.sdkMax, 1, 99));
  if (typeof matchSrc.lowRam === "boolean") match.lowRam = matchSrc.lowRam;
  putIf(match, "totalRamMaxMb", integer(matchSrc.totalRamMaxMb, 1, 1_000_000));
  const set = {};
  const bufferMode = typeof setSrc.bufferMode === "string" ? setSrc.bufferMode.toUpperCase() : null;
  if (bufferMode && BUFFER_MODES.has(bufferMode)) set.bufferMode = bufferMode;
  const startEngine = typeof setSrc.startEngine === "string" ? setSrc.startEngine.toUpperCase() : null;
  if (startEngine && PLAYER_MODES.has(startEngine)) set.startEngine = startEngine;
  for (const key of OVERRIDE_BOOL_SETS) {
    if (typeof setSrc[key] === "boolean") set[key] = setSrc[key];
  }
  if (!Object.keys(set).length) return null;
  return { match, set };
}

/** Closed-schema list of device override rules; invalid rules are dropped. */
function sanitizeDeviceOverrides(list) {
  if (!Array.isArray(list)) return [];
  return list.slice(0, OVERRIDE_MAX_RULES).map(sanitizeOverrideRule).filter(Boolean);
}

/**
 * Validate operator-entered policy text for the panel form. Unlike the lenient
 * env parser this explains WHY input is rejected, so a typo cannot be saved as
 * an (accidentally empty) policy. Returns { ok, policy, error, dropped }.
 */
function validatePlaybackPolicyText(raw) {
  const text = typeof raw === "string" ? raw.trim() : "";
  if (!text) return { ok: false, error: "empty" };
  if (text.length > POLICY_MAX_CHARS) {
    return { ok: false, error: `too large (${text.length} > ${POLICY_MAX_CHARS} chars)` };
  }
  let source;
  try {
    source = JSON.parse(text);
  } catch (e) {
    return { ok: false, error: `invalid JSON: ${e.message}` };
  }
  if (!source || typeof source !== "object" || Array.isArray(source)) {
    return { ok: false, error: "top level must be a JSON object" };
  }
  if (source.deviceOverrides !== undefined) {
    if (!Array.isArray(source.deviceOverrides)) {
      return { ok: false, error: "deviceOverrides must be an array" };
    }
    if (source.deviceOverrides.length > OVERRIDE_MAX_RULES) {
      return {
        ok: false,
        error: `too many deviceOverrides rules (${source.deviceOverrides.length} > ${OVERRIDE_MAX_RULES})`,
      };
    }
  }
  const policy = parsePlaybackPolicy(text);
  if (!policy) {
    return { ok: false, error: "no valid policy fields (check key names, ranges and rules)" };
  }
  const wanted = Array.isArray(source.deviceOverrides) ? source.deviceOverrides.length : 0;
  const kept = policy.deviceOverrides ? policy.deviceOverrides.length : 0;
  return { ok: true, policy, dropped: wanted - kept };
}

/**
 * Same predicate the app's DeviceOverrideMatcher applies, over a devices row as
 * the heartbeat stored it. Used only for the panel's "N devices match" preview.
 */
function deviceMatchesRule(rule, d) {
  const m = rule.match || {};
  const facts = {
    manufacturer: d.manufacturer,
    model: d.model,
    hardware: d.hardware,
    board: d.board,
    socModel: d.soc_model,
  };
  for (const key of OVERRIDE_REGEX_KEYS) {
    if (m[key] === undefined) continue;
    const value = facts[key];
    if (value == null) return false;
    if (!new RegExp(m[key], "i").test(String(value))) return false;
  }
  const sdk = Number(d.api_level);
  if (m.sdkMin !== undefined && !(sdk >= m.sdkMin)) return false;
  if (m.sdkMax !== undefined && !(sdk <= m.sdkMax)) return false;
  if (m.lowRam !== undefined && d.low_ram !== m.lowRam) return false;
  if (m.totalRamMaxMb !== undefined) {
    const ram = Number(d.total_ram_mb);
    if (!(ram > 0) || ram > m.totalRamMaxMb) return false;
  }
  return true;
}

/** Closed-schema staged update configuration supplied through deployment env. */
function parseUpdateRolloutPolicy(raw) {
  if (typeof raw !== "string" || !raw.trim()) return null;
  let source;
  try {
    source = JSON.parse(raw);
  } catch (_) {
    return null;
  }
  if (!source || typeof source !== "object" || Array.isArray(source)) return null;
  const targetVersion = clip(source.targetVersion, 32);
  if (!targetVersion || !/^\d+(?:\.\d+){1,3}$/.test(targetVersion)) return null;
  const stableVersion = clip(source.stableVersion, 32);
  if (stableVersion && !/^\d+(?:\.\d+){1,3}$/.test(stableVersion)) return null;
  return Object.freeze({
    policyVersion: 1,
    targetVersion,
    stableVersion: stableVersion || null,
    rolloutPercent: integer(source.rolloutPercent, 0, 100) ?? 0,
    paused: source.paused === true,
    emergency: source.emergency === true,
    salt: clip(source.salt, 64) || targetVersion,
    autoPauseEnabled: source.autoPauseEnabled !== false,
    autoPauseMinDevices: integer(source.autoPauseMinDevices, 3, 10_000) ?? 20,
    autoPauseFailurePercent:
      integer(source.autoPauseFailurePercent, 1, 100) ?? 15,
    autoPauseWindowMinutes:
      integer(source.autoPauseWindowMinutes, 5, 1_440) ?? 120,
  });
}

function rolloutBucket(deviceId, salt) {
  const digest = crypto
    .createHash("sha256")
    .update(`${String(salt)}:${String(deviceId)}`, "utf8")
    .digest();
  return digest.readUInt32BE(0) % 10_000;
}

/** Deterministic cohort decision: the same device never flaps between checks. */
function decideUpdateRollout(policy, request, runtime = {}) {
  const candidateVersion = clip(request && request.candidateVersion, 32);
  const deviceId = clip(request && request.deviceId, 128);
  if (!policy || candidateVersion !== policy.targetVersion || !deviceId) {
    return Object.freeze({ decision: "allow", managed: false });
  }
  const autoPaused = runtime.autoPaused === true;
  const paused = policy.paused || autoPaused;
  const bucket = rolloutBucket(deviceId, policy.salt);
  // A manual emergency may bypass the cohort and manual pause, but never the
  // automatic health circuit breaker: a demonstrably broken emergency build
  // must still stop spreading.
  const eligible =
    !autoPaused &&
    (policy.emergency || (!policy.paused && bucket < policy.rolloutPercent * 100));
  return Object.freeze({
    decision: eligible ? "allow" : "hold",
    managed: true,
    targetVersion: policy.targetVersion,
    stableVersion: policy.stableVersion,
    rolloutPercent: policy.rolloutPercent,
    cohort: Math.floor(bucket / 100),
    paused,
    autoPaused,
    reason: autoPaused ? clip(runtime.reason, 120) || "health_threshold" : null,
  });
}

function sanitizeSupportChecks(value) {
  if (!Array.isArray(value)) return [];
  return value
    .slice(0, 24)
    .map((entry) => {
      const key = clip(entry && entry.key, 40);
      if (!key || !/^[a-z0-9_]{1,40}$/i.test(key)) return null;
      const label = clip(entry && entry.label, 80);
      const detail = clip(entry && entry.detail, 300);
      return {
        key,
        label: label
          ? label
              .replace(/https?:\/\/\S+/gi, "<redacted>")
              .replace(/(?:(?:user|pass|token|auth)=)[^&\s]+/gi, "credential=<redacted>")
          : null,
        ok: entry && typeof entry.ok === "boolean" ? entry.ok : false,
        detail: detail
          ? detail
              .replace(/https?:\/\/\S+/gi, "<redacted>")
              .replace(/(?:(?:user|pass|token|auth)=)[^&\s]+/gi, "credential=<redacted>")
          : null,
      };
    })
    .filter(Boolean);
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

// ---- QoE session summaries (heartbeat "qoe": [...]) ----
// One row per finished playback session, aggregated by the app. The schema is
// closed and every value is typed + capped; a bad summary is dropped per item
// and never fails the beat. Engine / transport / kind names are short tokens so
// a new client engine name shows up in the dashboard without a server deploy.
const QOE_MAX_PER_BEAT = 20;
const QOE_MAX_SUMMARY_CHARS = 4096;
const QOE_SESSION_ID_RE = /^[A-Za-z0-9_.:-]{1,64}$/;
const QOE_TOKEN_RE = /^[A-Za-z0-9_-]{1,32}$/;
const QOE_CODE_RE = /^[A-Za-z0-9_.-]{1,48}$/;
const QOE_MAX_CODES = 16;
const QOE_MAX_MS = 7 * 24 * 3600 * 1000; // a week; longer durations are garbage
const QOE_MAX_COUNT = 1_000_000;
const QOE_MAX_FRAMES = 10_000_000_000;

function qoeToken(value) {
  if (typeof value !== "string") return null;
  const text = value.trim().toUpperCase();
  return QOE_TOKEN_RE.test(text) ? text : null;
}

function qoeCodeList(value) {
  if (value == null || value === "") return "";
  if (typeof value !== "string") return null;
  const parts = value
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean)
    .slice(0, QOE_MAX_CODES);
  for (const part of parts) if (!QOE_CODE_RE.test(part)) return null;
  return [...new Set(parts.map((part) => part.toUpperCase()))].join(",");
}

/**
 * Closed-schema QoE summary -> DB row fields, or null when the item is garbage.
 * Required: schema 1, session_id, session_duration_ms. Everything else is
 * optional and dropped individually when out of range.
 */
function sanitizeQoeSession(source) {
  if (!source || typeof source !== "object" || Array.isArray(source)) return null;
  let size;
  try {
    size = JSON.stringify(source).length;
  } catch (_) {
    return null;
  }
  if (size > QOE_MAX_SUMMARY_CHARS) return null;
  if (integer(source.schema, 1, 1) !== 1) return null;
  const sessionId =
    typeof source.session_id === "string" && QOE_SESSION_ID_RE.test(source.session_id.trim())
      ? source.session_id.trim()
      : null;
  if (!sessionId) return null;
  const duration = integer(source.session_duration_ms, 0, QOE_MAX_MS);
  if (duration == null) return null;

  let fingerprint = null;
  if (typeof source.capability_fingerprint === "string") {
    const hex = source.capability_fingerprint.trim().replace(/^cap-v1-/, "");
    if (/^[0-9a-f]{1,64}$/i.test(hex)) fingerprint = hex.toLowerCase();
  }
  return {
    sessionId,
    contentKind: qoeToken(source.content_kind),
    startedAt: occurredAt(source.started_at_epoch_ms),
    endedAt: occurredAt(source.ended_at_epoch_ms),
    initialEngine: qoeToken(source.initial_engine),
    finalEngine: qoeToken(source.final_engine),
    transport: qoeToken(source.transport),
    capabilityFingerprint: fingerprint,
    endReason: qoeToken(source.end_reason),
    sessionDurationMs: duration,
    timeToReadyMs: integer(source.time_to_ready_ms, 0, QOE_MAX_MS),
    timeToFirstFrameMs: integer(source.time_to_first_frame_ms, 0, QOE_MAX_MS),
    rebufferCount: integer(source.rebuffer_count, 0, QOE_MAX_COUNT) ?? 0,
    rebufferDurationMs: integer(source.rebuffer_duration_ms, 0, QOE_MAX_MS) ?? 0,
    engineSwitchCount: integer(source.engine_switch_count, 0, QOE_MAX_COUNT) ?? 0,
    renderedFrames: integer(source.rendered_frames, 0, QOE_MAX_FRAMES) ?? 0,
    droppedFrames: integer(source.dropped_frames, 0, QOE_MAX_FRAMES) ?? 0,
    failureCodes: qoeCodeList(source.failure_codes) ?? "",
    failureCategories: qoeCodeList(source.failure_categories) ?? "",
  };
}

/** Sanitize a beat's `qoe` list: at most QOE_MAX_PER_BEAT valid rows. */
function prepareQoeRows(list, context) {
  const deviceId = clip(context && context.deviceId, 128);
  if (!deviceId || !Array.isArray(list)) return [];
  const rows = [];
  const seen = new Set();
  for (const source of list.slice(0, QOE_MAX_PER_BEAT)) {
    const row = sanitizeQoeSession(source);
    if (!row || seen.has(row.sessionId)) continue;
    seen.add(row.sessionId);
    rows.push({
      deviceId,
      appVersion: clip(context.appVersion, 64),
      model: clip(context.model, 120),
      manufacturer: clip(context.manufacturer, 80),
      ...row,
    });
  }
  return rows;
}

const QOE_COLUMNS = [
  "device_id",
  "session_id",
  "app_version",
  "model",
  "manufacturer",
  "content_kind",
  "started_at",
  "ended_at",
  "initial_engine",
  "final_engine",
  "transport",
  "capability_fingerprint",
  "end_reason",
  "session_duration_ms",
  "time_to_ready_ms",
  "time_to_first_frame_ms",
  "rebuffer_count",
  "rebuffer_duration_ms",
  "engine_switch_count",
  "rendered_frames",
  "dropped_frames",
  "failure_codes",
  "failure_categories",
];

/** Insert QoE summaries; duplicates on (device_id, session_id) are ignored. */
async function persistQoeSessions(db, list, context) {
  const rows = prepareQoeRows(list, context);
  if (!rows.length) return 0;
  const values = [];
  const tuples = [];
  let parameter = 1;
  for (const row of rows) {
    tuples.push(
      `(${Array.from({ length: QOE_COLUMNS.length }, () => `$${parameter++}`).join(",")})`,
    );
    values.push(
      row.deviceId,
      row.sessionId,
      row.appVersion,
      row.model,
      row.manufacturer,
      row.contentKind,
      row.startedAt,
      row.endedAt,
      row.initialEngine,
      row.finalEngine,
      row.transport,
      row.capabilityFingerprint,
      row.endReason,
      row.sessionDurationMs,
      row.timeToReadyMs,
      row.timeToFirstFrameMs,
      row.rebufferCount,
      row.rebufferDurationMs,
      row.engineSwitchCount,
      row.renderedFrames,
      row.droppedFrames,
      row.failureCodes,
      row.failureCategories,
    );
  }
  const result = await db.query(
    `INSERT INTO qoe_sessions (${QOE_COLUMNS.join(", ")})
     VALUES ${tuples.join(",")}
     ON CONFLICT (device_id, session_id) DO NOTHING`,
    values,
  );
  return result && Number.isFinite(result.rowCount) ? result.rowCount : rows.length;
}

// ---- QoE aggregates ----
// Shared SELECT list for every QoE grouping (panel + /api/qoe/summary +
// /api/release-health), so all three report the same definitions:
//   clean_sessions  = sessions without any failure code (crash-free proxy)
//   stall_sessions  = sessions with at least one rebuffer
//   ttff_p50/p90    = percentile_cont over time_to_first_frame_ms (NULLs ignored)
const QOE_AGGREGATE_COLUMNS = `
  count(*)::int AS sessions,
  count(*) FILTER (WHERE coalesce(failure_codes, '') = '')::int AS clean_sessions,
  count(*) FILTER (WHERE rebuffer_count > 0)::int AS stall_sessions,
  coalesce(sum(rebuffer_duration_ms), 0)::bigint AS rebuffer_ms,
  coalesce(sum(session_duration_ms), 0)::bigint AS play_ms,
  coalesce(sum(engine_switch_count), 0)::bigint AS engine_switches,
  count(*) FILTER (WHERE engine_switch_count > 0)::int AS switch_sessions,
  percentile_cont(0.5) WITHIN GROUP (ORDER BY time_to_first_frame_ms) AS ttff_p50,
  percentile_cont(0.9) WITHIN GROUP (ORDER BY time_to_first_frame_ms) AS ttff_p90`;

const num = (value) => (value == null || value === "" ? null : Number(value));
const ratio = (part, whole) => (whole > 0 ? +(part / whole).toFixed(4) : null);

/** Turn one aggregate row into the metric shape every QoE consumer reports. */
function qoeMetrics(row) {
  const sessions = num(row && row.sessions) || 0;
  const clean = num(row && row.clean_sessions) || 0;
  const stalls = num(row && row.stall_sessions) || 0;
  const rebufferMs = num(row && row.rebuffer_ms) || 0;
  const playMs = num(row && row.play_ms) || 0;
  const switches = num(row && row.engine_switches) || 0;
  const switchSessions = num(row && row.switch_sessions) || 0;
  const p50 = num(row && row.ttff_p50);
  const p90 = num(row && row.ttff_p90);
  const playHours = playMs / 3_600_000;
  return {
    sessions,
    cleanSessions: clean,
    crashFreeSessionRate: ratio(clean, sessions),
    stallSessions: stalls,
    stallSessionRate: ratio(stalls, sessions),
    // Mean rebuffer seconds per hour of playback (0 when nothing played).
    rebufferSecPerHour: playHours > 0 ? +(rebufferMs / 1000 / playHours).toFixed(2) : 0,
    playbackHours: +playHours.toFixed(2),
    engineSwitches: switches,
    engineSwitchRate: ratio(switchSessions, sessions),
    ttffP50Ms: p50 == null || Number.isNaN(p50) ? null : Math.round(p50),
    ttffP90Ms: p90 == null || Number.isNaN(p90) ? null : Math.round(p90),
  };
}

// ---- Release health gate ----
// Verdict thresholds for GET /api/release-health (consumed by
// scripts/release_health_gate.py, which pauses the rollout on "degraded").
//   MIN_SESSIONS         below this the sample is too small: "insufficient".
//   MIN_CRASH_FREE       absolute floor for the crash-free session rate.
//   CRASH_FREE_DROP      degraded when worse than baseline by more than this.
//   STALL_RATIO/MARGIN   degraded when stall rate > baseline*RATIO + MARGIN.
//   TTFF_RATIO           degraded when TTFF p90 > baseline p90 * RATIO.
//   MIN_BASELINE         a baseline with fewer sessions is ignored (too noisy).
const RELEASE_HEALTH = Object.freeze({
  MIN_SESSIONS: 200,
  MIN_CRASH_FREE: 0.97,
  CRASH_FREE_DROP: 0.02,
  STALL_RATIO: 1.5,
  STALL_MARGIN: 0.02,
  TTFF_RATIO: 1.5,
  MIN_BASELINE: 50,
});

const finite = (value) => typeof value === "number" && Number.isFinite(value);

/**
 * Pure verdict: "insufficient" | "healthy" | "degraded" plus human reasons.
 * `current`/`baseline`: { sessions, crashFreeSessionRate, stallSessionRate,
 * ttffP90Ms }; a null/small baseline disables the relative comparisons.
 */
function evaluateReleaseHealth(current, baseline) {
  const c = current || {};
  const sessions = finite(c.sessions) ? c.sessions : 0;
  if (sessions < RELEASE_HEALTH.MIN_SESSIONS) {
    return {
      verdict: "insufficient",
      reasons: [`only ${sessions} sessions (need ${RELEASE_HEALTH.MIN_SESSIONS})`],
    };
  }
  const b =
    baseline && finite(baseline.sessions) && baseline.sessions >= RELEASE_HEALTH.MIN_BASELINE
      ? baseline
      : null;
  const reasons = [];
  const pct = (value) => `${(value * 100).toFixed(1)}%`;
  if (finite(c.crashFreeSessionRate)) {
    if (c.crashFreeSessionRate < RELEASE_HEALTH.MIN_CRASH_FREE) {
      reasons.push(
        `crash-free sessions ${pct(c.crashFreeSessionRate)} < ${pct(RELEASE_HEALTH.MIN_CRASH_FREE)}`,
      );
    }
    if (
      b &&
      finite(b.crashFreeSessionRate) &&
      c.crashFreeSessionRate < b.crashFreeSessionRate - RELEASE_HEALTH.CRASH_FREE_DROP
    ) {
      reasons.push(
        `crash-free sessions ${pct(c.crashFreeSessionRate)} vs baseline ${pct(b.crashFreeSessionRate)}`,
      );
    }
  }
  if (b && finite(c.stallSessionRate) && finite(b.stallSessionRate)) {
    const limit = b.stallSessionRate * RELEASE_HEALTH.STALL_RATIO + RELEASE_HEALTH.STALL_MARGIN;
    if (c.stallSessionRate > limit) {
      reasons.push(
        `stall sessions ${pct(c.stallSessionRate)} > ${pct(limit)} (baseline ${pct(b.stallSessionRate)})`,
      );
    }
  }
  if (b && finite(c.ttffP90Ms) && finite(b.ttffP90Ms) && b.ttffP90Ms > 0) {
    if (c.ttffP90Ms > b.ttffP90Ms * RELEASE_HEALTH.TTFF_RATIO) {
      reasons.push(
        `TTFF p90 ${c.ttffP90Ms} ms > baseline ${b.ttffP90Ms} ms x ${RELEASE_HEALTH.TTFF_RATIO}`,
      );
    }
  }
  return { verdict: reasons.length ? "degraded" : "healthy", reasons };
}

/** Numeric x.y[.z[.w]] compare; null when either side is not a version. */
function compareVersions(a, b) {
  const re = /^\d+(?:\.\d+){1,3}$/;
  if (typeof a !== "string" || typeof b !== "string" || !re.test(a) || !re.test(b)) return null;
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d < 0 ? -1 : 1;
  }
  return 0;
}

// ---- Playback policy signing ----
// The app verifies `sig` with an embedded P-256 public key and only then trusts
// `payload`; the unsigned `playbackPolicy` stays for older builds. `payload` is
// the exact JSON text that was signed, so the client must verify the bytes it
// received and parse that same string (never re-serialise).
function loadPolicySigningKey(pem) {
  if (typeof pem !== "string" || !pem.trim()) return null;
  // Deployment envs often store the PEM with literal "\n" sequences.
  const text = pem.includes("\\n") && !pem.includes("\n") ? pem.replace(/\\n/g, "\n") : pem;
  const key = crypto.createPrivateKey({ key: text, format: "pem" });
  const details = key.asymmetricKeyDetails || {};
  if (key.asymmetricKeyType !== "ec" || details.namedCurve !== "prime256v1") {
    throw new Error(
      `policy signing key must be P-256 (prime256v1) EC, got ${key.asymmetricKeyType}/${details.namedCurve || "?"}`,
    );
  }
  return key;
}

/** { payload, sig, kid }: ECDSA-SHA256 (DER, base64) over the UTF-8 payload. */
function signPolicyPayload(policyObject, privateKey, kid) {
  const payload = JSON.stringify(policyObject);
  const sig = crypto
    .sign("sha256", Buffer.from(payload, "utf8"), privateKey)
    .toString("base64");
  return { payload, sig, kid: String(kid || "1") };
}

module.exports = {
  eventId,
  parsePlaybackPolicy,
  sanitizeDeviceOverrides,
  validatePlaybackPolicyText,
  deviceMatchesRule,
  parseUpdateRolloutPolicy,
  decideUpdateRollout,
  sanitizeSupportChecks,
  prepareRows,
  sanitizePlaybackQoe,
  persistTelemetryEvents,
  QOE_MAX_PER_BEAT,
  QOE_AGGREGATE_COLUMNS,
  sanitizeQoeSession,
  prepareQoeRows,
  persistQoeSessions,
  qoeMetrics,
  RELEASE_HEALTH,
  evaluateReleaseHealth,
  compareVersions,
  loadPolicySigningKey,
  signPolicyPayload,
};
