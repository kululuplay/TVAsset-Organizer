"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

// This small DOM substitute verifies state/auth behavior and text-only rendering.
// It deliberately does not claim to verify browser layout, focus or accessibility.
class TestNode {
  constructor(tag = "div") {
    this.tag = tag; this.children = []; this.dataset = {}; this.classList = { toggle() {} };
    this.value = ""; this.hidden = false; this.open = false; this.attributes = {};
    this.listeners = {}; this._text = "";
  }
  set textContent(value) { this._text = String(value ?? ""); this.children = []; }
  get textContent() { return this._text + this.children.map(child => child.textContent ?? String(child)).join(""); }
  set innerHTML(_) { throw new Error("Untrusted diagnostics must use textContent, not innerHTML"); }
  insertAdjacentHTML() { throw new Error("Untrusted diagnostics must not be parsed as HTML"); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this._text = ""; this.children = children; }
  addEventListener(name, fn) { this.listeners[name] = fn; }
  setAttribute(key, value) { this.attributes[key] = value; }
  removeAttribute(key) { delete this.attributes[key]; }
  querySelectorAll() { return []; }
  focus() {}
  showModal() { this.open = true; }
  close() { this.open = false; this.listeners.close?.(); }
}

const source = fs.readFileSync(path.join(__dirname, "public", "desk.js"), "utf8");
function desk() {
  const nodes = new Map(), timers = new Map(); let timerId = 0, fetchCount = 0;
  const sandbox = {
    console, URLSearchParams, AbortController, Date, Intl, Map, Set,
    document: {
      hidden: false,
      getElementById(id) { if (!nodes.has(id)) nodes.set(id, new TestNode()); return nodes.get(id); },
      createElement: tag => new TestNode(tag), createTextNode: text => ({ textContent: String(text) }),
      querySelectorAll: () => [], addEventListener() {},
    },
    window: { addEventListener() {} }, navigator: {},
    setTimeout(fn, ms) { const id = ++timerId; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
    fetch: () => { fetchCount++; return new Promise(() => {}); },
  };
  vm.createContext(sandbox); vm.runInContext(source, sandbox);
  const run = code => vm.runInContext(code, sandbox);
  return {
    run, nodes, timers, fetchCount: () => fetchCount,
    respond(handler) { sandbox.fetch = (...args) => { fetchCount++; return handler(...args); }; },
    signIn() { run('csrf="test-session";activeView="playback"'); },
  };
}
const response = body => ({ ok: true, json: async () => body });
function device(overrides = {}) {
  const now = new Date().toISOString();
  return {
    installationId: "test-device", deviceCode: "test-device", lastSeenAt: now, sampledAt: now,
    info: { manufacturer: "Amazon", model: "AFTSS", appVersion: "1.5.90" },
    sessions: [], online: true, status: "healthy",
    diagnosis: { headline: "Son ölçümde sorun saptanmadı", evidence: [], recommendations: [] },
    ...overrides,
  };
}
const list = item => ({ items: [item], nextCursor: null, summary: { online: 1, problems: 0, devices: 1 }, serverTime: new Date().toISOString() });

test("health UI distinguishes missing measurements from zero and unknown decoder from preferences", () => {
  const { run } = desk();
  assert.equal(run("millis(undefined)"), "Ölçülmedi");
  assert.equal(run("millis(0)"), "0 sn");
  assert.equal(run("countValue(null)"), "Ölçülmedi");
  assert.equal(run("failureCount(null)"), null);
  assert.equal(run('failureCount({failure_codes:"",discarded_failure_count:0})'), 0);
  assert.equal(run('videoCodecValue({video_codec:"H265"})'), "H.265 / HEVC");
  assert.equal(run('videoDecoderValue({video_decoder:"UNKNOWN"})'), "Bilinmiyor");
  assert.equal(run("videoDecoderValue({})"), "Ölçülmedi");
  assert.equal(run("resolutionValue({video_width:1920,video_height:1080})"), "1920 × 1080");
  assert.equal(run("resolutionValue({video_width:1920})"), "Ölçülmedi");
  assert.equal(run("frameRateValue({frame_rate:29.97})"), "29,97 fps");
  assert.equal(run("frameRateValue({frame_rate:0})"), "Ölçülmedi");
});

test("health UI marks stale evidence unknown and retains failed final session state", () => {
  const { run } = desk();
  assert.equal(run('healthState({lastSeenAt:new Date(Date.now()-180000).toISOString(),online:true,status:"healthy"})'), "unknown");
  assert.equal(run('healthState({lastSeenAt:new Date().toISOString(),online:true,status:"healthy"},true)'), "unknown");
  assert.equal(run('healthState({lastSeenAt:new Date().toISOString(),online:true,status:"healthy"},false)'), "healthy");
  assert.equal(run("sessionOf({sessions:[{started_at_epoch_ms:1,final:false},{started_at_epoch_ms:2,final:true}]}).started_at_epoch_ms"), 2);
  assert.equal(run('sessionState({final:true,state:"FAILED"})'), "Son oturum · hata ile bitti");
  assert.equal(run('sessionState({state:"PLAYING"},true)'), "Son kayıtta oynatılıyordu");
});

test("health UI polls only with an authenticated visible health view", async () => {
  const env = desk(); const baseline = env.fetchCount();
  for (const state of ['csrf="";activeView="playback";document.hidden=false', 'csrf="test";activeView="all";document.hidden=false', 'csrf="test";activeView="playback";document.hidden=true']) {
    env.run(state); await env.run("refreshHealth()"); env.run("scheduleHealthPolling()");
    assert.equal(env.fetchCount(), baseline); assert.equal(env.timers.size, 0);
  }
  env.run('csrf="test";activeView="playback";document.hidden=false;scheduleHealthPolling()');
  assert.deepEqual([...env.timers.values()].map(timer => timer.ms), [30000]);
  env.run("showLogin()"); assert.equal(env.timers.size, 0);
});

test("health UI discards delayed list response after logout and aborts the request", async () => {
  const env = desk(); env.signIn(); let resolve, signal;
  env.respond((_, options) => { signal = options.signal; return new Promise(done => { resolve = done; }); });
  const pending = env.run("refreshHealth()"); env.run("showLogin()");
  assert.equal(signal.aborted, true);
  resolve(response(list(device()))); await pending;
  assert.equal(env.run("healthRows.length"), 0);
  assert.equal(env.run('$("health-list").children.length'), 0);
  assert.equal(env.run("csrf"), ""); assert.equal(env.timers.size, 0);
});

test("health UI discards delayed device dialog response after logout", async () => {
  const env = desk(); env.signIn(); let resolve, signal;
  env.respond((_, options) => { signal = options.signal; return new Promise(done => { resolve = done; }); });
  const pending = env.run('openHealthDevice("test-device")'); env.run("showLogin()");
  assert.equal(signal.aborted, true);
  resolve(response({ device: device(), timeline: [], serverTime: new Date().toISOString() })); await pending;
  assert.equal(env.run("healthDetailData"), null);
  assert.equal(env.run('$("health-detail").open'), false);
  assert.equal(env.run('$("health-detail-content").hidden'), true);
});

test("health UI preserves prior evidence but removes live claims after a refresh error", async () => {
  const env = desk(); env.signIn(); env.respond(async () => response(list(device())));
  await env.run("refreshHealth()"); assert.equal(env.run('$("health-online").textContent'), "1");
  env.respond(async () => ({ ok: false, status: 503, json: async () => ({}) }));
  await env.run("refreshHealth()");
  assert.equal(env.run("healthRows.length"), 1);
  assert.equal(env.run("healthState(healthRows[0])"), "unknown");
  assert.equal(env.run('$("health-online").textContent'), "—");
  assert.match(env.run('$("health-message").textContent'), /son alınan ölçümlerdir/);
  env.respond(async () => { throw new TypeError("Failed to fetch"); });
  await env.run("refreshHealth()");
  assert.match(env.run('$("health-message").textContent'), /^Ölçüm sunucusuna ulaşılamadı\./);
  assert.doesNotMatch(env.run('$("health-message").textContent'), /Failed to fetch/);
  await env.run('openHealthDevice("test-device")');
  assert.equal(env.run('$("health-detail-message").textContent'), "Ölçüm sunucusuna ulaşılamadı.");
});

test("health UI treats a timed out fetch as unknown instead of leaving live status indefinitely", async () => {
  const env = desk(); env.signIn(); env.respond(async () => response(list(device())));
  await env.run("refreshHealth()");
  env.respond((_, options) => new Promise((_, reject) => {
    options.signal.addEventListener("abort", () => { const error = new Error("aborted"); error.name = "AbortError"; reject(error); });
  }));
  const pending = env.run("refreshHealth()");
  [...env.timers.values()].find(timer => timer.ms === 15000).fn(); await pending;
  assert.equal(env.run("healthState(healthRows[0])"), "unknown");
  assert.match(env.run('$("health-message").textContent'), /zamanında yanıt vermedi/);
});

test("health UI renders device, channel and diagnostic labels as literal text", async () => {
  const env = desk(); env.signIn();
  const unsafe = '<img src=x onerror="throw 1">';
  const item = device({
    info: { model: unsafe },
    sessions: [{ content_label: unsafe, state: "PLAYING", final: false }],
    diagnosis: { headline: unsafe, evidence: [unsafe], recommendations: [unsafe] },
  });
  env.respond(async () => response(list(item))); await env.run("refreshHealth()");
  assert.ok(env.nodes.get("health-list").textContent.includes(unsafe));
  env.respond(async () => response({ device: item, timeline: [{ sampleId: "sample", ...item, receivedAt: new Date().toISOString() }], serverTime: new Date().toISOString() }));
  await env.run('openHealthDevice("test-device")');
  for (const id of ["health-detail-title", "health-session-metrics", "health-assessment", "health-evidence", "health-recommendations", "health-timeline"]) {
    assert.ok(env.nodes.get(id).textContent.includes(unsafe), `${id} must retain the label as plain text`);
  }
});
