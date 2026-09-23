"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { incidentPayload, mergeIncident, interventionPayload, compareChannelGroup, windowMetrics, compareIntervention, measurementPoints } = require("./playback-analysis");
const { playbackPayload } = require("./playback");
const { ticketPayload } = require("./security");
const now = 1800000000000, key = "a".repeat(64), id = crypto.randomUUID();
const point = (offsetMs = 0) => ({ offsetMs, state: "BUFFERING", stateDurationMs: 1000, sessionDurationMs: 30000 + offsetMs, rebufferCount: 1, rebufferDurationMs: 1000, framesKnown: false });
const incident = () => ({ incidentId: crypto.randomUUID(), sessionId: id, contentKey: key, trigger: "BUFFERING", triggeredAtMs: now, complete: false, points: [point(-1000), point()] });

test("incident envelope is bounded, closed and contains only scalar measurement points", () => {
  const good = incident(); assert.deepEqual(incidentPayload(good, now), good);
  for (const change of [row => row.message = "private", row => row.points[0].url = "https://private", row => row.points.push(point()), row => row.points = Array.from({ length: 32 }, (_, index) => point(index)), row => row.points[0].offsetMs = -30001, row => row.trigger = "CUSTOM", row => row.contentKey = "streamurl", row => row.points[0].sessionDurationMs = -1, row => row.points[1].sessionDurationMs = 1, row => row.complete = 1, row => row.points = []]) {
    const bad = incident(); change(bad); assert.equal(incidentPayload(bad, now), null);
  }
  assert.ok(incidentPayload({ ...good, truncated: true }, now));
});
test("partial incident completion merges advancing buckets without rewinding retained evidence", () => {
  const first = incident(); const previous = { ...first, points: first.points.map(p => Object.fromEntries(Object.entries(p).reverse())) };
  const completed = { ...first, complete: true, points: [...first.points, point(1000)] };
  assert.deepEqual(mergeIncident(previous, completed, now, now + 1000), completed);
  assert.equal(mergeIncident(completed, first, now + 1000, now + 2000), completed);
  assert.equal(mergeIncident(previous, completed, now, now - 1), previous);
  assert.equal(mergeIncident(previous, { ...completed, sessionId: crypto.randomUUID() }, now, now + 1000), previous);
  const changed = structuredClone(completed); changed.points[0].rebufferCount--;
  assert.equal(mergeIncident(previous, changed, now, now + 1000), previous);
  const advance = { ...first, complete: true, points: [point(-1000), point(1000)] };
  assert.deepEqual(mergeIncident(previous, advance, now, now + 1000).points.map(p => p.offsetMs), [-1000, 0, 1000]);
  const many = { ...first, points: Array.from({ length: 31 }, (_, i) => point(i * 500)) };
  const shifted = { ...many, complete: true, points: Array.from({ length: 31 }, (_, i) => point(i * 500 + 1)) };
  const limited = mergeIncident(many, shifted, now, now + 20000);
  assert.ok(limited.points.length <= 31); assert.equal(limited.truncated, true); assert.equal(limited.points[0].offsetMs, 0); assert.equal(limited.points.at(-1).offsetMs, 15001);
});
test("optional incident/startup fields keep old envelopes valid and reject undocumented fields", () => {
  const base = { schema: 1, sampleId: crypto.randomUUID(), sampledAtMs: now, device: {}, sessions: [{ schema: 1, session_id: id, content_kind: "LIVE_TV", state: "STARTING", state_duration_ms: 1, session_duration_ms: 1, final: false, frames_known: false }] };
  assert.ok(playbackPayload(base, now));
  const enhanced = structuredClone(base); enhanced.incidents = [incident()]; Object.assign(enhanced.sessions[0], { source_timing_scope: "INITIAL_ATTEMPT", source_open_ms: 120, time_to_first_byte_ms: 500, first_byte_to_first_frame_ms: 1200, paused_duration_ms: 0 });
  assert.deepEqual(playbackPayload(enhanced, now), enhanced);
  const tooMany = { ...enhanced, incidents: [incident(), incident()] }; assert.equal(playbackPayload(tooMany, now), null);
  for (const fields of [{ source_open_ms: 1 }, { source_timing_scope: "RETRY", source_open_ms: 1 }, { source_timing_scope: "INITIAL_ATTEMPT", source_open_ms: 86400001 }, { source_timing_scope: "INITIAL_ATTEMPT", time_to_first_byte_ms: -1 }]) {
    assert.equal(playbackPayload({ ...base, sessions: [{ ...base.sessions[0], ...fields }] }, now), null);
  }
});
test("same-millisecond terminal flush completes a partial window without reopening or rewinding it", () => {
  const partial = incident();
  const completed = { ...partial, complete: true, truncated: true };
  assert.deepEqual(mergeIncident(partial, completed, now, now), completed);
  assert.equal(mergeIncident(partial, completed, now, now - 1), partial);
  assert.equal(mergeIncident(partial, { ...partial, points: [...partial.points, point(1000)] }, now, now), partial);
  assert.equal(mergeIncident(completed, partial, now, now), completed);
  assert.equal(mergeIncident(completed, { ...completed, points: [...completed.points, point(1000)] }, now, now + 1), completed);
  assert.equal(mergeIncident(partial, { ...completed, contentKey: "b".repeat(64) }, now, now), partial);
  const regressed = structuredClone(completed); regressed.points[0].rebufferCount = 0;
  assert.equal(mergeIncident(partial, regressed, now, now), partial);
});
test("support ticket evidence identifiers are allowlisted and do not accept stream URLs", () => {
  const context = { playback_session_id: id, playback_incident_id: crypto.randomUUID(), content_key: key };
  const ticket = { requestId: crypto.randomUUID(), type: "complaint", message: "Donuyor", metadata: { ...context, stream_url: "private", username: "private" } };
  assert.deepEqual(ticketPayload(ticket).metadata, context);
  assert.deepEqual(ticketPayload({ ...ticket, metadata: { playback_session_id: "private", content_key: "https://private" } }).metadata, {});
});
test("intervention markers validate IDs, reject additional commands and redact credentials", () => {
  const valid = { requestId: id, contentKey: key, note: "Ethernet denendi token=secret" };
  assert.doesNotMatch(interventionPayload(valid).note, /secret/);
  for (const invalid of [{ ...valid, command: "restart" }, { ...valid, note: " " }, { ...valid, note: "x".repeat(301) }, { ...valid, contentKey: "channel" }]) assert.equal(interventionPayload(invalid), null);
});
test("cross-device observations do not assign a certain panel/device cause", () => {
  for (const [group, status] of [
    [{ devices: 3, problemDevices: 2, measuredDevices: 1, unknownDevices: 0 }, "shared_problem"],
    [{ devices: 2, problemDevices: 1, measuredDevices: 1, unknownDevices: 0 }, "mixed"],
    [{ devices: 2, problemDevices: 0, measuredDevices: 2, unknownDevices: 0 }, "no_problem_measured"],
    [{ devices: 2, problemDevices: 1, measuredDevices: 0, unknownDevices: 1 }, "insufficient"],
  ]) { const result = compareChannelGroup(group); assert.equal(result.status, status); assert.notEqual(result.confidence, "high"); }
});

