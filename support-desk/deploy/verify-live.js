"use strict";
// VPS-only integration checks. Secrets stay in memory; synthetic rows are removed by exact UUID.
const fs = require("node:fs");
const crypto = require("node:crypto");
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const { Pool } = require("pg");
async function main() {
  const config = Object.fromEntries(fs.readFileSync("/etc/kululu-support/service.env", "utf8").trim().split("\n").map(line => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]));
  execFileSync(process.execPath, ["--test", "support-desk/store.test.js"], { cwd: "/opt/kululu-support", env: { ...process.env, SUPPORT_TEST_DATABASE_URL: config.DATABASE_URL }, stdio: "inherit" });
  const origin = config.SUPPORT_ORIGIN;
  const credentials = fs.readFileSync("/etc/kululu-support/admin-access.txt", "utf8");
  const password = /^Parola: (.+)$/m.exec(credentials)[1];
  const installationId = crypto.randomUUID(), secret = crypto.randomBytes(32).toString("base64url");
  const pool = new Pool({ connectionString: config.DATABASE_URL });
  const call = async (path, body, headers = {}, method = body == null ? "GET" : "POST") => {
    const response = await fetch(origin + path, { method, signal: AbortSignal.timeout(15000), headers: { ...(body == null ? {} : { "Content-Type": "application/json" }), ...headers }, body: body == null ? undefined : JSON.stringify(body) });
    return { status: response.status, headers: response.headers, data: await response.json() };
  };
  try {
    assert.equal((await call("/healthz")).status, 200);
    assert.equal((await call("/api/admin/tickets")).status, 401);
    assert.equal((await call("/api/v1/installations", { installationId, secret })).status, 200);
    const auth = { Authorization: `Bearer ${installationId}.${secret}` };
    const body = { requestId: crypto.randomUUID(), type: "diagnostic", message: "Deployment self-test — synthetic", log: 'https://test.invalid/live/user/password/1.ts\nAuthorization: Bearer TESTSECRET', metadata: { model: "Deployment self-test", appVersion: "test" } };
    const report = await call("/api/v1/tickets", body, auth); assert.equal(report.status, 201); assert.match(report.data.code, /^K-[A-F0-9]{16}$/);
    const again = await call("/api/v1/tickets", body, auth); assert.equal(again.data.id, report.data.id);
    const request = await call("/api/v1/tickets", { requestId: crypto.randomUUID(), type: "channel", message: "Deployment self-test — request" }, auth); assert.equal(request.status, 201);
    const login = await call("/api/admin/login", { username: config.SUPPORT_ADMIN_USER, password }, { Origin: origin }); assert.equal(login.status, 200);
    const cookie = login.headers.get("set-cookie"); assert.match(cookie, /HttpOnly/); assert.match(cookie, /Secure/); assert.match(cookie, /SameSite=Strict/);
    const admin = { Origin: origin, Cookie: cookie.split(";")[0], "X-CSRF-Token": login.data.csrf };
    const detail = await call("/api/admin/tickets/" + report.data.id, null, admin); assert.equal(detail.status, 200); assert.doesNotMatch(detail.data.log, /\/user\/password\/|TESTSECRET/);
    assert.equal((await call("/api/admin/tickets/" + request.data.id, { status: "done" }, { ...admin, "X-CSRF-Token": "wrong" }, "PATCH")).status, 403);
    assert.equal((await call("/api/admin/tickets/" + request.data.id, { status: "done" }, admin, "PATCH")).status, 200);
    const history = await call("/api/v1/tickets", null, auth); assert.equal(history.data.requests[0].status, "done");
    assert.equal((await call("/api/admin/logout", {}, admin)).status, 200);
    assert.equal((await call("/api/admin/tickets", null, admin)).status, 401);
    console.log("PASS: HTTPS, registration, report receipt, idempotency, redaction, admin auth/CSRF, request status/history and logout.");
  } finally {
    await pool.query("DELETE FROM support_audit WHERE ticket_id IN (SELECT id FROM support_tickets WHERE installation_id=$1)", [installationId]);
    await pool.query("DELETE FROM support_tickets WHERE installation_id=$1", [installationId]);
    await pool.query("DELETE FROM support_installations WHERE id=$1", [installationId]);
    await pool.end(); console.log("Synthetic support records removed; no customer records changed.");
  }
}
main().catch(error => { console.error("Support verification failed:", error.code || error.name, error.message); process.exit(1); });
