"use strict";
const express = require("express");
const crypto = require("node:crypto");
const path = require("node:path");
const { UUID, SECRET, TYPES, STATUSES, hash, equal, ticketPayload, RateLimit, verifyPassword } = require("./security");

function createApp({ store, origin, adminUser, adminPasswordHash, secureCookies = true, clock = Date.now }) {
  if (!store || !origin || !adminUser || !/^scrypt\$/.test(adminPasswordHash || "")) throw new Error("Support configuration incomplete");
  const app = express();
  app.disable("x-powered-by");
  // Only the local Nginx proxy is trusted; the service binds loopback in production.
  app.set("trust proxy", "loopback");
  const limiter = new RateLimit(10000, clock);
  const sessions = new Map();
  const fail = (res, status, code) => res.status(status).json({ ok: false, error: code });
  const limit = (req, res, key, count, ms) => {
    if (limiter.take(key, count, ms)) return true;
    res.set("Retry-After", String(Math.ceil(ms / 1000))); fail(res, 429, "rate_limited"); return false;
  };
  app.use((req, res, next) => {
    res.set({ "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY",
      "Referrer-Policy": "no-referrer", "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
      "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'" });
    if (req.path.startsWith("/api/") && !limit(req, res, `http:${req.ip}`, 240, 60000)) return;
    next();
  });
  app.use(express.json({ limit: "192kb", strict: true }));
  app.get("/healthz", async (_req, res) => {
    try { await store.healthy(); res.json({ ok: true }); } catch { fail(res, 503, "unavailable"); }
  });
  const sameOrigin = (req, res, next) => {
    if (req.get("Origin") !== origin || !req.is("application/json")) return fail(res, 403, "origin_rejected");
    next();
  };
  const readSession = req => {
    const value = (req.get("Cookie") || "").split(";").map(x => x.trim()).find(x => x.startsWith("kululu_support="))?.slice(15);
    if (!value || !SECRET.test(value)) return null;
    const id = hash(value); const session = sessions.get(id);
    if (!session || session.until <= clock()) { sessions.delete(id); return null; }
    return { ...session, id };
  };
  const admin = (req, res, next) => {
    const session = readSession(req);
    if (!session) return fail(res, 401, "sign_in_required");
    req.adminSession = session; next();
  };
  const csrf = (req, res, next) => {
    if (!equal(req.get("X-CSRF-Token") || "", req.adminSession.csrf)) return fail(res, 403, "csrf_rejected");
    next();
  };
  app.post("/api/admin/login", sameOrigin, async (req, res) => {
    if (!limit(req, res, `login:${req.ip}`, 8, 15 * 60000)) return;
    if (!equal(req.body?.username || "", adminUser) || !await verifyPassword(req.body?.password, adminPasswordHash)) return fail(res, 401, "invalid_credentials");
    for (const [id, session] of sessions) if (session.until <= clock()) sessions.delete(id);
    if (sessions.size >= 100) return fail(res, 503, "session_limit");
    const token = crypto.randomBytes(32).toString("base64url");
    const session = { user: adminUser, csrf: crypto.randomBytes(32).toString("base64url"), until: clock() + 8 * 3600000 };
    sessions.set(hash(token), session);
    res.cookie("kululu_support", token, { httpOnly: true, secure: secureCookies, sameSite: "strict", path: "/", maxAge: 8 * 3600000 });
    res.json({ ok: true, user: session.user, csrf: session.csrf });
  });
  app.get("/api/admin/session", admin, (req, res) => res.json({ ok: true, user: req.adminSession.user, csrf: req.adminSession.csrf }));
  app.post("/api/admin/logout", sameOrigin, admin, csrf, (req, res) => {
    sessions.delete(req.adminSession.id);
    res.clearCookie("kululu_support", { httpOnly: true, secure: secureCookies, sameSite: "strict", path: "/" }); res.json({ ok: true });
  });
  app.get("/api/admin/stats", admin, async (_req, res) => res.json(await store.stats()));
  app.get("/api/admin/tickets", admin, async (req, res) => {
    const { status, type, before, q, scope } = req.query;
    if (scope && scope !== "requests") return fail(res, 400, "invalid_filter");
    if ((status && !STATUSES.has(status)) || (type && !TYPES.has(type)) || (before && !/^[1-9][0-9]{0,15}$/.test(before)) || (q && (typeof q !== "string" || q.length > 100))) return fail(res, 400, "invalid_filter");
    res.json(await store.list({ status, type, before, query: q, scope }));
  });
  app.get("/api/admin/tickets/:id", admin, async (req, res) => {
    if (!/^[1-9][0-9]{0,15}$/.test(req.params.id)) return fail(res, 400, "invalid_id");
    const ticket = await store.detail(req.params.id);
    if (!ticket) return fail(res, 404, "not_found"); res.json(ticket);
  });
  app.patch("/api/admin/tickets/:id", sameOrigin, admin, csrf, async (req, res) => {
    if (!/^[1-9][0-9]{0,15}$/.test(req.params.id) || !STATUSES.has(req.body?.status)) return fail(res, 400, "invalid_status");
    if (!await store.status(req.params.id, req.body.status, req.adminSession.user)) return fail(res, 404, "not_found");
    res.json({ ok: true });
  });

  app.post("/api/v1/installations", async (req, res) => {
    if (!limit(req, res, `register:${req.ip}`, 30, 3600000) || !limit(req, res, "register:global", 3000, 86400000)) return;
    const { installationId, secret } = req.body || {};
    if (typeof installationId !== "string" || typeof secret !== "string" || !UUID.test(installationId) || !SECRET.test(secret)) return fail(res, 400, "invalid_installation");
    if (!await store.register(installationId.toLowerCase(), hash(secret))) return fail(res, 409, "installation_conflict");
    res.json({ ok: true });
  });
  const device = async (req, res, next) => {
    const match = /^Bearer ([0-9a-f-]{36})\.([A-Za-z0-9_-]{43})$/i.exec(req.get("Authorization") || "");
    if (!match || !UUID.test(match[1]) || !await store.authenticate(match[1].toLowerCase(), hash(match[2]))) return fail(res, 401, "installation_required");
    req.installation = match[1].toLowerCase(); next();
  };
  app.post("/api/v1/tickets", device, async (req, res) => {
    if (!limit(req, res, `write:${req.installation}`, 20, 3600000)) return;
    const ticket = ticketPayload(req.body);
    if (!ticket) return fail(res, 400, "invalid_ticket");
    if (!limit(req, res, "tickets:global", 5000, 86400000)) return;
    if (ticket.type === "diagnostic" && (!limit(req, res, `reports:${req.ip}`, 20, 86400000) || !limit(req, res, "reports:global", 500, 86400000))) return;
    res.status(201).json({ ok: true, ...await store.create(req.installation, ticket) });
  });
  app.get("/api/v1/tickets", device, async (req, res) => res.json({ ok: true, requests: await store.mine(req.installation) }));
  app.post("/api/v1/tickets/ack", device, async (req, res) => {
    const ids = req.body?.ids;
    if (!Array.isArray(ids) || ids.length > 20 || ids.some(id => !Number.isSafeInteger(id) || id <= 0)) return fail(res, 400, "invalid_ids");
    await store.ack(req.installation, ids); res.json({ ok: true });
  });
  app.use(express.static(path.join(__dirname, "public"), { etag: false, maxAge: 0, dotfiles: "deny" }));
  app.use((_req, res) => fail(res, 404, "not_found"));
  app.use((error, _req, res, _next) => {
    if (error.type === "entity.too.large") return fail(res, 413, "report_too_large");
    if (error instanceof SyntaxError && "body" in error) return fail(res, 400, "invalid_json");
    if (error.code === "support_capacity") return fail(res, 503, "support_capacity");
    console.error("[support] request failed:", error.code || error.name || "unknown");
    fail(res, 503, "temporarily_unavailable");
  });
  return app;
}
module.exports = { createApp };
