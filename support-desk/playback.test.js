"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { playbackPayload, diagnose, presentDevice, freshWindowMs, RETENTION_MS } = require("./playback");
const { createApp } = require("./app");
const { passwordHash, hash } = require("./security");
const now = 1790000000000;
const session = (extra = {}) => ({ schema: 1, session_id: crypto.randomUUID(), content_kind: "LIVE_TV", started_at_epoch_ms: now - 30000, initial_engine: "EXO_PLAYER", final_engine: "EXO_PLAYER", transport: "MPEG_TS", session_duration_ms: 30000, state: "PLAYING", state_duration_ms: 25000, frames_known: true, rendered_frames: 600, dropped_frames: 0, last_frame_age_ms: 10, final: false, ...extra });
const payload = (extra = {}) => ({ schema: 1, sampleId: crypto.randomUUID(), sampledAtMs: now, device: { manufacturer: "Amazon", model: "AFTSS", appVersion: "1.5.90", networkType: "WIFI", networkConnected: true }, sessions: [session()], ...extra });

test("playback schema accepts closed metrics and preserves unknown as absent", () => {
  const body = payload(); const result = playbackPayload(body, now);
  assert.deepEqual(result, body);
  assert.equal("availableMemoryMb" in result.device, false);
  assert.equal("current_buffer_ms" in result.sessions[0], false);
});
test("playback rejects open-ended metadata, raw errors and credentials", () => {
  for (const mutate of [p => p.url = "https://private/", p => p.device.username = "secret", p => p.sessions[0].exception = "private", p => p.sessions[0].failure_codes = "arbitrary credential text", p => p.sessions[0].failure_http_statuses = "200", p => p.sessions[0].content_key = "channel url", p => p.sessions[0].final = "false"] ) {
    const body = payload(); mutate(body); assert.equal(playbackPayload(body, now), null);
  }
});
test("safe video format accepts only bounded enums and finite dimensions/rates", () => {
  const fields = { video_codec: "H265", video_decoder: "HARDWARE", video_width: 1920, video_height: 1080, frame_rate: 29.97 };
  const body = payload({ sessions: [session(fields)] });
  assert.deepEqual(playbackPayload(body, now), body);
  assert.equal(diagnose({}, body.sessions).status, "healthy");
  for (const bad of [{ video_codec: "video/h265" }, { video_decoder: "c2.amlogic.avc.decoder" }, { video_width: 0 }, { video_width: 16385 }, { video_height: 1080.5 }, { frame_rate: 0 }, { frame_rate: 241 }, { frame_rate: Infinity }, { frame_rate: NaN }, { frame_rate: "29.97" }]) assert.equal(playbackPayload(payload({ sessions: [session(bad)] }), now), null);
  assert.equal(diagnose({}, [session({ ...fields, video_codec: "OTHER", video_decoder: "SOFTWARE" })]).status, "healthy");
  assert.equal(diagnose({}, [session({ ...fields, frames_known: false })]).status, "unknown");
});
test("playback rejects duplicates, too many sessions, unsafe numbers and stale/future clocks", () => {
  const sample = session();
  for (const body of [payload({ sessions: [sample, sample] }), payload({ sessions: Array.from({ length: 17 }, () => session()) }), payload({ sampledAtMs: now - RETENTION_MS - 1 }), payload({ sampledAtMs: now + 300001 }), payload({ sessions: [session({ session_duration_ms: -1 })] }), payload({ sessions: [session({ dropped_frames: Number.MAX_SAFE_INTEGER + 1 })] })]) assert.equal(playbackPayload(body, now), null);
});
test("optional display labels redact URL and credentials", () => {
  const result = playbackPayload(payload({ sessions: [session({ content_label: "TR https://host/live/user/pass/1.ts token=PRIVATE test@example.com" })] }), now);
  assert.doesNotMatch(result.sessions[0].content_label, /host|user|pass|PRIVATE|example/);
});
test("startup, buffering and known frame gaps provide distinct measured evidence", () => {
  for (const [row, proof] of [[session({ state: "STARTING", state_duration_ms: 15000 }), /açılış/], [session({ state: "BUFFERING", state_duration_ms: 5000 }), /veri bekliyor/], [session({ last_frame_age_ms: 8000 }), /video karesi/]]) {
    const d = diagnose({}, [row]); assert.equal(d.status, "problem"); assert.match(d.evidence.join(" "), proof);
  }
  assert.equal(diagnose({}, [session({ frames_known: false, last_frame_age_ms: 99000 })]).status, "unknown");
  assert.equal(diagnose({}, [session({ rendered_frames: 0 })]).status, "unknown");
  assert.equal(diagnose({}, [session({ last_frame_age_ms: undefined })]).status, "unknown");
  assert.equal(diagnose({}, [session({ content_kind: "RADIO", last_frame_age_ms: 99000 })]).status, "healthy");
});
test("typed HTTP, decoder and network failures give bounded evidence without assigning blame", () => {
  const d = diagnose({}, [session({ state: "FAILED", failure_codes: "HTTP_FORBIDDEN,DNS_LOOKUP_FAILED,DECODER_RUNTIME_FAILED", failure_http_statuses: "403,," })]);
  assert.equal(d.status, "problem"); assert.equal(d.confidence, "high");
  assert.match(d.evidence.join(" "), /HTTP 403/); assert.match(d.recommendations.join(" "), /kesin neden olarak henüz belirlenemez/);
});
test("recovered playback is not permanently labelled broken by historical failure counters", () => {
  assert.equal(diagnose({}, [session({ failure_codes: "READ_TIMEOUT", rebuffer_count: 3, rebuffer_duration_ms: 20000 })]).status, "healthy");
});
test("paused, ended, missing session and stale devices are unknown rather than healthy", () => {
  for (const rows of [[], [session({ state: "PAUSED" })], [session({ state: "ENDED", final: true })]]) assert.equal(diagnose({}, rows).status, "unknown");
  assert.equal(diagnose({}, [session()], false).status, "unknown");
});

