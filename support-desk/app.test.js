"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { createApp } = require("./app");
const { passwordHash, hash, equal, redact, ticketPayload, RateLimit } = require("./security");
const ORIGIN = "https://212.95.41.130:8443";
const uid = () => crypto.randomUUID();
class TestStore {
  constructor() { this.devices = new Map(); this.tickets = []; }
  async healthy() {}
  async register(id, secret) { if (!this.devices.has(id)) this.devices.set(id, secret); return equal(secret, this.devices.get(id)); }
  async authenticate(id, secret) { return this.devices.has(id) && equal(secret, this.devices.get(id)); }
  async create(installationId, ticket) { let item = this.tickets.find(row => row.installationId === installationId && row.requestId === ticket.requestId); if (!item) { item = { ...ticket, installationId, id: this.tickets.length + 1, code: "K-0123456789ABCDEF", status: "new" }; this.tickets.push(item); } return item; }
  async mine(id) { return this.tickets.filter(row => row.installationId === id && row.type !== "diagnostic"); }
  async ack(id, ids) { this.tickets.filter(row => row.installationId === id && ids.includes(row.id)).forEach(row => row.notified = true); }
  async list(filter) { return { items: this.tickets.filter(row => filter.scope !== "requests" || row.type !== "diagnostic"), nextCursor: null }; }
  async detail(id) { return this.tickets.find(row => String(row.id) === String(id)); }
  async status(id, status) { const row = await this.detail(id); if (!row) return false; row.status = status; return true; }
  async stats() { return { total: this.tickets.length }; }
}
async function fixture(t) {
  const store = new TestStore();
  const app = createApp({ store, origin: ORIGIN, adminUser: "admin", adminPasswordHash: await passwordHash("test-only-password"), secureCookies: false });
  const server = app.listen(0, "127.0.0.1"); await new Promise(resolve => server.once("listening", resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const url = `http://127.0.0.1:${server.address().port}`;
  const call = async (path, body, headers = {}, method = body === undefined ? "GET" : "POST") => {
    const response = await fetch(url + path, { method, headers: { ...(body !== undefined ? { "Content-Type": "application/json" } : {}), ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, headers: response.headers, data: await response.json() };
  };
  const install = async () => { const id = uid(), secret = crypto.randomBytes(32).toString("base64url"); assert.equal((await call("/api/v1/installations", { installationId: id, secret })).status, 200); return { Authorization: `Bearer ${id}.${secret}` }; };
  const login = async () => { const res = await call("/api/admin/login", { username: "admin", password: "test-only-password" }, { Origin: ORIGIN }); assert.equal(res.status, 200); return { Cookie: res.headers.get("set-cookie").split(";")[0], Origin: ORIGIN, "X-CSRF-Token": res.data.csrf }; };
  return { store, call, install, login, url };
}
test("admin data and mutations require a session", async t => { const f = await fixture(t); for (const path of ["session", "tickets", "stats", "tickets/1"]) assert.equal((await f.call("/api/admin/" + path)).status, 401); });
test("login rejects cross origin and invalid credentials", async t => { const f = await fixture(t); assert.equal((await f.call("/api/admin/login", { username: "admin", password: "test-only-password" })).status, 403); assert.equal((await f.call("/api/admin/login", { username: "admin", password: "wrong" }, { Origin: ORIGIN })).status, 401); });
test("session cookie is HttpOnly SameSite and logout revokes access", async t => { const f = await fixture(t); const auth = await f.login(); assert.equal((await f.call("/api/admin/session", undefined, auth)).status, 200); assert.equal((await f.call("/api/admin/logout", {}, { ...auth, "X-CSRF-Token": "wrong" })).status, 403); assert.equal((await f.call("/api/admin/logout", {}, auth)).status, 200); assert.equal((await f.call("/api/admin/session", undefined, auth)).status, 401); });
test("install rejects arrays and identity takeover", async t => { const f = await fixture(t); const id = uid(), secret = crypto.randomBytes(32).toString("base64url"); assert.equal((await f.call("/api/v1/installations", { installationId: [id], secret })).status, 400); assert.equal((await f.call("/api/v1/installations", { installationId: id, secret })).status, 200); assert.equal((await f.call("/api/v1/installations", { installationId: id, secret: "b".repeat(43) })).status, 409); });
test("unauthenticated device cannot read or submit tickets", async t => { const f = await fixture(t); assert.equal((await f.call("/api/v1/tickets")).status, 401); assert.equal((await f.call("/api/v1/tickets", { requestId: uid(), type: "movie", message: "Film" })).status, 401); });
test("idempotent retries and installation ownership", async t => { const f = await fixture(t), a = await f.install(), b = await f.install(); const body = { requestId: uid(), type: "movie", message: "Film isteği" }; const one = await f.call("/api/v1/tickets", body, a), two = await f.call("/api/v1/tickets", body, a); assert.equal(one.data.id, two.data.id); assert.equal(f.store.tickets.length, 1); assert.equal((await f.call("/api/v1/tickets", undefined, b)).data.requests.length, 0); await f.call("/api/v1/tickets/ack", { ids: [one.data.id] }, b); assert.equal(f.store.tickets[0].notified, undefined); });
test("admin status change requires origin and CSRF", async t => { const f = await fixture(t); const device = await f.install(), auth = await f.login(); await f.call("/api/v1/tickets", { requestId: uid(), type: "movie", message: "Test" }, device); assert.equal((await f.call("/api/admin/tickets/1", { status: "done" }, { ...auth, "X-CSRF-Token": "wrong" }, "PATCH")).status, 403); assert.equal((await f.call("/api/admin/tickets/1", { status: "done" }, auth, "PATCH")).status, 200); assert.equal((await f.call("/api/v1/tickets", undefined, device)).data.requests[0].status, "done"); });
test("payload invalid IDs, oversized logs and forbidden request logs are rejected", async t => { const f = await fixture(t), auth = await f.install(); for (const body of [{ requestId: [uid()], type: "movie", message: "x" }, { requestId: uid(), type: "movie", message: "x", log: "secret" }, { requestId: uid(), type: "diagnostic", message: "x", log: "x".repeat(131073) }]) assert.equal((await f.call("/api/v1/tickets", body, auth)).status, 400); });
test("JSON body cap returns 413, not success", async t => { const f = await fixture(t), auth = await f.install(); assert.equal((await f.call("/api/v1/tickets", { x: "x".repeat(200000) }, auth)).status, 413); });
test("requests scope excludes diagnostics on server", async t => { const f = await fixture(t), device = await f.install(), admin = await f.login(); for (const type of ["movie", "diagnostic"]) await f.call("/api/v1/tickets", { requestId: uid(), type, message: "Test" }, device); const result = await f.call("/api/admin/tickets?scope=requests", undefined, admin); assert.deepEqual(result.data.items.map(row => row.type), ["movie"]); });
test("persistent capacity error becomes safe 503", async t => { const f = await fixture(t), auth = await f.install(); f.store.create = async () => { throw Object.assign(new Error("not public"), { code: "support_capacity" }); }; const r = await f.call("/api/v1/tickets", { requestId: uid(), type: "movie", message: "Test" }, auth); assert.deepEqual(r.data, { ok: false, error: "support_capacity" }); assert.equal(r.status, 503); });
test("health reflects database failure", async t => { const f = await fixture(t); f.store.healthy = async () => { throw Error("secret DB address"); }; assert.equal((await f.call("/healthz")).status, 503); });
test("redaction covers URLs JSON and prefixed authorization", () => { for (const input of ['https://a/live/USER/PASSWORD/1.ts', '{"secret":"PRIVATE"}', '{"Authorization":"Bearer PRIVATE"}', '12:34 OkHttp Authorization: Bearer PRIVATE', ' Cookie: PRIVATE', 'Bearer PRIVATE']) { const output = redact(input); assert.doesNotMatch(output, /PRIVATE|USER|PASSWORD/); assert.equal(redact(output), output); } });
test("metadata allowlist and strict UUID", () => { const body = { requestId: uid(), type: "diagnostic", message: "test", metadata: { model: "Fire TV", username: "private", password: "private", apiLevel: 25 } }; assert.deepEqual(ticketPayload(body).metadata, { model: "Fire TV", apiLevel: 25 }); assert.equal(ticketPayload({ ...body, requestId: [body.requestId] }), null); });
test("limiter has fixed quota, expiration and bounded keys", () => { let now = 0; const rate = new RateLimit(1, () => now); assert.equal(rate.take("one", 1, 100), true); assert.equal(rate.take("one", 1, 100), false); assert.equal(rate.take("two", 1, 100), false); now = 101; assert.equal(rate.take("two", 1, 100), true); assert.equal(rate.rows.size, 1); });
test("installation secrets are only stored hashed", async t => { const f = await fixture(t); const id = uid(), secret = "x".repeat(43); await f.call("/api/v1/installations", { installationId: id, secret }); assert.equal(f.store.devices.get(id), hash(secret)); });
