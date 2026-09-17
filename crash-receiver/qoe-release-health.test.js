"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const test = require("node:test");
const {
  sanitizeQoeSession,
  prepareQoeRows,
  persistQoeSessions,
  qoeMetrics,
  RELEASE_HEALTH,
  evaluateReleaseHealth,
  compareVersions,
  loadPolicySigningKey,
  signPolicyPayload,
} = require("./telemetry-store");

const BEAT = { deviceId: "device-1", appVersion: "1.5.90", model: "AFTT", manufacturer: "Amazon" };

const summary = (extra = {}) => ({
  schema: 1,
  session_id: "s-2026-09-17-0001",
  content_kind: "LIVE",
  started_at_epoch_ms: 1_758_000_000_000,
  ended_at_epoch_ms: 1_758_000_060_000,
  initial_engine: "EXO",
  final_engine: "VLC_HW",
  transport: "HLS",
  capability_fingerprint: "ab".repeat(32),
  end_reason: "USER_STOP",
  session_duration_ms: 60_000,
  time_to_ready_ms: 800,
  time_to_first_frame_ms: 1_200,
  rebuffer_count: 2,
  rebuffer_duration_ms: 3_000,
  engine_switch_count: 1,
  rendered_frames: 1_500,
  dropped_frames: 12,
  failure_codes: "HTTP_503,DECODER_INIT",
  failure_categories: "NETWORK,DECODER",
  ...extra,
});

test("qoe summary keeps the closed schema with typed, capped values", () => {
  const row = sanitizeQoeSession(summary({ url: "https://user:pw@example.test", note: "free text" }));
  assert.deepEqual(row, {
    sessionId: "s-2026-09-17-0001",
    contentKind: "LIVE",
    startedAt: new Date(1_758_000_000_000),
    endedAt: new Date(1_758_000_060_000),
    initialEngine: "EXO",
    finalEngine: "VLC_HW",
    transport: "HLS",
    capabilityFingerprint: "ab".repeat(32),
    endReason: "USER_STOP",
    sessionDurationMs: 60_000,
    timeToReadyMs: 800,
    timeToFirstFrameMs: 1_200,
    rebufferCount: 2,
    rebufferDurationMs: 3_000,
    engineSwitchCount: 1,
    renderedFrames: 1_500,
    droppedFrames: 12,
    failureCodes: "HTTP_503,DECODER_INIT",
    failureCategories: "NETWORK,DECODER",
  });
  assert.equal("url" in row, false);
  assert.equal("note" in row, false);
});

test("qoe summary rejects garbage per item and tolerates optional fields", () => {
  assert.equal(sanitizeQoeSession(null), null);
  assert.equal(sanitizeQoeSession("x"), null);
  assert.equal(sanitizeQoeSession(summary({ schema: 2 })), null);
  assert.equal(sanitizeQoeSession(summary({ session_id: "" })), null);
  assert.equal(sanitizeQoeSession(summary({ session_id: "a".repeat(65) })), null);
  assert.equal(sanitizeQoeSession(summary({ session_id: "has space" })), null);
  assert.equal(sanitizeQoeSession(summary({ session_duration_ms: -1 })), null);
  assert.equal(sanitizeQoeSession(summary({ session_duration_ms: "soon" })), null);
  assert.equal(sanitizeQoeSession(summary({ pad: "x".repeat(4_100) })), null);
  // Optional fields degrade individually instead of failing the summary.
  const row = sanitizeQoeSession({
    schema: 1,
    session_id: "min",
    session_duration_ms: 0,
    content_kind: "not a token!",
    final_engine: "x".repeat(33),
    capability_fingerprint: "zz",
    time_to_first_frame_ms: -5,
    rebuffer_count: 1e12,
    failure_codes: "OK,bad code",
    failure_categories: "NETWORK",
  });
  assert.equal(row.contentKind, null);
  assert.equal(row.finalEngine, null);
  assert.equal(row.capabilityFingerprint, null);
  assert.equal(row.timeToFirstFrameMs, null);
  assert.equal(row.rebufferCount, 0);
  assert.equal(row.failureCodes, "");
  assert.equal(row.failureCategories, "NETWORK");
  // Tokens are upper-cased; a client-side cap-v1- prefix is stripped.
  const tokens = sanitizeQoeSession(summary({ transport: "mpeg_ts", capability_fingerprint: `cap-v1-${"A".repeat(64)}` }));
  assert.equal(tokens.transport, "MPEG_TS");
  assert.equal(tokens.capabilityFingerprint, "a".repeat(64));
});