function measure(atMs, index, extra = {}) {
  return { atMs, receivedAtMs: atMs + 500, session: { session_id: id, content_key: key, session_duration_ms: 300000 + atMs - now, state: "PLAYING", final: false, paused_duration_ms: 0, rebuffer_count: index, rebuffer_duration_ms: index * 5000, frames_known: true, rendered_frames: index * 1000, dropped_frames: index * 10, ...extra } };
}
const windows = () => ({ before: [0, 1, 2, 3].map(i => measure(now - 120000 + i * 30000, i)), after: [0, 1, 2, 3].map(i => measure(now + i * 30000, 4 + i, { rebuffer_count: 3, rebuffer_duration_ms: 15000 })) });
test("intervention comparison uses normalized deltas instead of repeated cumulative sums", () => {
  const { before, after } = windows(); const result = compareIntervention(before, after, now, now + 150000);
  assert.equal(result.status, "comparable"); assert.equal(result.before.rebufferCount, 3); assert.equal(result.after.rebufferCount, 0);
  assert.equal(result.before.observedMs, 90000); assert.equal(result.change.bufferingPercentagePoints, -16.67);
  assert.equal(result.before.renderedFrames, 3000); assert.equal(result.before.droppedFrames, 30);
  assert.match(result.evidence.join(" "), /tek başına neden olduğunu/);
  assert.equal(compareIntervention(before, after, now, now + 1000).status, "waiting");
});
test("hidden pause counters, missing legacy counters and resets cannot imply improvement", () => {
  const { before, after } = windows();
  for (const transform of [
    (row, index) => ({ ...row, session: { ...row.session, paused_duration_ms: index * 1000 } }),
    row => ({ ...row, session: { ...row.session, paused_duration_ms: undefined } }),
    (row, index) => ({ ...row, session: { ...row.session, rebuffer_count: 10 - index } }),
    (row, index) => ({ ...row, session: { ...row.session, session_id: index % 2 ? crypto.randomUUID() : id } }),
    row => ({ ...row, receivedAtMs: row.atMs + 10000 }),
    row => ({ ...row, session: { ...row.session, state: "PAUSED" } }),
  ]) assert.equal(compareIntervention(before, after.map(transform), now, now + 150000).status, "insufficient");
});
test("content change, timestamp duplication, gaps and unknown frames remain conservative", () => {
  const { before, after } = windows();
  const mismatched = compareIntervention(before, after.map(row => ({ ...row, session: { ...row.session, content_key: "b".repeat(64) } })), now, now + 150000);
  assert.equal(mismatched.status, "insufficient"); assert.match(mismatched.evidence.at(-1), /kimlikleri eşleşmediği/);
  // No measurement at all is reported as absence, not as a content mismatch.
  const empty = compareIntervention([], [], now, now + 150000);
  assert.equal(empty.status, "insufficient"); assert.match(empty.evidence.at(-1), /kayıt bulunmadığından/); assert.doesNotMatch(empty.evidence.join(" "), /kimlikleri eşleşmediği/);
  assert.equal(windowMetrics([after[0], after[0]], now, now + 120000).observedMs, 0);
  assert.equal(windowMetrics([after[0], after[3]], now, now + 120000).observedMs, 0);
  const unknown = after.map(row => ({ ...row, session: { ...row.session, frames_known: false } }));
  const result = compareIntervention(before, unknown, now, now + 150000);
  assert.equal(result.status, "comparable"); assert.equal("droppedPercent" in result.after, false); assert.equal("droppedPercentagePoints" in result.change, false);
});
test("measurement point selection never mixes content and selects at most one session per envelope", () => {
  const session = measure(now, 1).session;
  const rows = [{ sampled_at_ms: now, received_at: new Date(now), payload: { sessions: [session, { ...session, content_key: "b".repeat(64) }, { ...session, started_at_epoch_ms: now + 1 }] } }];
  const result = measurementPoints(rows, key); assert.equal(result.length, 1); assert.equal(result[0].session.started_at_epoch_ms, now + 1);
});
