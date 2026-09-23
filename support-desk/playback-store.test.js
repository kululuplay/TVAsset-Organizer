"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { Pool } = require("pg");
const { PgStore } = require("./store");
const { RETENTION_MS } = require("./playback");

test("PostgreSQL playback retries, ordering, rings, freshness, isolation and retention", { skip: !process.env.SUPPORT_TEST_DATABASE_URL }, async () => {
  const url = new URL(process.env.SUPPORT_TEST_DATABASE_URL);
  assert.ok(["127.0.0.1", "localhost"].includes(url.hostname));
  assert.equal(url.pathname, "/kululu_support");
  const admin = new Pool({ connectionString: url.toString() });
  const schema = "playback_test_" + crypto.randomBytes(8).toString("hex");
  assert.match(schema, /^playback_test_[a-f0-9]{16}$/);
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: url.toString(), options: `-c search_path=${schema}` });
  try {
    await pool.query(fs.readFileSync(path.join(__dirname, "schema.sql"), "utf8"));
    // Running schema on an existing installation remains safe.
    await pool.query(fs.readFileSync(path.join(__dirname, "schema.sql"), "utf8"));
    const store = new PgStore(pool, { playbackSamples: 4, playbackPerDevice: 3, playbackDevices: 3 });
    const a = crypto.randomUUID(), b = crypto.randomUUID();
    await store.register(a, "a"); await store.register(b, "b");
    const now = Date.now(); const sid = crypto.randomUUID();
    const make = (offset, fields = {}) => ({ schema: 1, sampleId: crypto.randomUUID(), sampledAtMs: now + offset, device: { model: "AFTSS", networkConnected: true }, sessions: [{ schema: 1, session_id: sid, content_kind: "LIVE_TV", started_at_epoch_ms: now - 30000, session_duration_ms: 30000 + offset, state: "PLAYING", state_duration_ms: 1000, final: false, frames_known: true, last_frame_age_ms: 10, ...fields }] });
    const first = make(0, { state: "BUFFERING", state_duration_ms: 10000, video_codec: "H264", video_decoder: "HARDWARE", video_width: 1920, video_height: 1080, frame_rate: 29.97 });
    const acks = await Promise.all([store.playback(a, first, now), store.playback(a, first, now + 1000)]);
    assert.deepEqual(acks, [first.sampleId, first.sampleId]);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_samples")).rows[0].n, 1);
    assert.equal((await store.playbackDetail(a, now)).device.lastSeenAt, new Date(now).toISOString());
    assert.equal((await store.playbackDetail(a, now)).device.sessions[0].video_codec, "H264");
    assert.equal((await store.playbackDetail(a, now)).timeline[0].sessions[0].frame_rate, 29.97);
    assert.equal((await store.playbackList({ status: "problem" }, now)).items.length, 1);
    assert.equal((await store.playbackList({ status: "all", query: "AFTSS" }, now)).items.length, 1);
    assert.equal((await store.playbackList({ status: "all", query: "AFT%" }, now)).items.length, 0);
    assert.equal(await store.playbackDetail(b, now), null);
    const newest = make(2000); await store.playback(a, newest, now + 2000);
    const old = make(1000, { state: "FAILED", final: true }); await store.playback(a, old, now + 3000);
    let detail = await store.playbackDetail(a, now + 3000);
    assert.equal(detail.device.sessions[0].state, "PLAYING");
    assert.equal(detail.device.sampledAt, new Date(now + 2000).toISOString());
    assert.equal(detail.device.lastSeenAt, new Date(now + 2000).toISOString());
    assert.equal(detail.timeline.length, 3);
    // Fourth sample prunes history, not the most recent device snapshot or receipts.
    const end = make(4000, { state: "ENDED", final: true }); await store.playback(a, end, now + 4000);
    detail = await store.playbackDetail(a, now + 4000);
    assert.equal(detail.historyLimited, true); assert.equal(detail.timeline.length, 3);
    await store.playback(a, first, now + 5000);
    assert.equal((await store.playbackDetail(a, now + 5000)).timeline.length, 3);
    assert.equal((await store.playbackDetail(a, now + 5000)).device.lastSeenAt, new Date(now + 4000).toISOString());
    // Even a new envelope cannot reopen a finalized same-session snapshot.
    await store.playback(a, make(6000), now + 6000);
    assert.equal((await store.playbackDetail(a, now + 6000)).device.sessions[0].state, "ENDED");
    const idle = { ...make(7000), sessions: [] }; await store.playback(a, idle, now + 7000);
    detail = await store.playbackDetail(a, now + 7000);
    assert.deepEqual(detail.device.sessions, []); assert.equal(detail.device.status, "unknown");
    await store.playback(b, make(8000), now + 8000);
    await store.playback(b, make(9000), now + 9000);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_samples")).rows[0].n, 4);
    assert.equal((await store.playbackList({ status: "all" }, now + 9000)).summary.devices, 2);
    assert.equal((await store.playbackList({ status: "problem" }, now + 240000)).items.length, 0);
    assert.equal((await store.playbackDetail(b, now + 240000)).device.status, "unknown");
    // Contact from a delayed offline sample is not a claim of present playback health.
    const c = crypto.randomUUID(); await store.register(c, "c");
    await store.playback(c, make(-300000), now + 10000);
    assert.equal((await store.playbackDetail(c, now + 10000)).device.status, "unknown");
    assert.equal((await store.playbackDetail(c, now + 10000)).device.online, true);
    const ticket = await store.create(a, { requestId: crypto.randomUUID(), type: "complaint", message: "Donuyor", log: "", metadata: {} });
    assert.equal((await store.detail(ticket.id)).installationId, a);
    assert.equal((await store.list({})).items[0].deviceCode, a);
    await pool.query("UPDATE support_playback_samples SET received_at=$1", [new Date(now - RETENTION_MS - 1000)]);
    await pool.query("UPDATE support_playback_receipts SET received_at=$1", [new Date(now - RETENTION_MS - 1000)]);
    await pool.query("UPDATE support_playback_devices SET last_seen_at=$1", [new Date(now - RETENTION_MS - 1000)]);
    await store.maintain();
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_samples")).rows[0].n, 0);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_receipts")).rows[0].n, 0);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_devices")).rows[0].n, 0);
    assert.ok(await store.detail(ticket.id));
    const bounded = new PgStore(pool, { playbackReceipts: 1 });
    const parallel = await Promise.allSettled([make(20000), make(21000)].map(row => bounded.playback(a, row, now + 22000)));
    assert.equal(parallel.filter(result => result.status === "fulfilled").length, 1);
    assert.equal(parallel.find(result => result.status === "rejected").reason.code, "support_capacity");
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_samples")).rows[0].n, 1);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_receipts")).rows[0].n, 1);
  } finally {
    await pool.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end();
  }
});
