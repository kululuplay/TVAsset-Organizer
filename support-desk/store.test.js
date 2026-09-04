"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { Pool } = require("pg");
const { PgStore } = require("./store");
test("PostgreSQL ownership, atomic cap, retention, status audit and request pagination", { skip: !process.env.SUPPORT_TEST_DATABASE_URL }, async () => {
  const databaseUrl = new URL(process.env.SUPPORT_TEST_DATABASE_URL);
  assert.ok(["127.0.0.1", "localhost"].includes(databaseUrl.hostname));
  assert.equal(databaseUrl.pathname, "/kululu_support");
  const admin = new Pool({ connectionString: databaseUrl.toString() });
  const schema = "support_test_" + crypto.randomBytes(8).toString("hex");
  assert.match(schema, /^support_test_[a-f0-9]{16}$/);
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new Pool({ connectionString: databaseUrl.toString(), options: `-c search_path=${schema}` });
  try {
    await pool.query(fs.readFileSync(path.join(__dirname, "schema.sql"), "utf8"));
    const store = new PgStore(pool, { installations: 2, tickets: 3, logs: 1 });
    const a = crypto.randomUUID(), b = crypto.randomUUID();
    assert.equal(await store.register(a, "hash-a"), true); assert.equal(await store.register(a, "other"), false);
    assert.equal(await store.register(b, "hash-b"), true);
    await assert.rejects(store.register(crypto.randomUUID(), "hash-c"), { code: "support_capacity" });
    const payload = { requestId: crypto.randomUUID(), type: "diagnostic", message: "Test", log: "recent log", metadata: {} };
    const [one, retry] = await Promise.all([store.create(a, payload), store.create(a, payload)]); assert.equal(one.id, retry.id);
    await assert.rejects(store.create(a, { ...payload, requestId: crypto.randomUUID() }), { code: "support_capacity" });
    assert.equal((await store.mine(b)).length, 0);
    await store.create(a, { ...payload, requestId: crypto.randomUUID(), type: "movie", log: "" });
    assert.equal((await store.list({ scope: "requests" })).items.length, 1);
    assert.equal(await store.status(one.id, "done", "admin"), true);
    await store.status(one.id, "done", "admin");
    assert.equal((await pool.query("SELECT count(*)::int AS n FROM support_audit")).rows[0].n, 1);
    await pool.query("UPDATE support_tickets SET created_at=now()-interval '91 days' WHERE id=$1", [one.id]);
    await store.maintain(); assert.equal((await store.detail(one.id)).log, ""); assert.equal((await store.stats()).total, 2);
    const parallel = await Promise.allSettled([1, 2].map(() => store.create(a, { ...payload, requestId: crypto.randomUUID(), log: "" })));
    assert.equal(parallel.filter(result => result.status === "fulfilled").length, 1);
    assert.equal((await store.stats()).total, 3);
  } finally {
    await pool.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end();
  }
});
