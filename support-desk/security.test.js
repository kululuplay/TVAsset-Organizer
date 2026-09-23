"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { RateLimit } = require("./security");

test("a full limiter drops expired rows before live ones and never refuses a new key", () => {
  let now = 0; const rate = new RateLimit(3, () => now);
  assert.equal(rate.take("stale", 5, 100), true);
  now = 50; assert.equal(rate.take("long", 5, 1000), true); assert.equal(rate.take("short", 5, 200), true);
  now = 120; // "stale" has expired; the table is full and a new device must still be admitted.
  assert.equal(rate.take("new", 5, 100), true);
  assert.deepEqual([...rate.rows.keys()], ["long", "short", "new"]);
});
test("a full limiter with only live rows evicts the earliest-expiring key and keeps other counts", () => {
  let now = 0; const rate = new RateLimit(2, () => now);
  for (let i = 0; i < 3; i++) assert.equal(rate.take("hot", 3, 1000), true);
  assert.equal(rate.take("hot", 3, 1000), false);
  assert.equal(rate.take("soon", 3, 100), true);
  // "soon" expires first, so it leaves; the exhausted "hot" quota survives the eviction untouched.
  assert.equal(rate.take("new", 3, 500), true);
  assert.deepEqual([...rate.rows.keys()], ["hot", "new"]);
  assert.equal(rate.take("hot", 3, 1000), false);
  now = 1000; assert.equal(rate.take("hot", 3, 1000), true);
});
test("an expired key renews its window in place without an eviction scan", () => {
  let now = 0; const rate = new RateLimit(1, () => now);
  assert.equal(rate.take("one", 1, 100), true); assert.equal(rate.take("one", 1, 100), false);
  now = 100; assert.equal(rate.take("one", 1, 100), true); assert.equal(rate.take("one", 1, 100), false); assert.equal(rate.rows.size, 1);
});
