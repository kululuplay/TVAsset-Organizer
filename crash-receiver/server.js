/*
 * Kululu IPTV — crash receiver + live device telemetry
 * A tiny Express service the Android app talks to (no Google Play Services needed,
 * important for Fire TV sticks). Two jobs:
 *   1. Crash reports — the app silently POSTs a report on the next launch after a
 *      crash. Stored in Postgres, viewed through a password-protected panel.
 *   2. Live devices — while foregrounded the app sends a lightweight heartbeat
 *      every minute, so the panel shows which devices are online right now, their
 *      IP, model, app/Android version. Heartbeats UPSERT one row per device.
 *
 * Endpoints:
 *   POST /api/crash            crash ingest (app -> server), guarded by X-Kululu-Key
 *   POST /api/heartbeat        live ping (app -> server), guarded by X-Kululu-Key
 *   GET  /                     HTML panel (HTTP Basic auth)
 *   GET  /api/crashes          JSON crash list (auth)
 *   GET  /api/devices          JSON device list (auth)
 *   POST /api/crashes/:id/delete   delete one crash (auth)
 *   POST /api/crashes/clear        delete all crashes (auth)
 *   POST /api/devices/clear        delete all devices (auth)
 *   GET  /healthz              health check
 */
const express = require("express");
const { Pool } = require("pg");

const app = express();
const PORT = process.env.PORT || 5000;

// A device counts as "online" if we've heard a heartbeat within this window.
// Heartbeats arrive every 60s, so 3 min tolerates a couple of dropped beats on
// flaky TV wifi without flapping the status.
const ONLINE_WINDOW_MS = 3 * 60 * 1000;

// Shared key the app stamps on every report. NOT a real secret (it ships inside
// the APK and is extractable) — it only deters casual spam. Rotate by setting
// CRASH_INGEST_KEY on the server AND in the app's Telemetry helper if abused.
const INGEST_KEY =
  process.env.CRASH_INGEST_KEY || "kululu-crash-ingest-v1-7f3ab9c2";

// Panel login. ADMIN_PASSWORD must be set as a secret before publishing.
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "kululu-dev";
if (!process.env.ADMIN_PASSWORD) {
  console.warn(
    "[crash-receiver] ADMIN_PASSWORD is not set — using a weak dev password. " +
      "Set the ADMIN_PASSWORD secret before publishing.",
  );
}

// Optional: forward each crash to a Telegram chat if these are configured.
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || "";
const TELEGRAM_CHAT_ID = process.env.TELEGRAM_CHAT_ID || "";

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl:
    process.env.DATABASE_URL &&
    !process.env.DATABASE_URL.includes("localhost") &&
    !process.env.DATABASE_URL.includes("127.0.0.1")
      ? { rejectUnauthorized: false }
      : false,
});

