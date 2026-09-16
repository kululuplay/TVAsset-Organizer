"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {
  BoundedMap,
  createRateLimiter,
  createCsrf,
  sameOrigin,
  normalizeIp,
  maskAccount,
  parseTrustProxyHops,
  timingSafeEqualStr,
} = require("./guard");

test("BoundedMap evicts the oldest entry past its cap and sweeps by predicate", () => {
  const m = new BoundedMap(3);
  m.set("a", 1).set("b", 2).set("c", 3);
  m.set("d", 4);
  assert.deepEqual([...m.keys()], ["b", "c", "d"]);
  // Re-setting an existing key refreshes its position instead of growing.
  m.set("b", 20);
  m.set("e", 5);
  assert.deepEqual([...m.keys()], ["d", "b", "e"]);
  assert.equal(m.sweep((v) => v >= 5), 2);
  assert.deepEqual([...m.entries()], [["d", 4]]);
});

test("rate limiter grants the budget, then 429s with a Retry-After, then refills", () => {
  let t = 0;
  const lim = createRateLimiter({ max: 3, windowMs: 3000, maxKeys: 2, now: () => t });
  assert.equal(lim.check("dev-1").ok, true);
  assert.equal(lim.check("dev-1").ok, true);
  assert.equal(lim.check("dev-1").ok, true);
  const denied = lim.check("dev-1");
  assert.equal(denied.ok, false);
  assert.equal(denied.retryAfterSec, 1); // one token per second at 3/3s
  t = 1000;
  assert.equal(lim.check("dev-1").ok, true);
  assert.equal(lim.check("dev-1").ok, false);
  // Keys are independent and the key map is bounded (oldest evicted).
  assert.equal(lim.check("dev-2").ok, true);
  assert.equal(lim.check("dev-3").ok, true);
  assert.equal(lim.buckets.has("dev-1"), false);
  assert.equal(lim.buckets.size, 2);
  // Fully refilled buckets are swept.
  t = 10_000;
  assert.equal(lim.sweep(), 2);
  assert.equal(lim.buckets.size, 0);
});

test("CSRF token is bound to the credential + nonce and verified in constant time", () => {
  const nonce = Buffer.alloc(32, 7);
  const a = createCsrf(Buffer.from("cred-a"), nonce);
  const b = createCsrf(Buffer.from("cred-b"), nonce);
  const a2 = createCsrf(Buffer.from("cred-a"), Buffer.alloc(32, 8));
  assert.match(a.token, /^[0-9a-f]{64}$/);
  assert.notEqual(a.token, b.token);
  assert.notEqual(a.token, a2.token);
  assert.equal(a.verify(a.token), true);
  assert.equal(a.verify(b.token), false);
  assert.equal(a.verify(a.token.slice(0, 63)), false);
  assert.equal(a.verify(undefined), false);
  assert.equal(a.verify(["x"]), false);
  assert.ok(a.field.includes(`name="_csrf" value="${a.token}"`));
  assert.equal(timingSafeEqualStr("abc", "abc"), true);
  assert.equal(timingSafeEqualStr("abc", "abcd"), false);
});

test("sameOrigin accepts a matching Origin/Referer and rejects cross-site or missing", () => {
  const h = (map) => (name) => map[name.toLowerCase()];
  const self = "panel.example.test";
  assert.equal(sameOrigin({ header: h({ origin: "https://panel.example.test" }), hostname: self }), null);
  assert.equal(sameOrigin({ header: h({ origin: "https://Panel.Example.Test:443" }), hostname: self }), null);
  assert.equal(sameOrigin({ header: h({ referer: "https://panel.example.test/device/x" }), hostname: self }), null);
  assert.equal(sameOrigin({ header: h({}), hostname: self }), "missing_origin");
  assert.equal(sameOrigin({ header: h({ origin: "null" }), hostname: self }), "missing_origin");
  assert.equal(sameOrigin({ header: h({ origin: "https://evil.test" }), hostname: self }), "origin_mismatch");
  assert.equal(sameOrigin({ header: h({ origin: "not a url" }), hostname: self }), "origin_mismatch");
  assert.equal(
    sameOrigin({
      header: h({ origin: "https://panel.example.test", "sec-fetch-site": "cross-site" }),
      hostname: self,
    }),
    "cross_site",
  );
  assert.equal(
    sameOrigin({ header: h({ origin: "https://panel.example.test", "sec-fetch-site": "same-origin" }), hostname: self }),
    null,
  );
});

test("normalizeIp accepts only real addresses and strips the v4-mapped prefix", () => {
  assert.equal(normalizeIp("::ffff:203.0.113.9"), "203.0.113.9");
  assert.equal(normalizeIp("203.0.113.9"), "203.0.113.9");
  assert.equal(normalizeIp("2001:db8::1"), "2001:db8::1");
  assert.equal(normalizeIp("fe80::1%eth0"), "fe80::1");
  assert.equal(normalizeIp("203.0.113.9, 10.0.0.1"), null);
  assert.equal(normalizeIp("<script>"), null);
  assert.equal(normalizeIp(""), null);
  assert.equal(normalizeIp(undefined), null);
});

test("maskAccount and parseTrustProxyHops", () => {
  assert.equal(maskAccount("kululu_user"), "ku***");
  assert.equal(maskAccount("a"), "a***");
  assert.equal(maskAccount(""), "***");
  assert.equal(maskAccount(null), "***");
  assert.equal(parseTrustProxyHops(undefined), 1);
  assert.equal(parseTrustProxyHops("2"), 2);
  assert.equal(parseTrustProxyHops("0"), 0);
  assert.equal(parseTrustProxyHops("-1"), 1);
  assert.equal(parseTrustProxyHops("nope"), 1);
});
