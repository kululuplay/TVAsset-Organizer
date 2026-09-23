"use strict";
// Run after testDebugUnitTest; validates actual Kotlin-produced envelopes, not copied fixtures.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const { playbackPayload, diagnose } = require("../support-desk/playback");
const file = process.argv[2] || "IptvPlayer/app/build/reports/support-playback-contract.json";
const cases = JSON.parse(fs.readFileSync(file, "utf8"));
assert.ok(cases.length >= 7, "Android contract scenarios were not generated");
for (const scenario of cases) {
  const payload = playbackPayload(scenario.payload);
  assert.ok(payload, `Support server rejected Android payload: ${scenario.name}`);
  assert.equal(diagnose(payload.device, payload.sessions).status, scenario.expected, scenario.name);
  if (scenario.name === "recent rendered frames") {
    const session = payload.sessions[0];
    assert.equal(session.video_codec, "H264");
    assert.equal(session.video_decoder, "HARDWARE");
    assert.equal(session.video_width, 1920);
    assert.equal(session.video_height, 1080);
    assert.ok(Math.abs(session.frame_rate - 29.97) < .001);
    assert.equal(session.source_open_ms, 100);
    assert.equal(session.time_to_first_byte_ms, 200);
    assert.equal(session.first_byte_to_first_frame_ms, 19800);
    assert.equal(session.source_timing_scope, "INITIAL_ATTEMPT");
    assert.equal(session.paused_duration_ms, 0);
  }
}
console.log(`PASS: ${cases.length} Android-to-support playback contract scenarios`);
const { mergeIncident } = require("../support-desk/playback-analysis");
const incidentsFile = require("node:path").join(require("node:path").dirname(file), "support-incident-contract.json");
const incidentCases = JSON.parse(fs.readFileSync(incidentsFile, "utf8"));
assert.equal(incidentCases.length, 2, "Android incident windows were not generated");
const incidentPayloads = incidentCases.map(scenario => {
  const payload = playbackPayload(scenario.payload);
  assert.ok(payload, `Support server rejected Android incident: ${scenario.name}`);
  assert.equal(payload.incidents.length, 1);
  assert.equal(payload.sessions[0].content_key, payload.incidents[0].contentKey);
  return payload;
});
const [initial, complete] = incidentPayloads;
assert.equal(initial.incidents[0].complete, false);
assert.equal(complete.incidents[0].complete, true);
assert.equal(initial.incidents[0].incidentId, complete.incidents[0].incidentId);
assert.ok(complete.incidents[0].points.some(point => point.offsetMs < 0));
assert.ok(complete.incidents[0].points.some(point => point.offsetMs > 0));
assert.deepEqual(mergeIncident(initial.incidents[0], complete.incidents[0], initial.sampledAtMs, complete.sampledAtMs), complete.incidents[0]);
console.log(`PASS: ${incidentCases.length} Android-to-support incident windows and immutable completion`);