async function initDb() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS crash_reports (
      id SERIAL PRIMARY KEY,
      received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      occurred_at TIMESTAMPTZ,
      app_version TEXT,
      version_code INTEGER,
      manufacturer TEXT,
      model TEXT,
      device TEXT,
      android_version TEXT,
      api_level INTEGER,
      message TEXT,
      log TEXT
    );
  `);
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_crash_received ON crash_reports(received_at DESC);`,
  );
  // CREATE TABLE IF NOT EXISTS never adds columns to an existing table, so the
  // client IP must be added explicitly for already-deployed databases.
  await pool.query(
    `ALTER TABLE crash_reports ADD COLUMN IF NOT EXISTS ip TEXT;`,
  );
  // One row per device, refreshed by heartbeats. first_seen is preserved across
  // upserts; last_seen drives the online/offline status in the panel.
  await pool.query(`
    CREATE TABLE IF NOT EXISTS devices (
      device_id TEXT PRIMARY KEY,
      first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
      last_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
      ip TEXT,
      manufacturer TEXT,
      model TEXT,
      device TEXT,
      android_version TEXT,
      api_level INTEGER,
      app_version TEXT,
      version_code INTEGER
    );
  `);
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen DESC);`,
  );
}

app.use(express.json({ limit: "512kb" }));
app.set("trust proxy", true);

function clip(v, max = 500) {
  if (v == null) return null;
  const s = String(v);
  return s.length > max ? s.slice(0, max) : s;
}

function toInt(v) {
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? n : null;
}

// Real client IP. 'trust proxy' is on, so req.ip resolves through Replit's proxy
// to the device's public address. Strip the IPv4-mapped-IPv6 prefix for clarity.
function clientIp(req) {
  let ip = req.ip || req.socket?.remoteAddress || "";
  if (ip.startsWith("::ffff:")) ip = ip.slice(7);
  return clip(ip, 64);
}

// ---- Crash ingest (app -> server) ----
app.post("/api/crash", async (req, res) => {
  if ((req.get("X-Kululu-Key") || "") !== INGEST_KEY) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const b = req.body || {};
  try {
    await pool.query(
      `INSERT INTO crash_reports
        (occurred_at, app_version, version_code, manufacturer, model, device,
         android_version, api_level, message, log, ip)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`,
      [
        b.occurredAt ? new Date(b.occurredAt) : null,
        clip(b.appVersion),
        toInt(b.versionCode),
        clip(b.manufacturer),
        clip(b.model),
        clip(b.device),
        clip(b.androidVersion),
        toInt(b.apiLevel),
        clip(b.message, 2000),
        clip(b.log, 200000),
        clientIp(req),
      ],
    );
    forwardTelegram(b).catch(() => {});
    return res.status(204).end();
  } catch (e) {
    console.error("insert failed", e);
    return res.status(500).json({ error: "store_failed" });
  }
});

// ---- Heartbeat ingest (app -> server) ----
// Sent every ~60s while the app is foregrounded. Upserts one row per device so
// the panel can show who is watching right now.
app.post("/api/heartbeat", async (req, res) => {
  if ((req.get("X-Kululu-Key") || "") !== INGEST_KEY) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const b = req.body || {};
  const deviceId = clip(b.deviceId, 128);
  if (!deviceId) return res.status(400).json({ error: "missing_device_id" });
  try {
    await pool.query(
      `INSERT INTO devices
        (device_id, last_seen, ip, manufacturer, model, device,
         android_version, api_level, app_version, version_code)
       VALUES ($1, now(), $2,$3,$4,$5,$6,$7,$8,$9)
       ON CONFLICT (device_id) DO UPDATE SET
         last_seen = now(),
         ip = EXCLUDED.ip,
         manufacturer = EXCLUDED.manufacturer,
         model = EXCLUDED.model,
         device = EXCLUDED.device,
         android_version = EXCLUDED.android_version,
         api_level = EXCLUDED.api_level,
         app_version = EXCLUDED.app_version,
         version_code = EXCLUDED.version_code`,
      [
        deviceId,
        clientIp(req),
        clip(b.manufacturer),
        clip(b.model),
        clip(b.device),
        clip(b.androidVersion),
        toInt(b.apiLevel),
        clip(b.appVersion),
        toInt(b.versionCode),
      ],
    );
    return res.status(204).end();
  } catch (e) {
    console.error("heartbeat failed", e);
    return res.status(500).json({ error: "store_failed" });
  }
});

async function forwardTelegram(b) {
  if (!TELEGRAM_BOT_TOKEN || !TELEGRAM_CHAT_ID) return;
  const text =
    `🛑 Kululu çökme\n` +
    `${b.manufacturer || "?"} ${b.model || ""} · Android ${b.androidVersion || "?"} · v${b.appVersion || "?"}\n` +
    `${String(b.message || "").slice(0, 500)}`;
  await fetch(`https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chat_id: TELEGRAM_CHAT_ID, text }),
  });
}

// ---- Basic auth for the panel ----
function auth(req, res, next) {
  const hdr = req.get("Authorization") || "";
  const [scheme, encoded] = hdr.split(" ");
  if (scheme === "Basic" && encoded) {
    const [user, pass] = Buffer.from(encoded, "base64")
      .toString("utf8")
      .split(":");
    if (user === ADMIN_USER && pass === ADMIN_PASSWORD) return next();
  }
  res.set("WWW-Authenticate", 'Basic realm="Kululu Crash Panel"');
  return res.status(401).send("Authentication required");
}

app.get("/healthz", (req, res) => res.status(200).send("ok"));

app.get("/api/crashes", auth, async (req, res) => {
  const { rows } = await pool.query(
    `SELECT * FROM crash_reports ORDER BY received_at DESC LIMIT 500`,
  );
  res.json(rows);
});

app.get("/api/devices", auth, async (req, res) => {
  const { rows } = await pool.query(
    `SELECT *, (last_seen > now() - interval '${Math.round(ONLINE_WINDOW_MS / 1000)} seconds') AS online
       FROM devices ORDER BY last_seen DESC LIMIT 1000`,
  );
  res.json(rows);
});

app.post("/api/crashes/:id/delete", auth, async (req, res) => {
  await pool.query(`DELETE FROM crash_reports WHERE id = $1`, [
    toInt(req.params.id),
  ]);
  res.redirect("/");
});

app.post("/api/crashes/clear", auth, async (req, res) => {
  await pool.query(`DELETE FROM crash_reports`);
  res.redirect("/");
});

