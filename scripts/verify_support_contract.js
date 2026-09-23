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
  }
}
console.log(`PASS: ${cases.length} Android-to-support playback contract scenarios`);