test("abandoned sessions preserve the actual reason without implying healthy playback", () => {
  const body = payload({ sessions: [session({ state: "ENDED", end_reason: "ABANDONED", final: true, frames_known: false })] });
  const parsed = playbackPayload(body, now);
  assert.ok(parsed);
  assert.equal(parsed.sessions[0].end_reason, "ABANDONED");
  assert.equal(diagnose({}, parsed.sessions).status, "unknown");
});
test("freshness uses receipt and sample times; uploading an offline sample does not imply live playback", () => {
  const row = { installation_id: crypto.randomUUID(), device: {}, sessions: [session()], last_seen_at: new Date(now), sampled_at_ms: now - 240000 };
  assert.equal(presentDevice(row, now).online, true); assert.equal(presentDevice(row, now).status, "unknown");
  row.sampled_at_ms = now + 240000; assert.equal(presentDevice(row, now).status, "unknown");
  row.sampled_at_ms = now; row.last_seen_at = new Date(now - 240000); assert.equal(presentDevice(row, now).online, false);
});
test("an idle snapshot stays fresh for six minutes because the client refreshes it only every five", () => {
  assert.equal(freshWindowMs([]), 360000); assert.equal(freshWindowMs([session()]), 120000);
  assert.equal(freshWindowMs([session({ state: "ENDED", end_reason: "COMPLETED", final: true })]), 360000);
  assert.equal(freshWindowMs([session({ state: "ENDED", final: true }), session({ state: "PAUSED" })]), 120000);
  const row = { installation_id: crypto.randomUUID(), device: {}, sessions: [], last_seen_at: new Date(now - 300000), sampled_at_ms: now - 300000 };
  const idle = presentDevice(row, now);
  assert.equal(idle.online, true); assert.equal(idle.freshWindowMs, 360000); assert.equal(idle.status, "unknown"); assert.equal(idle.diagnosis.headline, "Etkin oynatma yok");
  assert.equal(presentDevice({ ...row, last_seen_at: new Date(now - 361000), sampled_at_ms: now - 361000 }, now).online, false);
  assert.equal(presentDevice({ ...row, sessions: [session({ state: "ENDED", final: true })] }, now).online, true);
  const active = presentDevice({ ...row, sessions: [session()] }, now);
  assert.equal(active.online, false); assert.equal(active.freshWindowMs, 120000); assert.equal(active.diagnosis.headline, "Güncel ölçüm yok");
});
test("low-memory evidence does not infer an unavailable numeric measurement", () => {
  const d = diagnose({ lowMemory: true }, [session()]); assert.equal(d.status, "problem"); assert.doesNotMatch(d.evidence.join(" "), /0 MB/);
});

