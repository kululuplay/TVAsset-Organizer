/*
 * Kululu IPTV — crash receiver + live device telemetry + ops panel
 * A tiny Express service the Android app talks to (no Google Play Services needed,
 * important for Fire TV sticks). Jobs:
 *   1. Crash reports — the app silently POSTs a report on the next launch after a
 *      crash. Stored in Postgres, viewed through a password-protected panel.
 *   2. Live devices — while foregrounded the app sends a heartbeat every minute,
 *      so the panel shows which devices are online now, their IP + city, model,
 *      app/Android version and what they're watching. Heartbeats UPSERT one row
 *      per device.
 *   3. Remote announcement — an operator message set in the panel is returned in
 *      every heartbeat response; the app shows it once as a banner/dialog.
 *
 * Endpoints:
 *   POST /api/crash               crash ingest (app -> server), X-Kululu-Key
 *   POST /api/heartbeat           live ping (app -> server), X-Kululu-Key; returns announcement
 *   GET  /                        HTML panel (HTTP Basic auth)
 *   GET  /api/crashes             JSON crash list (auth)
 *   GET  /api/devices             JSON device list (auth)
 *   POST /api/announcement        set the active announcement (auth)
 *   POST /api/announcement/clear  clear the active announcement (auth)
 *   POST /api/crashes/:id/delete  delete one crash (auth)
 *   POST /api/crashes/clear       delete all crashes (auth)
 *   POST /api/devices/clear       delete all devices (auth)
 *   GET  /healthz                 health check
 */
const express = require("express");
const { Pool } = require("pg");

const app = express();
const PORT = process.env.PORT || 5000;

// A device counts as "online" if we've heard a heartbeat within this window.
// Heartbeats arrive every 60s, so 3 min tolerates a couple of dropped beats on
// flaky TV wifi without flapping the status.
const ONLINE_WINDOW_MS = 3 * 60 * 1000;
const ONLINE_WINDOW_SEC = Math.round(ONLINE_WINDOW_MS / 1000);

// IP -> city/country cache so we don't hit the geolocation API on every beat.
const GEO_TTL_MS = 24 * 3600 * 1000;
const GEO_FAIL_RETRY_MS = 5 * 60 * 1000;
const geoCache = new Map(); // ip -> { city, country, at }
const geoInFlight = new Set();

// Active operator announcement, cached in memory (refreshed on set/clear + boot)
// so heartbeats never trigger a DB read.
let activeAnnouncement = null; // { id, message } | null

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
      log TEXT,
      ip TEXT,
      device_id TEXT
    );
  `);
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_crash_received ON crash_reports(received_at DESC);`,
  );
  // CREATE TABLE IF NOT EXISTS never adds columns to an existing table, so any
  // column added after first deploy must be ALTERed in explicitly.
  await pool.query(`ALTER TABLE crash_reports ADD COLUMN IF NOT EXISTS ip TEXT;`);
  await pool.query(
    `ALTER TABLE crash_reports ADD COLUMN IF NOT EXISTS device_id TEXT;`,
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
      version_code INTEGER,
      now_playing TEXT,
      now_playing_kind TEXT,
      geo_city TEXT,
      geo_country TEXT,
      last_nettest TEXT,
      last_nettest_at TIMESTAMPTZ
    );
  `);
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen DESC);`,
  );
  await pool.query(
    `ALTER TABLE devices ADD COLUMN IF NOT EXISTS now_playing TEXT;`,
  );
  await pool.query(
    `ALTER TABLE devices ADD COLUMN IF NOT EXISTS now_playing_kind TEXT;`,
  );
  await pool.query(`ALTER TABLE devices ADD COLUMN IF NOT EXISTS geo_city TEXT;`);
  await pool.query(
    `ALTER TABLE devices ADD COLUMN IF NOT EXISTS geo_country TEXT;`,
  );
  await pool.query(
    `ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_nettest TEXT;`,
  );
  await pool.query(
    `ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_nettest_at TIMESTAMPTZ;`,
  );
  // Operator announcement pushed to every device via the heartbeat response.
  await pool.query(`
    CREATE TABLE IF NOT EXISTS announcements (
      id SERIAL PRIMARY KEY,
      message TEXT NOT NULL,
      active BOOLEAN NOT NULL DEFAULT true,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
  `);
  await refreshAnnouncementCache();
}

async function refreshAnnouncementCache() {
  try {
    const { rows } = await pool.query(
      `SELECT id, message FROM announcements WHERE active = true ORDER BY id DESC LIMIT 1`,
    );
    activeAnnouncement = rows[0] || null;
  } catch (e) {
    console.error("announcement cache refresh failed", e);
  }
}

app.use(express.json({ limit: "512kb" }));
app.use(express.urlencoded({ extended: false }));
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

