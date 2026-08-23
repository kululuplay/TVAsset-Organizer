"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  parsePlaybackPolicy,
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
