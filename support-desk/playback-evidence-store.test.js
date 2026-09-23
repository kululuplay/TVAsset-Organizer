"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { Pool } = require("pg");
const { PgStore } = require("./store");

test("PostgreSQL channel comparison, incident lifecycle, measured interventions and retention", { skip: !process.env.SUPPORT_TEST_DATABASE_URL }, async () => {
  const url = new URL(process.env.SUPPORT_TEST_DATABASE_URL);
  assert.ok(["127.0.0.1", "localhost"].includes(url.hostname)); assert.equal(url.pathname, "/kululu_support");
  const admin = new Pool({ connectionString: url.toString() }); const schema = "evidence_test_" + crypto.randomBytes(8).toString("hex");
  assert.match(schema, /^evidence_test_[a-f0-9]{16}$/); await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: url.toString(), options: `-c search_path=${schema}` });
  try {
    // Every unqualified statement below must land in the throwaway schema, never in live tables.
    assert.equal((await pool.query("SELECT current_schema() AS schema")).rows[0].schema, schema);
    await pool.query(fs.readFileSync(path.join(__dirname, "schema.sql"), "utf8"));
    const store = new PgStore(pool, { incidents: 3, incidentsPerDevice: 2, interventions: 4, interventionsPerDevice: 2 }, { usageIntervalMs: 0 });
    const [a, b, c, d] = Array.from({ length: 4 }, () => crypto.randomUUID());
    for (const device of [a, b, c, d]) await store.register(device, device);
    const now = Date.now(), content = "a".repeat(64), otherContent = "b".repeat(64), sid = crypto.randomUUID();
    const make = (at, count = 0, fields = {}) => ({ schema: 1, sampleId: crypto.randomUUID(), sampledAtMs: at, device: { model: "Fire Stick", networkConnected: true }, sessions: [{ schema: 1, session_id: sid, content_kind: "LIVE_TV", content_key: content, content_label: "Kanal A", started_at_epoch_ms: now - 300000, state: "PLAYING", final: false, session_duration_ms: 300000 + at - now, state_duration_ms: 5000, rebuffer_count: count, rebuffer_duration_ms: count * 5000, paused_duration_ms: 0, frames_known: true, rendered_frames: 1000 + count * 1000, dropped_frames: count * 10, last_frame_age_ms: 1, ...fields }] });
    for (let i = 0; i < 4; i++) { const at = now - 120000 + i * 30000; await store.playback(a, make(at, i, { state: "BUFFERING", state_duration_ms: 10000 }), at); }
    await store.playback(b, make(now, 0, { content_label: "Alternate label" }), now);
    await store.playback(c, make(now - 300000), now); // Delayed offline sample is excluded.
    let groups = await store.playbackChannels({}, now);
    assert.equal(groups.items.length, 1); assert.equal(groups.items[0].devices, 2); assert.equal(groups.items[0].status, "mixed");
    assert.equal((await store.playbackChannels({ query: "Alternate" }, now)).items[0].devices, 2);
    assert.equal((await store.playbackChannels({ before: content }, now)).items.length, 0);
    assert.equal((await store.playbackChannels({}, now + 180000)).items.length, 0);
    const marker = { requestId: crypto.randomUUID(), contentKey: content, note: "Kablo ile test edildi." };
    const created = await store.createIntervention(a, marker, "operator", now);
    assert.equal(created.comparison.status, "waiting");
    assert.equal((await store.createIntervention(a, marker, "operator", now + 1)).id, created.id);
    assert.equal(await store.createIntervention(b, { ...marker, contentKey: otherContent }, "operator", now), null);
    for (let i = 0; i < 4; i++) { const at = now + i * 30000; await store.playback(a, make(at, 3), at); }
    let details = await store.playbackDetail(a, now + 150000);
    assert.equal(details.interventions[0].comparison.status, "comparable");
    assert.equal(details.interventions[0].comparison.before.rebufferCount, 3);
    assert.equal(details.interventions[0].comparison.after.rebufferCount, 0);
    assert.ok(details.interventions[0].comparison.change.bufferingPercentagePoints < 0);
    // Final before/after evidence survives bounded heartbeat history trimming.
    await pool.query(`DELETE FROM ${schema}.support_playback_samples WHERE installation_id=$1`, [a]);
    assert.equal((await store.playbackDetail(a, now + 160000)).interventions[0].comparison.status, "comparable");
    const event = { incidentId: crypto.randomUUID(), sessionId: sid, contentKey: otherContent, trigger: "BUFFERING", triggeredAtMs: now, complete: false, points: [{ offsetMs: 0, state: "BUFFERING", stateDurationMs: 1000, sessionDurationMs: 300000, rebufferCount: 1, rebufferDurationMs: 1000, framesKnown: false }] };
    const initial = { ...make(now, 0, { content_key: otherContent }), incidents: [event] }; await store.playback(d, initial, now);
    const completed = { ...event, complete: true, points: [...event.points, { ...event.points[0], offsetMs: 1000, sessionDurationMs: 301000 }] };
    await store.playback(d, { ...make(now + 1000, 0, { content_key: otherContent }), incidents: [completed] }, now + 1000);
    assert.equal((await store.playbackDetail(d, now + 2000)).incidents[0].complete, true);
    await store.playback(d, { ...make(now + 2000, 0, { content_key: otherContent }), incidents: [event] }, now + 2000);
    details = await store.playbackDetail(d, now + 2000); assert.equal(details.incidents[0].points.length, 2); assert.equal(details.incidents[0].complete, true);
    assert.equal((await store.playbackDetail(a, now + 2000)).incidents.length, 0);
    // Trigger and immediate pause/end capture may have distinct IDs but the same epoch ms.
    const immediate = { ...event, incidentId: crypto.randomUUID(), triggeredAtMs: now + 2001 };
    await store.playback(d, { ...make(now + 2001), incidents: [immediate] }, now + 2001);
    const flush = { ...make(now + 2001), incidents: [{ ...immediate, complete: true, truncated: true }] };
    await store.playback(d, flush, now + 2002);
    await store.playback(d, flush, now + 2003);
    const immediateResult = (await store.playbackDetail(d, now + 2003)).incidents.find(item => item.incidentId === immediate.incidentId);
    assert.equal(immediateResult.complete, true); assert.equal(immediateResult.truncated, true); assert.equal(immediateResult.points.length, 1);
    for (let i = 0; i < 2; i++) await store.playback(d, { ...make(now + 3000 + i, 0, { content_key: otherContent }), incidents: [{ ...event, incidentId: crypto.randomUUID() }] }, now + 3000 + i);
    assert.equal((await store.playbackDetail(d, now + 4000)).incidents.length, 2);
    assert.equal((await store.playbackDetail(d, now + 4000)).historyLimited, true);
    // The fleet-wide incident ring prunes the oldest rows at the usage check instead of refusing uploads.
    for (let i = 0; i < 2; i++) await store.playback(a, { ...make(now + 5000 + i, 0), incidents: [{ ...event, incidentId: crypto.randomUUID() }] }, now + 5000 + i);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_incidents")).rows[0].n, 3);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_incidents WHERE installation_id=$1", [d])).rows[0].n, 1);
    const markerD = await store.createIntervention(d, { ...marker, requestId: crypto.randomUUID(), contentKey: otherContent }, "operator", now + 4000);
    assert.ok(markerD);
    // Additional tables cascade on a legacy installation cleanup; they cannot block rollback.
    for (const table of ["support_playback_samples", "support_playback_receipts", "support_playback_devices"]) await pool.query(`DELETE FROM ${schema}.${table} WHERE installation_id=$1`, [d]);
    await pool.query(`DELETE FROM ${schema}.support_installations WHERE id=$1`, [d]);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_incidents WHERE installation_id=$1", [d])).rows[0].n, 0);
    assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_interventions WHERE installation_id=$1", [d])).rows[0].n, 0);
    await pool.query(`UPDATE ${schema}.support_playback_interventions SET created_at=now()-interval '8 days' WHERE installation_id=ANY($1::uuid[])`, [[a, b, c, d]]);
    await store.maintain(); assert.equal((await pool.query("SELECT count(*)::int n FROM support_playback_interventions")).rows[0].n, 0);
  } finally {
    await pool.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end();
  }
});