// Private / loopback / link-local addresses have no public geolocation.
function isPublicIp(ip) {
  if (!ip) return false;
  if (ip === "127.0.0.1" || ip === "::1" || ip === "0.0.0.0") return false;
  if (/^127\./.test(ip)) return false;
  if (/^10\./.test(ip)) return false;
  if (/^192\.168\./.test(ip)) return false;
  if (/^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(ip)) return false;
  if (/^169\.254\./.test(ip)) return false;
  if (ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) {
    return false;
  }
  return true;
}

function cachedGeo(ip) {
  const e = geoCache.get(ip);
  if (e && Date.now() - e.at < GEO_TTL_MS) return e;
  return null;
}

async function fetchGeo(ip) {
  const r = await fetch(
    `http://ip-api.com/json/${encodeURIComponent(ip)}?fields=status,city,country`,
    { signal: AbortSignal.timeout(4000) },
  );
  const j = await r.json();
  if (j && j.status === "success") {
    return { city: clip(j.city, 80), country: clip(j.country, 80), at: Date.now() };
  }
  return { city: null, country: null, at: Date.now() }; // valid response, no geo
}

// Fire-and-forget: ensure every row with this IP has its city/country backfilled.
// Cheap conditional UPDATE when already cached; one network lookup per new IP.
async function applyGeo(ip) {
  if (!isPublicIp(ip)) return;
  let geo = cachedGeo(ip);
  if (!geo) {
    if (geoInFlight.has(ip)) return;
    geoInFlight.add(ip);
    try {
      geo = await fetchGeo(ip);
      geoCache.set(ip, geo);
    } catch {
      // Transient network error — retry sooner than the success TTL.
      geoCache.set(ip, {
        city: null,
        country: null,
        at: Date.now() - GEO_TTL_MS + GEO_FAIL_RETRY_MS,
      });
      geoInFlight.delete(ip);
      return;
    }
    geoInFlight.delete(ip);
  }
  if (geo && geo.city) {
    await pool
      .query(
        `UPDATE devices SET geo_city=$1, geo_country=$2
           WHERE ip=$3 AND (geo_city IS DISTINCT FROM $1 OR geo_country IS DISTINCT FROM $2)`,
        [geo.city, geo.country, ip],
      )
      .catch(() => {});
  }
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
         android_version, api_level, message, log, ip, device_id)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)`,
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
        clip(b.deviceId, 128),
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
// Sent every ~60s while the app is foregrounded. Upserts one row per device and
// returns the active announcement (if any) for the app to surface.
app.post("/api/heartbeat", async (req, res) => {
  if ((req.get("X-Kululu-Key") || "") !== INGEST_KEY) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const b = req.body || {};
  const deviceId = clip(b.deviceId, 128);
  if (!deviceId) return res.status(400).json({ error: "missing_device_id" });
  const ip = clientIp(req);
  try {
    await pool.query(
      `INSERT INTO devices
        (device_id, last_seen, ip, manufacturer, model, device,
         android_version, api_level, app_version, version_code,
         now_playing, now_playing_kind)
       VALUES ($1, now(), $2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
       ON CONFLICT (device_id) DO UPDATE SET
         last_seen = now(),
         ip = EXCLUDED.ip,
         manufacturer = EXCLUDED.manufacturer,
         model = EXCLUDED.model,
         device = EXCLUDED.device,
         android_version = EXCLUDED.android_version,
         api_level = EXCLUDED.api_level,
         app_version = EXCLUDED.app_version,
         version_code = EXCLUDED.version_code,
         now_playing = EXCLUDED.now_playing,
         now_playing_kind = EXCLUDED.now_playing_kind`,
      [
        deviceId,
        ip,
        clip(b.manufacturer),
        clip(b.model),
        clip(b.device),
        clip(b.androidVersion),
        toInt(b.apiLevel),
        clip(b.appVersion),
        toInt(b.versionCode),
        clip(b.nowPlaying, 200),
        clip(b.nowPlayingKind, 40),
      ],
    );
    res.status(200).json({ announcement: activeAnnouncement });
    // Resolve location after responding so the heartbeat stays fast.
    applyGeo(ip).catch(() => {});
  } catch (e) {
    console.error("heartbeat failed", e);
    return res.status(500).json({ error: "store_failed" });
  }
});

// ---- Peering / network-quality test result (app -> server) ----
// One human-readable line per device summarising its last in-app peering test
// (run from Settings -> Diagnostics). Upserted so it survives even if the device
// row predates the column, and never accumulates.
app.post("/api/nettest", async (req, res) => {
  if ((req.get("X-Kululu-Key") || "") !== INGEST_KEY) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const b = req.body || {};
  const deviceId = clip(b.deviceId, 128);
  if (!deviceId) return res.status(400).json({ error: "missing_device_id" });
  const summary = clip(b.summary, 1000);
  try {
    await pool.query(
      `INSERT INTO devices (device_id, last_nettest, last_nettest_at)
       VALUES ($1, $2, now())
       ON CONFLICT (device_id) DO UPDATE SET
         last_nettest = EXCLUDED.last_nettest,
         last_nettest_at = now()`,
      [deviceId, summary],
    );
    return res.status(204).end();
  } catch (e) {
    console.error("nettest failed", e);
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
    `SELECT *, (last_seen > now() - interval '${ONLINE_WINDOW_SEC} seconds') AS online
       FROM devices ORDER BY last_seen DESC LIMIT 1000`,
  );
  res.json(rows);
});

// ---- Announcement management ----
app.post("/api/announcement", auth, async (req, res) => {
  const message = clip((req.body && req.body.message) || "", 500);
  if (!message || !message.trim()) return res.redirect("/");
  await pool.query(`UPDATE announcements SET active = false WHERE active = true`);
  await pool.query(
    `INSERT INTO announcements (message, active) VALUES ($1, true)`,
    [message.trim()],
  );
  await refreshAnnouncementCache();
  res.redirect("/");
});

app.post("/api/announcement/clear", auth, async (req, res) => {
  await pool.query(`UPDATE announcements SET active = false WHERE active = true`);
  await refreshAnnouncementCache();
  res.redirect("/");
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
      `SELECT d.*,
              (d.last_seen > now() - interval '${ONLINE_WINDOW_SEC} seconds') AS online,
              c.crash_count, c.last_crash
         FROM devices d
         LEFT JOIN (
           SELECT device_id, count(*) AS crash_count, max(received_at) AS last_crash
             FROM crash_reports WHERE device_id IS NOT NULL GROUP BY device_id
         ) c ON c.device_id = d.device_id
        ORDER BY (d.last_seen > now() - interval '${ONLINE_WINDOW_SEC} seconds') DESC,
                 d.last_seen DESC
        LIMIT 500`,
    ),
  ]);
  const rows = crashRes.rows;
  const devices = deviceRes.rows;

  const dayAgo = Date.now() - 24 * 3600 * 1000;
  const last24 = rows.filter((r) => new Date(r.received_at).getTime() > dayAgo).length;
  const onlineCount = devices.filter((d) => d.online).length;

  // Same-IP grouping = likely same household/account (multi-TV). Not proof of
  // cross-home credential sharing, but a useful "this account is on N boxes" flag.
  const ipDeviceCount = new Map();
  for (const d of devices) {
    if (!d.ip) continue;
    if (!ipDeviceCount.has(d.ip)) ipDeviceCount.set(d.ip, new Set());
    ipDeviceCount.get(d.ip).add(d.device_id);
  }
  const sharedIp = (ip) => (ip && ipDeviceCount.get(ip)?.size > 1) || false;

  // Version distribution across registered devices.
  const versionCounts = new Map();
  for (const d of devices) {
    const key = d.app_version || "?";
    versionCounts.set(key, (versionCounts.get(key) || 0) + 1);
  }
  const versionChips = [...versionCounts.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([v, n]) => `<span class="chip">v${esc(v)} <b>${n}</b></span>`)
    .join("");

  const deviceRows = devices
    .map((d) => {
      const name = `${d.manufacturer || ""} ${d.model || ""}`.trim() || "Bilinmeyen cihaz";
      const status = d.online ? "Çevrimiçi" : "Çevrimdışı";
      const geo = [d.geo_city, d.geo_country].filter(Boolean).join(", ");
      const shared = sharedIp(d.ip)
        ? ` <span class="badge warn" title="Aynı IP'de ${ipDeviceCount.get(d.ip).size} cihaz">⚠ ${ipDeviceCount.get(d.ip).size}</span>`
        : "";
      const playing = d.online && d.now_playing
        ? `<span class="play">▶ ${esc(d.now_playing)}</span>${d.now_playing_kind ? ` <span class="muted">${esc(d.now_playing_kind)}</span>` : ""}`
        : '<span class="muted">—</span>';
      const crash = d.crash_count
        ? `<span class="badge err" title="Son: ${esc(fmt(d.last_crash))}">${d.crash_count}</span>`
        : '<span class="muted">0</span>';
      const nettest = d.last_nettest
        ? `<span title="${esc(fmt(d.last_nettest_at))}">${esc(d.last_nettest)}</span><div class="muted">${esc(ago(d.last_nettest_at))}</div>`
        : '<span class="muted">—</span>';
      return `
      <tr class="${d.online ? "online" : "offline"}">
        <td><span class="dot ${d.online ? "on" : "off"}" title="${status}"></span>${status}</td>
        <td class="strong">${esc(name)}</td>
        <td class="mono">${esc(d.ip || "—")}${shared}${geo ? `<div class="muted">${esc(geo)}</div>` : ""}</td>
        <td>${playing}</td>
        <td>${versionLabel(d.app_version, d.version_code)}</td>
        <td>Android ${esc(d.android_version || "?")}${d.api_level ? " · API " + esc(d.api_level) : ""}</td>
        <td>${crash}</td>
        <td class="nettest">${nettest}</td>
        <td title="${esc(fmt(d.last_seen))}">${esc(ago(d.last_seen))}</td>
        <td class="muted" title="${esc(fmt(d.first_seen))}">${esc(fmt(d.first_seen))}</td>
      </tr>`;
    })
    .join("");

  const deviceSection = devices.length
    ? `<table class="devices">
        <thead><tr>
          <th>Durum</th><th>Cihaz</th><th>IP / Konum</th><th>Şu an izliyor</th>
          <th>Sürüm</th><th>Android</th><th>Çökme</th><th>Ağ testi</th><th>Son görülme</th><th>İlk görülme</th>
        </tr></thead>
        <tbody>${deviceRows}</tbody>
      </table>`
    : '<div class="empty">Henüz bağlı cihaz yok. (Yalnızca güncel sürümü yükleyen cihazlar görünür.)</div>';

  const announcementBox = activeAnnouncement
    ? `<div class="ann-current">
         <span class="ann-label">Aktif duyuru</span>
         <span class="ann-msg">${esc(activeAnnouncement.message)}</span>
         <form method="post" action="/api/announcement/clear" style="margin:0">
           <button class="clearbtn" type="submit">Kaldır</button>
         </form>
       </div>`
    : "";

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
<title>Kululu IPTV — Çökme &amp; Cihaz Paneli</title>
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
  main { padding:16px 24px; max-width:1200px; }
  section { margin-bottom:28px; }
  .empty { color:#5B6877; padding:24px 0; text-align:center; }
  .count-badge { background:#16331F; color:#2FBF71; border:1px solid #1f5a35; padding:2px 10px; border-radius:20px; font-size:12px; font-weight:600; }
  .chip { background:#1C232D; color:#9AA7B4; padding:3px 10px; border-radius:20px; font-size:12px; margin-right:6px; }
  .chip b { color:#3DA9FC; }
  .ann { background:#161B22; border:1px solid #1C232D; border-radius:12px; padding:14px 16px; }
  .ann textarea { width:100%; box-sizing:border-box; background:#0E1116; color:#F5F7FA; border:1px solid #2A3340; border-radius:8px; padding:10px; font:13px/1.4 system-ui, sans-serif; resize:vertical; min-height:54px; }
  .ann .sendbtn { margin-top:8px; background:#1f5a35; color:#D6FBE5; border:1px solid #2FBF71; padding:8px 16px; border-radius:8px; cursor:pointer; font-size:13px; }
  .ann-current { display:flex; align-items:center; gap:12px; background:#23301c; border:1px solid #3a5a2a; border-radius:10px; padding:10px 14px; margin-bottom:12px; }
  .ann-label { color:#9fe6b8; font-size:11px; text-transform:uppercase; letter-spacing:.5px; }
  .ann-msg { flex:1; color:#EAF7EE; }
  table { width:100%; border-collapse:collapse; background:#161B22; border:1px solid #1C232D; border-radius:12px; overflow:hidden; }
  thead th { text-align:left; font-size:12px; color:#9AA7B4; font-weight:600; padding:10px 12px; border-bottom:1px solid #1C232D; background:#12171E; }
  tbody td { padding:10px 12px; border-bottom:1px solid #161B22; font-size:13px; vertical-align:top; }
  tbody tr:last-child td { border-bottom:none; }
  tbody tr.offline { opacity:.55; }
  tbody tr.online { background:rgba(47,191,113,.05); }
  .strong { font-weight:600; }
  .mono { font-family:monospace; }
  .muted { color:#5B6877; font-size:12px; }
  .play { color:#FFB300; }
  .badge { display:inline-block; padding:1px 7px; border-radius:20px; font-size:11px; font-weight:600; }
  .badge.warn { background:#3a2f10; color:#FFC93C; border:1px solid #6a5410; }
  .badge.err { background:#3a1714; color:#F2766A; border:1px solid #6a201a; }
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
    <h2>Duyuru</h2>
    ${announcementBox}
    <form class="ann" method="post" action="/api/announcement">
      <textarea name="message" maxlength="500" placeholder="Tüm cihazlara gönderilecek mesaj (ör. 'Yarın 02:00-04:00 arası bakım yapılacaktır')"></textarea>
      <button class="sendbtn" type="submit">Duyuruyu yayınla</button>
    </form>
  </section>
  <section>
    <h2>Canlı Cihazlar <span class="count-badge">${onlineCount} çevrimiçi</span></h2>
    ${versionChips ? `<div style="margin-bottom:12px">${versionChips}</div>` : ""}
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