async function fixture(t) {
  const id = crypto.randomUUID(); const secret = crypto.randomBytes(32).toString("base64url"); const writes = [];
  const store = { authenticate: async (candidate, h) => candidate === id && h === hash(secret), playback: async (owner, sample) => { writes.push({ owner, sample }); return sample.sampleId; }, playbackList: async filter => ({ items: [], filter }), playbackDetail: async () => null, playbackChannels: async () => ({ items: [], nextCursor: null }), createIntervention: async (installation, marker) => ({ id: "1", ...marker, installationId: installation, comparison: { status: "waiting" } }) };
  const app = createApp({ store, origin: "https://support.example", adminUser: "admin", adminPasswordHash: await passwordHash("test-password"), secureCookies: false, clock: () => now });
  const server = app.listen(0, "127.0.0.1"); await new Promise(resolve => server.once("listening", resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const call = async (path, body, headers = {}) => {
    const response = await fetch(`http://127.0.0.1:${server.address().port}${path}`, { method: body === undefined ? "GET" : "POST", headers: { "Content-Type": "application/json", ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, headers: response.headers, body: await response.json() };
  };
  const login = await call("/api/admin/login", { username: "admin", password: "test-password" }, { Origin: "https://support.example" });
  return { call, writes, id, device: { Authorization: `Bearer ${id}.${secret}` }, admin: { Cookie: login.headers.get("set-cookie").split(";")[0], Origin: "https://support.example", "X-CSRF-Token": login.body.csrf } };
}
test("measurement upload authenticates ownership and acknowledges only persisted sample", async t => {
  const f = await fixture(t); const body = payload();
  assert.equal((await f.call("/api/v1/playback", body)).status, 401);
  const result = await f.call("/api/v1/playback", body, f.device);
  assert.equal(result.status, 200); assert.deepEqual(result.body, { ok: true, ackedSampleId: body.sampleId });
  assert.equal(f.writes[0].owner, f.id);
});
test("admin measurements require session and strict filters; device bearer cannot browse customers", async t => {
  const f = await fixture(t);
  for (const path of ["/api/admin/playback", `/api/admin/playback/${f.id}`]) assert.equal((await f.call(path, undefined, f.device)).status, 401);
  for (const filter of ["status=bad", "before=0", "q[]=foo", "status[]=problem"]) assert.equal((await f.call("/api/admin/playback?" + filter, undefined, f.admin)).status, 400);
  assert.equal((await f.call("/api/admin/playback?status=problem", undefined, f.admin)).status, 200);
  assert.equal((await f.call(`/api/admin/playback/${f.id}`, undefined, f.admin)).status, 404);
});
test("measurement payload cap and per-installation burst limits prevent unbounded writes", async t => {
  const f = await fixture(t);
  assert.equal((await f.call("/api/v1/playback", { value: "x".repeat(33000) }, f.device)).status, 413);
  for (let i = 0; i < 12; i++) assert.equal((await f.call("/api/v1/playback", payload(), f.device)).status, 200);
  assert.equal((await f.call("/api/v1/playback", payload(), f.device)).status, 429);
  assert.equal(f.writes.length, 12);
});
test("channel comparison route requires admin and rejects invalid filters", async t => {
  const f = await fixture(t);
  assert.equal((await f.call("/api/admin/playback/channels", undefined, f.device)).status, 401);
  assert.equal((await f.call("/api/admin/playback/channels", undefined, f.admin)).status, 200);
  for (const query of ["before=1", "q[]=private", "status=problem"]) assert.equal((await f.call("/api/admin/playback/channels?" + query, undefined, f.admin)).status, 400);
});
test("manual intervention requires same origin, admin session, CSRF and a closed note schema", async t => {
  const f = await fixture(t); const route = `/api/admin/playback/${f.id}/interventions`;
  const body = { requestId: crypto.randomUUID(), contentKey: "a".repeat(64), note: "Ethernet ile tekrar denendi." };
  assert.equal((await f.call(route, body, { ...f.admin, Origin: "https://other.example" })).status, 403);
  assert.equal((await f.call(route, body, { ...f.device, Origin: f.admin.Origin })).status, 401);
  assert.equal((await f.call(route, body, { ...f.admin, "X-CSRF-Token": "wrong" })).status, 403);
  assert.equal((await f.call(route, { ...body, command: "restart_device" }, f.admin)).status, 400);
  const success = await f.call(route, body, f.admin); assert.equal(success.status, 200); assert.equal(success.body.intervention.comparison.status, "waiting");
});
module.exports = { session, payload };
