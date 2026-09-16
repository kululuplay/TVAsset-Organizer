"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
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
} = require("./telemetry-store");

const DEVICE = {
  deviceId: "device-1",
  appVersion: "1.5.79",
  versionCode: 123,
  manufacturer: "test",
  model: "tv",
  device: "box",
  androidVersion: "10",
  apiLevel: 29,
};
const ID_1 = "123e4567-e89b-42d3-a456-426614174000";
const ID_2 = "123e4567-e89b-42d3-a456-426614174001";

test("playback policy exposes only typed in-range kill switches", () => {
  assert.deepEqual(
    parsePlaybackPolicy(
      JSON.stringify({
        disablePixelCopyValidation: true,
        vodConnectTimeoutMs: 12_000,
        vodReadTimeoutMs: 45_000,
        allowSourceEngineFallback: false,
        arbitraryUrl: "https://user:pass@example.test",
      }),
    ),
    {
      policyVersion: 1,
      ttlSeconds: 7_200,
      disablePixelCopyValidation: true,
      allowSourceEngineFallback: false,
      vodConnectTimeoutMs: 12_000,
      vodReadTimeoutMs: 45_000,
    },
  );
  assert.deepEqual(
    parsePlaybackPolicy(
      '{"vodConnectTimeoutMs":4999,"vodReadTimeoutMs":60001,"unknown":true}',
    ),
    null,
  );
  assert.equal(parsePlaybackPolicy("not json"), null);
});

test("device override rules keep only closed-schema matches and sets", () => {
  const rules = sanitizeDeviceOverrides([
    {
      match: { model: "AFTM|AFTT", sdkMax: 27, lowRam: true, totalRamMaxMb: 1536, junk: 1 },
      set: { bufferMode: "high", startEngine: "ExoPlayer", tunneling: true, livePreview: false, evil: "x" },
    },
    { match: { model: "AFT(" }, set: { bufferMode: "HIGH" } }, // invalid regex
    { match: { model: "a".repeat(129) }, set: { bufferMode: "HIGH" } }, // too long
    { match: { model: 42 }, set: { bufferMode: "HIGH" } }, // not a string
    { match: {}, set: { bufferMode: "ULTRA", tunneling: "yes" } }, // nothing valid to set
    { set: { compatibilityProfile: true } }, // empty match = every device
    "not an object",
  ]);
  assert.deepEqual(rules, [
    {
      match: { model: "AFTM|AFTT", sdkMax: 27, lowRam: true, totalRamMaxMb: 1536 },
      set: { bufferMode: "HIGH", startEngine: "EXOPLAYER", tunneling: true, livePreview: false },
    },
    { match: {}, set: { compatibilityProfile: true } },
  ]);
  // Cap: 33 rules -> 32; a policy consisting only of rules is still a policy.
  const many = Array.from({ length: 33 }, () => ({ set: { bufferMode: "LOW" } }));
  assert.equal(sanitizeDeviceOverrides(many).length, 32);
  assert.equal(
    parsePlaybackPolicy(JSON.stringify({ deviceOverrides: many })).deviceOverrides.length,
    32,
  );
  assert.equal(parsePlaybackPolicy('{"deviceOverrides":[{"set":{"x":1}}]}'), null);
});

test("panel policy text validation explains rejections and counts dropped rules", () => {
  assert.equal(validatePlaybackPolicyText("").ok, false);
  assert.match(validatePlaybackPolicyText("{oops").error, /invalid JSON/);
  assert.match(validatePlaybackPolicyText("[1]").error, /object/);
  assert.match(validatePlaybackPolicyText('{"deviceOverrides":{}}').error, /array/);
  assert.match(validatePlaybackPolicyText('{"x":' + " ".repeat(33_000) + "1}").error, /too large/);
  const tooMany = JSON.stringify({
    deviceOverrides: Array.from({ length: 33 }, () => ({ set: { bufferMode: "LOW" } })),
  });
  assert.match(validatePlaybackPolicyText(tooMany).error, /too many/);
  assert.match(validatePlaybackPolicyText('{"unknown":true}').error, /no valid policy/);
  const ok = validatePlaybackPolicyText(
    JSON.stringify({
      ttlSeconds: 600,
      deviceOverrides: [
        { match: { lowRam: true }, set: { bufferMode: "LOW" } },
        { match: { model: "(" }, set: { bufferMode: "LOW" } },
      ],
    }),
  );
  assert.equal(ok.ok, true);
  assert.equal(ok.dropped, 1);
  assert.equal(ok.policy.ttlSeconds, 600);
  assert.deepEqual(ok.policy.deviceOverrides, [{ match: { lowRam: true }, set: { bufferMode: "LOW" } }]);
});