test("a beat yields at most 20 distinct sessions with beat-level device fields", () => {
  const list = Array.from({ length: 25 }, (_, i) => summary({ session_id: `s-${i % 22}` }));
  const rows = prepareQoeRows(list, BEAT);
  assert.equal(rows.length, 20);
  assert.equal(rows[0].deviceId, "device-1");
  assert.equal(rows[0].appVersion, "1.5.90");
  assert.equal(rows[0].model, "AFTT");
  assert.equal(rows[0].manufacturer, "Amazon");
  assert.deepEqual(prepareQoeRows(list, { deviceId: "" }), []);
  assert.deepEqual(prepareQoeRows("nope", BEAT), []);
});

test("qoe rows are inserted with ON CONFLICT DO NOTHING on (device_id, session_id)", async () => {
  const calls = [];
  const db = {
    async query(sql, values) {
      calls.push({ sql, values });
      return { rowCount: 1 };
    },
  };
  const n = await persistQoeSessions(db, [summary(), { schema: 1 }], BEAT);
  assert.equal(n, 1);
  assert.equal(calls.length, 1);
  assert.match(calls[0].sql, /INSERT INTO qoe_sessions/);
  assert.match(calls[0].sql, /ON CONFLICT \(device_id, session_id\) DO NOTHING/);
  assert.equal(calls[0].values.length, 23);
  assert.equal(calls[0].values[0], "device-1");
  assert.equal(calls[0].values[1], "s-2026-09-17-0001");
  assert.equal(await persistQoeSessions(db, [], BEAT), 0);
  assert.equal(calls.length, 1);
});

test("qoe metrics derive rates from one aggregate row", () => {
  const m = qoeMetrics({
    sessions: "200",
    clean_sessions: "190",
    stall_sessions: "30",
    rebuffer_ms: "90000",
    play_ms: String(2 * 3_600_000),
    engine_switches: "12",
    switch_sessions: "10",
    ttff_p50: "812.5",
    ttff_p90: "2400",
  });
  assert.equal(m.sessions, 200);
  assert.equal(m.crashFreeSessionRate, 0.95);
  assert.equal(m.stallSessionRate, 0.15);
  assert.equal(m.rebufferSecPerHour, 45);
  assert.equal(m.playbackHours, 2);
  assert.equal(m.engineSwitchRate, 0.05);
  assert.equal(m.ttffP50Ms, 813);
  assert.equal(m.ttffP90Ms, 2400);
  const empty = qoeMetrics(undefined);
  assert.equal(empty.sessions, 0);
  assert.equal(empty.crashFreeSessionRate, null);
  assert.equal(empty.ttffP90Ms, null);
  assert.equal(empty.rebufferSecPerHour, 0);
});

const healthy = { sessions: 500, crashFreeSessionRate: 0.99, stallSessionRate: 0.1, ttffP90Ms: 2000 };
const baseline = { sessions: 5000, crashFreeSessionRate: 0.985, stallSessionRate: 0.1, ttffP90Ms: 2000 };

test("release health: insufficient below the session floor", () => {
  const r = evaluateReleaseHealth({ ...healthy, sessions: RELEASE_HEALTH.MIN_SESSIONS - 1 }, baseline);
  assert.equal(r.verdict, "insufficient");
  assert.equal(r.reasons.length, 1);
  assert.equal(evaluateReleaseHealth(null, null).verdict, "insufficient");
  assert.equal(evaluateReleaseHealth({ ...healthy, sessions: RELEASE_HEALTH.MIN_SESSIONS }, null).verdict, "healthy");
});

test("release health: absolute crash-free floor applies without a baseline", () => {
  assert.equal(evaluateReleaseHealth(healthy, null).verdict, "healthy");
  const r = evaluateReleaseHealth({ ...healthy, crashFreeSessionRate: 0.969 }, null);
  assert.equal(r.verdict, "degraded");
  assert.match(r.reasons[0], /crash-free sessions 96.9% < 97.0%/);
  // Relative rules never fire without a baseline, even with awful stalls/TTFF.
  assert.equal(
    evaluateReleaseHealth({ ...healthy, stallSessionRate: 0.9, ttffP90Ms: 99_000 }, null).verdict,
    "healthy",
  );
});

