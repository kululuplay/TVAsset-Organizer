"use strict";
// VPS-only integration checks. Secrets stay in memory; synthetic rows are removed by exact UUID.
const fs = require("node:fs");
const crypto = require("node:crypto");
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const { Pool } = require("pg");
async function main() {
  const config = Object.fromEntries(fs.readFileSync("/etc/kululu-support/service.env", "utf8").trim().split("\n").map(line => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]));
  // The database tests isolate themselves through the search_path connection option. Refuse to run them
  // unless this server demonstrably honours it, so no unqualified test statement can reach live tables.
  const probe = "verify_probe_" + crypto.randomBytes(8).toString("hex");
  assert.match(probe, /^verify_probe_[a-f0-9]{16}$/);
  const guard = new Pool({ connectionString: config.DATABASE_URL, max: 1 });
  try {
    await guard.query(`CREATE SCHEMA ${probe}`);
    const scoped = new Pool({ connectionString: config.DATABASE_URL, options: `-c search_path=${probe}`, max: 1 });
    try { assert.equal((await scoped.query("SELECT current_schema() AS schema")).rows[0].schema, probe, "search_path connection option is not honoured; refusing to run database tests"); }
    finally { await scoped.end(); }
  } finally { await guard.query(`DROP SCHEMA IF EXISTS ${probe}`).catch(() => {}); await guard.end(); }
  const sqlTests = fs.readdirSync("/opt/kululu-support/support-desk").filter(name => /(?:^|-)store\.test\.js$/.test(name)).map(name => "support-desk/" + name);
  execFileSync(process.execPath, ["--test", ...sqlTests], { cwd: "/opt/kululu-support", env: { ...process.env, SUPPORT_TEST_DATABASE_URL: config.DATABASE_URL }, stdio: "inherit" });
  const origin = config.SUPPORT_ORIGIN;
  const credentials = fs.readFileSync("/etc/kululu-support/admin-access.txt", "utf8");
  const password = /^Parola: (.+)$/m.exec(credentials)[1];
  const installationId = crypto.randomUUID(), secret = crypto.randomBytes(32).toString("base64url");
  const peerId = crypto.randomUUID(), peerSecret = crypto.randomBytes(32).toString("base64url");
  const sessionId = crypto.randomUUID(), incidentId = crypto.randomUUID();
  const contentKey = crypto.createHash("sha256").update("deployment-self-test-" + installationId).digest("hex");
  const pool = new Pool({ connectionString: config.DATABASE_URL });
  const call = async (path, body, headers = {}, method = body == null ? "GET" : "POST") => {
    const response = await fetch(origin + path, { method, signal: AbortSignal.timeout(15000), headers: { ...(body == null ? {} : { "Content-Type": "application/json" }), ...headers }, body: body == null ? undefined : JSON.stringify(body) });
    return { status: response.status, headers: response.headers, data: await response.json() };
  };
  try {
    assert.equal((await call("/healthz")).status, 200);
    assert.equal((await call("/api/admin/tickets")).status, 401);
    assert.equal((await call("/api/admin/playback")).status, 401);
    assert.equal((await call("/api/admin/playback/channels")).status, 401);
    assert.equal((await call("/api/v1/playback", { schema: 1 })).status, 401);
    assert.equal((await call("/api/v1/installations", { installationId, secret })).status, 200);
    const auth = { Authorization: `Bearer ${installationId}.${secret}` };
    const body = { requestId: crypto.randomUUID(), type: "diagnostic", message: "Deployment self-test — synthetic", log: 'https://test.invalid/live/user/password/1.ts\nAuthorization: Bearer TESTSECRET', metadata: { model: "Deployment self-test", appVersion: "test", playback_session_id: sessionId, playback_incident_id: incidentId, content_key: contentKey } };
    const report = await call("/api/v1/tickets", body, auth); assert.equal(report.status, 201); assert.match(report.data.code, /^K-[A-F0-9]{16}$/);
    const again = await call("/api/v1/tickets", body, auth); assert.equal(again.data.id, report.data.id);
    const request = await call("/api/v1/tickets", { requestId: crypto.randomUUID(), type: "channel", message: "Deployment self-test — request" }, auth); assert.equal(request.status, 201);
    const login = await call("/api/admin/login", { username: config.SUPPORT_ADMIN_USER, password }, { Origin: origin }); assert.equal(login.status, 200);
    const cookie = login.headers.get("set-cookie"); assert.match(cookie, /HttpOnly/); assert.match(cookie, /Secure/); assert.match(cookie, /SameSite=Strict/);
    const admin = { Origin: origin, Cookie: cookie.split(";")[0], "X-CSRF-Token": login.data.csrf };
    const detail = await call("/api/admin/tickets/" + report.data.id, null, admin); assert.equal(detail.status, 200); assert.doesNotMatch(detail.data.log, /\/user\/password\/|TESTSECRET/); assert.equal(detail.data.installationId, installationId);
    const sampledAtMs = Date.now();
    const sample = { schema: 1, sampleId: crypto.randomUUID(), sampledAtMs, device: { model: "Deployment self-test", manufacturer: "Synthetic", appVersion: "test", networkType: "WIFI", networkConnected: true }, sessions: [{ schema: 1, session_id: crypto.randomUUID(), content_kind: "LIVE_TV", started_at_epoch_ms: sampledAtMs - 30000, initial_engine: "EXO_PLAYER", final_engine: "EXO_PLAYER", transport: "MPEG_TS", session_duration_ms: 30000, state: "BUFFERING", state_duration_ms: 10000, final: false, frames_known: true, rendered_frames: 100, dropped_frames: 0, last_frame_age_ms: 10000, video_codec: "H264", video_decoder: "HARDWARE", video_width: 1920, video_height: 1080, frame_rate: 29.97, content_label: "Deployment self-test — synthetic" }] };
    Object.assign(sample.sessions[0], { session_id: sessionId, content_key: contentKey, paused_duration_ms: 0, rebuffer_count: 1, rebuffer_duration_ms: 10000, source_open_ms: 100, time_to_first_byte_ms: 200, first_byte_to_first_frame_ms: 800, source_timing_scope: "INITIAL_ATTEMPT" });
    const point = { offsetMs: 0, state: "BUFFERING", stateDurationMs: 10000, sessionDurationMs: 30000, rebufferCount: 1, rebufferDurationMs: 10000, framesKnown: true, renderedFrames: 100, droppedFrames: 0, lastFrameAgeMs: 10000, currentBufferMs: 0 };
    sample.incidents = [{ incidentId, sessionId, contentKey, trigger: "BUFFERING", triggeredAtMs: sampledAtMs, complete: false, points: [point] }];
    const measurement = await call("/api/v1/playback", sample, auth); assert.equal(measurement.status, 200); assert.equal(measurement.data.ackedSampleId, sample.sampleId);
    assert.equal((await call("/api/v1/playback", sample, auth)).data.ackedSampleId, sample.sampleId);
    assert.equal((await call("/api/admin/playback/" + installationId, null, auth)).status, 401);
    const playback = await call("/api/admin/playback/" + installationId, null, admin); assert.equal(playback.status, 200); assert.equal(playback.data.device.deviceCode, installationId); assert.equal(playback.data.device.status, "problem"); assert.equal(playback.data.timeline.length, 1); assert.equal(playback.data.timeline[0].sessions[0].frame_rate, 29.97);
    const problems = await call("/api/admin/playback?status=problem&q=" + installationId, null, admin); assert.equal(problems.status, 200); assert.equal(problems.data.items.length, 1);
    assert.equal(playback.data.incidents[0].incidentId, incidentId);
    assert.equal(playback.data.incidents[0].complete, false);
    assert.equal(playback.data.timeline[0].sessions[0].source_open_ms, 100);
    assert.equal(detail.data.metadata.playback_incident_id, incidentId);
    assert.equal((await call("/api/v1/installations", { installationId: peerId, secret: peerSecret })).status, 200);
    const peerAuth = { Authorization: `Bearer ${peerId}.${peerSecret}` };
    const peerSample = { ...sample, sampleId: crypto.randomUUID(), incidents: [], sessions: [{ ...sample.sessions[0], session_id: crypto.randomUUID(), state: "PLAYING", last_frame_age_ms: 0, current_buffer_ms: 6000 }] };
    assert.equal((await call("/api/v1/playback", peerSample, peerAuth)).status, 200);
    const channels = await call("/api/admin/playback/channels?q=" + contentKey, null, admin);
    assert.equal(channels.status, 200); assert.equal(channels.data.items[0].devices, 2); assert.equal(channels.data.items[0].status, "mixed");
    const completion = { ...sample, sampleId: crypto.randomUUID(), sampledAtMs: sampledAtMs + 2, incidents: [{ ...sample.incidents[0], complete: true, truncated: true }] };
    assert.equal((await call("/api/v1/playback", completion, auth)).status, 200);
    const incidentDetail = await call("/api/admin/playback/" + installationId, null, admin);
    assert.equal(incidentDetail.data.incidents.length, 1); assert.equal(incidentDetail.data.incidents[0].complete, true);
    const marker = { requestId: crypto.randomUUID(), contentKey, note: "Deployment self-test — synthetic operator marker" };
    const interventionPath = "/api/admin/playback/" + installationId + "/interventions";
    assert.equal((await call(interventionPath, marker, { ...admin, "X-CSRF-Token": "wrong" })).status, 403);
    const intervention = await call(interventionPath, marker, admin);
    assert.equal(intervention.status, 200); assert.equal(intervention.data.intervention.requestId, marker.requestId); assert.equal(intervention.data.intervention.comparison.status, "waiting");
    const repeatedMarker = await call(interventionPath, marker, admin);
    assert.equal(repeatedMarker.data.intervention.id, intervention.data.intervention.id);
    const idle = { ...sample, sampleId: crypto.randomUUID(), sampledAtMs: sampledAtMs + 3, sessions: [], incidents: [] };
    assert.equal((await call("/api/v1/playback", idle, auth)).status, 200);
    const stopped = await call("/api/admin/playback/" + installationId, null, admin); assert.equal(stopped.data.device.status, "unknown"); assert.deepEqual(stopped.data.device.sessions, []);
    assert.equal((await call("/api/admin/tickets/" + request.data.id, { status: "done" }, { ...admin, "X-CSRF-Token": "wrong" }, "PATCH")).status, 403);
    assert.equal((await call("/api/admin/tickets/" + request.data.id, { status: "done" }, admin, "PATCH")).status, 200);
    const history = await call("/api/v1/tickets", null, auth); assert.equal(history.data.requests[0].status, "done");
    assert.equal((await call("/api/admin/logout", {}, admin)).status, 200);
    assert.equal((await call("/api/admin/tickets", null, admin)).status, 401);
    console.log("PASS: HTTPS, tickets/session links, playback/incident ACK and completion, mixed channel comparison, startup timing, intervention idempotency/CSRF, idle/isolation and logout.");
  } finally {
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      for (const fixtureId of [installationId, peerId]) {
        await client.query("DELETE FROM support_audit WHERE ticket_id IN (SELECT id FROM support_tickets WHERE installation_id=$1)", [fixtureId]);
        for (const table of ["support_tickets", "support_playback_incidents", "support_playback_interventions", "support_playback_samples", "support_playback_receipts", "support_playback_devices"]) await client.query(`DELETE FROM ${table} WHERE installation_id=$1`, [fixtureId]);
        await client.query("DELETE FROM support_installations WHERE id=$1", [fixtureId]);
      }
      await client.query("COMMIT");
    } catch (error) { await client.query("ROLLBACK"); throw error; }
    finally { client.release(); await pool.end(); }
    console.log("Synthetic support and playback fixtures removed by exact installation UUID.");
  }
}
main().catch(error => { console.error("Support verification failed:", error.code || error.name, error.message); process.exit(1); });