test("device row matching mirrors the app matcher (AND, case-insensitive find)", () => {
  const stick = {
    manufacturer: "Amazon",
    model: "AFTT",
    hardware: "mt8127",
    board: "sloane",
    soc_model: null,
    api_level: 25,
    low_ram: true,
    total_ram_mb: 1000,
  };
  const rule = {
    match: { manufacturer: "^amazon$", model: "aftm|aftt", sdkMin: 21, sdkMax: 27, lowRam: true, totalRamMaxMb: 1536 },
    set: { bufferMode: "HIGH" },
  };
  assert.equal(deviceMatchesRule(rule, stick), true);
  assert.equal(deviceMatchesRule(rule, { ...stick, api_level: 28 }), false);
  assert.equal(deviceMatchesRule(rule, { ...stick, low_ram: null }), false);
  assert.equal(deviceMatchesRule(rule, { ...stick, total_ram_mb: 2048 }), false);
  assert.equal(deviceMatchesRule(rule, { ...stick, model: "SHIELD" }), false);
  // socModel rule never matches a device that did not report one.
  assert.equal(deviceMatchesRule({ match: { socModel: "tegra" }, set: {} }, stick), false);
  assert.equal(deviceMatchesRule({ match: {}, set: {} }, stick), true);
});

test("update rollout policy accepts only a closed bounded schema", () => {
  assert.deepEqual(
    parseUpdateRolloutPolicy(
      JSON.stringify({
        targetVersion: "1.5.83",
        stableVersion: "1.5.82",
        rolloutPercent: 10,
        salt: "release-183",
        autoPauseMinDevices: 25,
        autoPauseFailurePercent: 12,
        autoPauseWindowMinutes: 90,
        url: "https://user:secret@example.test",
      }),
    ),
    {
      policyVersion: 1,
      targetVersion: "1.5.83",
      stableVersion: "1.5.82",
      rolloutPercent: 10,
      paused: false,
      emergency: false,
      salt: "release-183",
      autoPauseEnabled: true,
      autoPauseMinDevices: 25,
      autoPauseFailurePercent: 12,
      autoPauseWindowMinutes: 90,
    },
  );
  assert.equal(parseUpdateRolloutPolicy('{"targetVersion":"latest"}'), null);
  assert.equal(parseUpdateRolloutPolicy("not-json"), null);
});

test("rollout cohort is deterministic and pause holds every managed device", () => {
  const policy = parseUpdateRolloutPolicy(
    JSON.stringify({ targetVersion: "1.5.83", rolloutPercent: 50, salt: "x" }),
  );
  const request = { deviceId: "device-42", candidateVersion: "1.5.83" };
  assert.deepEqual(
    decideUpdateRollout(policy, request),
    decideUpdateRollout(policy, request),
  );
  const paused = decideUpdateRollout(policy, request, {
    autoPaused: true,
    reason: "fatal rate 20%",
  });
  assert.equal(paused.decision, "hold");
  assert.equal(paused.autoPaused, true);
  assert.equal(paused.reason, "fatal rate 20%");
});

test("emergency rollout bypasses cohort but never unrelated versions", () => {
  const policy = parseUpdateRolloutPolicy(
    JSON.stringify({
      targetVersion: "1.5.83",
      stableVersion: "1.5.82",
      rolloutPercent: 0,
      paused: true,
      emergency: true,
    }),
  );
  assert.equal(
    decideUpdateRollout(policy, {
      deviceId: "device-1",
      candidateVersion: "1.5.83",
    }).decision,
    "allow",
  );
  assert.equal(
    decideUpdateRollout(
      policy,
      { deviceId: "device-1", candidateVersion: "1.5.83" },
      { autoPaused: true, reason: "health threshold" },
    ).decision,
    "hold",
  );
  assert.deepEqual(
    decideUpdateRollout(policy, {
      deviceId: "device-1",
      candidateVersion: "1.5.84",
    }),
    { decision: "allow", managed: false },
  );
});

test("support checks keep only bounded redacted closed-schema rows", () => {
  assert.deepEqual(
    sanitizeSupportChecks([
      { key: "internet", ok: true, detail: "online" },
      {
        key: "provider",
        label: "Test https://private.example.test?token=hidden",
        ok: false,
        detail: "https://user:pass@example.test/live?token=secret",
      },
      { key: "bad key!", ok: true, detail: "ignored" },
    ]),
    [
      { key: "internet", label: null, ok: true, detail: "online" },
      { key: "provider", label: "Test <redacted>", ok: false, detail: "<redacted>" },
    ],
  );
});

test("playback_qoe keeps only the reviewed schema", () => {
  const payload = sanitizePlaybackQoe({
    schema: 1,
    session_id: ID_1,
    content_kind: "VOD_MOVIE",
    initial_engine: "EXO_PLAYER",
    final_engine: "VLC",
    transport: "PROGRESSIVE",
    capability_fingerprint: `cap-v1-${"a".repeat(64)}`,
    session_duration_ms: 12_345,
    rebuffer_count: 2,
    failure_categories: "NETWORK,TIMEOUT",
    failure_http_statuses: "503,",
    final: true,
    url: "https://user:secret@example.test/movie.mp4",
    detail: "must not cross the closed schema",
  });

  assert.deepEqual(payload, {
    schema: 1,
    session_id: ID_1,
    content_kind: "VOD_MOVIE",
    initial_engine: "EXO_PLAYER",
    final_engine: "VLC",
    transport: "PROGRESSIVE",
    capability_fingerprint: `cap-v1-${"a".repeat(64)}`,
    session_duration_ms: 12_345,
    rebuffer_count: 2,
    failure_categories: "NETWORK,TIMEOUT",
    failure_http_statuses: "503,",
    final: true,
  });
  assert.equal("url" in payload, false);
  assert.equal("detail" in payload, false);
});