test("release health: relative rules against the baseline", () => {
  // crash-free drop > 0.02 even though above the absolute floor
  let r = evaluateReleaseHealth({ ...healthy, crashFreeSessionRate: 0.975 }, { ...baseline, crashFreeSessionRate: 0.996 });
  assert.equal(r.verdict, "degraded");
  assert.match(r.reasons[0], /vs baseline 99.6%/);
  assert.equal(
    evaluateReleaseHealth({ ...healthy, crashFreeSessionRate: 0.977 }, { ...baseline, crashFreeSessionRate: 0.996 }).verdict,
    "healthy",
  );
  // stall > baseline*1.5 + 0.02 : 0.1*1.5+0.02 = 0.17
  assert.equal(evaluateReleaseHealth({ ...healthy, stallSessionRate: 0.17 }, baseline).verdict, "healthy");
  r = evaluateReleaseHealth({ ...healthy, stallSessionRate: 0.171 }, baseline);
  assert.equal(r.verdict, "degraded");
  assert.match(r.reasons[0], /stall sessions 17.1% > 17.0%/);
  // ttff p90 > baseline*1.5
  assert.equal(evaluateReleaseHealth({ ...healthy, ttffP90Ms: 3000 }, baseline).verdict, "healthy");
  r = evaluateReleaseHealth({ ...healthy, ttffP90Ms: 3001 }, baseline);
  assert.equal(r.verdict, "degraded");
  assert.match(r.reasons[0], /TTFF p90 3001 ms > baseline 2000 ms/);
  // Several rules collect several reasons.
  r = evaluateReleaseHealth({ sessions: 300, crashFreeSessionRate: 0.9, stallSessionRate: 0.5, ttffP90Ms: 9000 }, baseline);
  assert.equal(r.verdict, "degraded");
  assert.equal(r.reasons.length, 4);
});

test("release health: a tiny or null-metric baseline is ignored", () => {
  const small = { ...baseline, sessions: RELEASE_HEALTH.MIN_BASELINE - 1 };
  assert.equal(evaluateReleaseHealth({ ...healthy, stallSessionRate: 0.5 }, small).verdict, "healthy");
  const noTtff = { ...baseline, ttffP90Ms: null, stallSessionRate: null };
  assert.equal(evaluateReleaseHealth({ ...healthy, stallSessionRate: 0.5, ttffP90Ms: 9000 }, noTtff).verdict, "healthy");
});

test("compareVersions orders numerically and rejects non-versions", () => {
  assert.equal(compareVersions("1.5.89", "1.5.90"), -1);
  assert.equal(compareVersions("1.5.90", "1.5.9"), 1);
  assert.equal(compareVersions("1.10", "1.9.0"), 1);
  assert.equal(compareVersions("1.5.90", "1.5.90"), 0);
  assert.equal(compareVersions("latest", "1.5.90"), null);
  assert.equal(compareVersions(null, "1.5.90"), null);
});

test("policy signing: P-256 ECDSA-SHA256 over the exact payload bytes, verifiable with the public key", () => {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const pem = privateKey.export({ type: "pkcs8", format: "pem" });
  const key = loadPolicySigningKey(pem);
  const policy = { policyVersion: 1, allowSourceEngineFallback: true, expiresAtEpochMs: 1_758_000_000_000 };
  const signed = signPolicyPayload(policy, key, "7");
  assert.equal(signed.kid, "7");
  assert.equal(signed.payload, JSON.stringify(policy));
  assert.deepEqual(JSON.parse(signed.payload), policy);
  const ok = crypto.verify("sha256", Buffer.from(signed.payload, "utf8"), publicKey, Buffer.from(signed.sig, "base64"));
  assert.equal(ok, true);
  const tampered = signed.payload.replace("true", "false");
  assert.equal(
    crypto.verify("sha256", Buffer.from(tampered, "utf8"), publicKey, Buffer.from(signed.sig, "base64")),
    false,
  );
  // Escaped newlines (single-line env values) are accepted; empty = no key.
  assert.ok(loadPolicySigningKey(pem.replace(/\n/g, "\\n")));
  assert.equal(loadPolicySigningKey(""), null);
  assert.equal(loadPolicySigningKey(undefined), null);
});

test("policy signing refuses non-P-256 keys", () => {
  const rsa = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey;
  assert.throws(() => loadPolicySigningKey(rsa.export({ type: "pkcs8", format: "pem" })), /P-256/);
  const p384 = crypto.generateKeyPairSync("ec", { namedCurve: "secp384r1" }).privateKey;
  assert.throws(() => loadPolicySigningKey(p384.export({ type: "pkcs8", format: "pem" })), /P-256/);
  assert.throws(() => loadPolicySigningKey("not a pem"));
});