app.post("/api/devices/clear", auth, async (req, res) => {
  await pool.query(`DELETE FROM devices`);
  res.redirect("/");
});

const esc = (s) =>
  String(s ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;",
      })[c],
  );

function fmt(ts) {
  if (!ts) return "—";
  try {
    return new Date(ts).toLocaleString("tr-TR", { timeZone: "Europe/Istanbul" });
  } catch {
    return String(ts);
  }
}

// "5 dk önce" style relative label so "online" is obvious at a glance.
function ago(ts) {
  if (!ts) return "—";
  const sec = Math.max(0, Math.round((Date.now() - new Date(ts).getTime()) / 1000));
  if (sec < 60) return `${sec} sn önce`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min} dk önce`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr} sa önce`;
  return `${Math.round(hr / 24)} gün önce`;
}

function versionLabel(v, code) {
  if (!v && !code) return "—";
  return `v${esc(v || "?")}${code ? " (" + esc(code) + ")" : ""}`;
}

app.get("/", auth, async (req, res) => {
  const [crashRes, deviceRes] = await Promise.all([
    pool.query(`SELECT * FROM crash_reports ORDER BY received_at DESC LIMIT 300`),
    pool.query(
      `SELECT *, (last_seen > now() - interval '${Math.round(ONLINE_WINDOW_MS / 1000)} seconds') AS online
         FROM devices ORDER BY (last_seen > now() - interval '${Math.round(ONLINE_WINDOW_MS / 1000)} seconds') DESC, last_seen DESC LIMIT 500`,
    ),
  ]);
  const rows = crashRes.rows;
  const devices = deviceRes.rows;

  const dayAgo = Date.now() - 24 * 3600 * 1000;
  const last24 = rows.filter((r) => new Date(r.received_at).getTime() > dayAgo).length;
  const onlineCount = devices.filter((d) => d.online).length;

  const deviceRows = devices
    .map((d) => {
      const name = `${d.manufacturer || ""} ${d.model || ""}`.trim() || "Bilinmeyen cihaz";
      const dot = d.online ? "on" : "off";
      const status = d.online ? "Çevrimiçi" : "Çevrimdışı";
      return `
      <tr class="${d.online ? "online" : "offline"}">
        <td><span class="dot ${dot}" title="${status}"></span>${status}</td>
        <td class="strong">${esc(name)}</td>
        <td class="mono">${esc(d.ip || "—")}</td>
        <td>${versionLabel(d.app_version, d.version_code)}</td>
        <td>Android ${esc(d.android_version || "?")}${d.api_level ? " · API " + esc(d.api_level) : ""}</td>
        <td title="${esc(fmt(d.last_seen))}">${esc(ago(d.last_seen))}</td>
        <td class="muted" title="${esc(fmt(d.first_seen))}">${esc(fmt(d.first_seen))}</td>
      </tr>`;
    })
    .join("");

  const deviceSection = devices.length
    ? `<table class="devices">
        <thead><tr>
          <th>Durum</th><th>Cihaz</th><th>IP adresi</th><th>Sürüm</th>
          <th>Android</th><th>Son görülme</th><th>İlk görülme</th>
        </tr></thead>
        <tbody>${deviceRows}</tbody>
      </table>`
    : '<div class="empty">Henüz bağlı cihaz yok. (Yalnızca güncel sürümü yükleyen cihazlar görünür.)</div>';

  const cards = rows
    .map((r) => {
      const firstLine = (r.message || "").split("\n")[0] || "(mesaj yok)";
      return `
      <div class="card">
        <div class="meta">
          <span class="dev">${esc(r.manufacturer)} ${esc(r.model)}</span>
          <span class="pill">Android ${esc(r.android_version)}</span>
          <span class="pill">v${esc(r.app_version)}${r.version_code ? " (" + esc(r.version_code) + ")" : ""}</span>
          ${r.ip ? `<span class="pill ip">${esc(r.ip)}</span>` : ""}
          <span class="time">${esc(fmt(r.received_at))}</span>
          <form method="post" action="/api/crashes/${r.id}/delete" class="del">
            <button title="Sil">✕</button>
          </form>
        </div>
        <div class="msg">${esc(firstLine)}</div>
        <details>
          <summary>Tam kaydı göster</summary>
          <pre>${esc(r.log || "(kayıt yok)")}</pre>
        </details>
      </div>`;
    })
    .join("");

  res.send(`<!doctype html>
<html lang="tr"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="60">
<title>Kululu IPTV — Çökme Raporları</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; background:#0E1116; color:#F5F7FA; font:14px/1.5 system-ui, sans-serif; }
  header { padding:20px 24px; border-bottom:1px solid #1C232D; position:sticky; top:0; background:#0E1116; z-index:2; }
  h1 { margin:0 0 8px; font-size:20px; }
  h2 { font-size:15px; margin:0 0 12px; color:#F5F7FA; display:flex; align-items:center; gap:8px; }
  .stats { color:#9AA7B4; font-size:13px; }
  .stats b { color:#3DA9FC; }
  .stats .live { color:#2FBF71; }
  .actions { margin-top:12px; display:flex; gap:8px; flex-wrap:wrap; }
  .actions a, .clearbtn { background:#1C232D; color:#F5F7FA; border:1px solid #2A3340; padding:8px 14px; border-radius:8px; text-decoration:none; cursor:pointer; font-size:13px; }
  .clearbtn { color:#E0533D; }
  main { padding:16px 24px; max-width:1100px; }
  section { margin-bottom:28px; }
  .empty { color:#5B6877; padding:24px 0; text-align:center; }
  .count-badge { background:#16331F; color:#2FBF71; border:1px solid #1f5a35; padding:2px 10px; border-radius:20px; font-size:12px; font-weight:600; }
  table { width:100%; border-collapse:collapse; background:#161B22; border:1px solid #1C232D; border-radius:12px; overflow:hidden; }
  thead th { text-align:left; font-size:12px; color:#9AA7B4; font-weight:600; padding:10px 12px; border-bottom:1px solid #1C232D; background:#12171E; }
  tbody td { padding:10px 12px; border-bottom:1px solid #161B22; font-size:13px; }
  tbody tr:last-child td { border-bottom:none; }
  tbody tr.offline { opacity:.55; }
  tbody tr.online { background:rgba(47,191,113,.05); }
  .strong { font-weight:600; }
  .mono { font-family:monospace; }
  .muted { color:#5B6877; font-size:12px; }
  .dot { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:7px; vertical-align:middle; }
  .dot.on { background:#2FBF71; box-shadow:0 0 0 3px rgba(47,191,113,.18); }
  .dot.off { background:#5B6877; }
  .card { background:#161B22; border:1px solid #1C232D; border-radius:12px; padding:14px 16px; margin-bottom:12px; }
  .meta { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
  .dev { font-weight:600; }
  .pill { background:#1C232D; color:#9AA7B4; padding:2px 8px; border-radius:20px; font-size:12px; }
  .pill.ip { font-family:monospace; color:#7FB2E5; }
  .time { color:#5B6877; font-size:12px; margin-left:auto; }
  .del { margin:0; }
  .del button { background:transparent; border:none; color:#5B6877; cursor:pointer; font-size:14px; }
  .del button:hover { color:#E0533D; }
  .msg { margin:8px 0; color:#FFB300; font-family:monospace; font-size:13px; word-break:break-word; }
  details summary { cursor:pointer; color:#3DA9FC; font-size:13px; }
  pre { background:#0E1116; border:1px solid #1C232D; border-radius:8px; padding:12px; overflow:auto; max-height:420px; font-size:11px; color:#9AA7B4; white-space:pre-wrap; word-break:break-word; }
</style></head>
<body>
<header>
  <h1>Kululu IPTV — Çökme &amp; Cihaz Paneli</h1>
  <div class="stats">
    <span class="live">● Canlı <b class="live">${onlineCount}</b></span> · Kayıtlı cihaz <b>${devices.length}</b> · Toplam çökme <b>${rows.length}</b> · Son 24 saat <b>${last24}</b>
  </div>
  <div class="actions">
    <a href="/">↻ Yenile</a>
    <form method="post" action="/api/devices/clear" style="display:inline" onsubmit="return confirm('Tüm cihaz kayıtları silinsin mi?')">
      <button class="clearbtn" type="submit">Cihazları temizle</button>
    </form>
    <form method="post" action="/api/crashes/clear" style="display:inline" onsubmit="return confirm('Tüm çökme raporları silinsin mi?')">
      <button class="clearbtn" type="submit">Çökmeleri temizle</button>
    </form>
  </div>
</header>
<main>
  <section>
    <h2>Canlı Cihazlar <span class="count-badge">${onlineCount} çevrimiçi</span></h2>
    ${deviceSection}
  </section>
  <section>
    <h2>Çökme Raporları</h2>
    ${rows.length ? cards : '<div class="empty">Henüz çökme raporu yok.</div>'}
  </section>
</main>
</body></html>`);
});

initDb()
  .then(() => {
    app.listen(PORT, "0.0.0.0", () =>
      console.log(`[crash-receiver] listening on :${PORT}`),
    );
  })
  .catch((e) => {
    console.error("[crash-receiver] DB init failed", e);
    process.exit(1);
  });