test("invalid qoe values never leak into JSON payload", () => {
  const payload = sanitizePlaybackQoe({
    schema: 1,
    session_id: ID_1,
    content_kind: "https://credential.test",
    session_duration_ms: -1,
    failure_categories: "NETWORK,NOT_A_CATEGORY",
    capability_fingerprint: "not-a-sha256",
  });
  assert.deepEqual(payload, { schema: 1, session_id: ID_1 });
  assert.equal(sanitizePlaybackQoe({ schema: 2, session_id: ID_1 }), null);
});

test("every reviewed playback_qoe v1 field survives sanitization", () => {
  const source = {
    schema: 1,
    session_id: ID_1,
    content_kind: "VOD_EPISODE",
    started_at_epoch_ms: 1_700_000_000_000,
    initial_engine: "EXO_PLAYER",
    final_engine: "VLC",
    transport: "HLS",
    capability_fingerprint: `cap-v1-${"b".repeat(64)}`,
    ended_at_epoch_ms: 1_700_000_030_000,
    end_reason: "FATAL_FAILURE",
    session_duration_ms: 30_000,
    time_to_ready_ms: 1_000,
    time_to_first_frame_ms: 1_200,
    rebuffer_count: 1,
    rebuffer_duration_ms: 500,
    engine_switch_count: 1,
    rendered_frames: 720,
    dropped_frames: 3,
    failure_codes: "HTTP_SERVER_ERROR",
    failure_categories: "NETWORK",
    failure_phases: "PLAYBACK",
    failure_components: "TRANSPORT",
    failure_retry_advice: "RETRY_SAME_ROUTE",
    failure_http_statuses: "503",
    audio_failure_codecs: "AC3",
    audio_failure_decoders: "HARDWARE",
    audio_failure_sink_events: "UNDERRUN",
    audio_failure_output_modes: "PCM",
    discarded_failure_count: 0,
    final: true,
  };
  assert.deepEqual(sanitizePlaybackQoe(source), source);
});

test("audio evidence accepts only reviewed enum values", () => {
  const payload = sanitizePlaybackQoe({
    schema: 1,
    session_id: ID_1,
    audio_failure_codecs: "AC3,E_AC3",
    audio_failure_decoders: "HARDWARE,SOFTWARE",
    audio_failure_sink_events: "SINK_ERROR,CLOCK_STALL",
    audio_failure_output_modes: "PCM,PASSTHROUGH",
  });
  assert.equal(payload.audio_failure_codecs, "AC3,E_AC3");
  assert.equal(payload.audio_failure_decoders, "HARDWARE,SOFTWARE");
  assert.equal(payload.audio_failure_sink_events, "SINK_ERROR,CLOCK_STALL");
  assert.equal(payload.audio_failure_output_modes, "PCM,PASSTHROUGH");

  const rejected = sanitizePlaybackQoe({
    schema: 1,
    session_id: ID_1,
    audio_failure_codecs: "https://user:secret@example.test",
    audio_failure_decoders: "vendor.decoder.with.free.text",
  });
  assert.equal("audio_failure_codecs" in rejected, false);
  assert.equal("audio_failure_decoders" in rejected, false);
});

test("legacy events remain insertable but are not falsely ackable", () => {
  const rows = prepareRows(
    [{ type: "fallback", detail: "safe" }, { type: "playback_qoe", schema: 1 }],
    DEVICE,
  );
  assert.equal(rows.length, 2);
  assert.equal(rows[0].eventId, null);
  assert.equal(rows[1].payload, null);
});

test("server acks only event IDs proven persisted for the same device", async () => {
  const calls = [];
  const db = {
    async query(sql, values) {
      calls.push({ sql, values });
      if (sql.startsWith("SELECT")) return { rows: [{ event_id: ID_1 }] };
      return { rows: [] };
    },
  };
  const acked = await persistTelemetryEvents(
    db,
    [
      { event_id: ID_1, type: "fallback" },
      { event_id: ID_2, type: "fatal" },
    ],
    DEVICE,
  );
  assert.deepEqual(acked, [ID_1]);
  assert.equal(calls.length, 2);
  assert.match(calls[0].sql, /ON CONFLICT DO NOTHING/);
  assert.deepEqual(calls[1].values, [DEVICE.deviceId, [ID_1, ID_2]]);
});

test("insert failure rejects before any successful acknowledgement", async () => {
  let selects = 0;
  const db = {
    async query(sql) {
      if (sql.startsWith("SELECT")) selects += 1;
      throw new Error("database unavailable");
    },
  };
  await assert.rejects(
    persistTelemetryEvents(db, [{ event_id: ID_1, type: "fatal" }], DEVICE),
    /database unavailable/,
  );
  assert.equal(selects, 0);
});
