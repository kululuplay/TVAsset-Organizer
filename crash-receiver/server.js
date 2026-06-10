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
 *   3. Remote announcements — operator messages set in the panel are returned in
 *      heartbeat responses; the app shows each once. Messages can be global, or
 *      targeted to one account (username) or one device.
 *   4. Requests & complaints — the app's Home "İstek & Şikayet" dialog POSTs a
 *      typed request (live channel / movie / series / complaint). Stored and
 *      shown in the panel, where the operator can mark it handled or delete it.
 *
 * Endpoints:
 *   POST /api/crash               crash ingest (app -> server), X-Kululu-Key
 *   POST /api/heartbeat           live ping (app -> server), X-Kululu-Key; returns announcement
 *   POST /api/nettest             network-test result (app -> server), X-Kululu-Key
 *   POST /api/request             user request/complaint (app -> server), X-Kululu-Key
 *   GET  /                        HTML panel (HTTP Basic auth)
 *   GET  /device/:id              per-device detail: watch / nettest / crash history (auth)
 *   GET  /api/crashes             JSON crash list (auth)
 *   GET  /api/devices             JSON device list (auth)
 *   POST /api/announcement        publish an announcement, optionally targeted (auth)
 *   POST /api/announcement/clear  retire one announcement by id, or all (auth)
 *   POST /api/crashes/:id/delete  delete one crash (auth)
 *   POST /api/crashes/clear       delete all crashes (auth)
 *   POST /api/devices/clear       delete all devices (auth)
 *   POST /api/requests/:id/done   toggle a request handled/new (auth)
 *   POST /api/requests/:id/delete delete one request (auth)
 *   POST /api/requests/clear-done delete all handled requests (auth)
 *   GET  /healthz                 health check
 */
const express = require("express");
const { Pool } = require("pg");
const crypto = require("crypto");

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

// Active operator announcements, cached in memory (refreshed on every CRUD +
// boot) so heartbeats never trigger a DB read. Each beat picks the highest-id
// row targeting its device, its account, or a global broadcast.
let activeAnnouncements = []; // [{ id, message, target_device_id, target_username }]

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
// Pre-hash the expected credential to a fixed 32-byte digest so the auth compare
// is constant-time (timingSafeEqual needs equal-length buffers).
const sha256 = (s) => crypto.createHash("sha256").update(String(s), "utf8").digest();
const ADMIN_CRED_HASH = sha256(`${ADMIN_USER}:${ADMIN_PASSWORD}`);

// Optional: forward each crash to a Telegram chat if these are configured.
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || "";
const TELEGRAM_CHAT_ID = process.env.TELEGRAM_CHAT_ID || "";

// Data retention: max age in days per table; 0 disables that sweep. Devices keep
// a generous default because pruning one loses its account/geo mapping until the
// app re-registers. Runs on boot and every 24h.
const intEnv = (name, def) => {
  const v = parseInt(process.env[name], 10);
  return Number.isFinite(v) && v >= 0 ? v : def;
};
const RETENTION_DEVICE_DAYS = intEnv("RETENTION_DEVICE_DAYS", 90);
const RETENTION_CRASH_DAYS = intEnv("RETENTION_CRASH_DAYS", 60);
const RETENTION_LOG_DAYS = intEnv("RETENTION_LOG_DAYS", 30);

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
      username TEXT,
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
  await pool.query(
    `ALTER TABLE devices ADD COLUMN IF NOT EXISTS username TEXT;`,
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
  // Targeting: NULL+NULL = broadcast (everyone); target_device_id = one box;
  // target_username = every box logged into that account. Existing prod table,
  // so these must be ALTERed in (CREATE IF NOT EXISTS won't add them).
  await pool.query(
    `ALTER TABLE announcements ADD COLUMN IF NOT EXISTS target_device_id TEXT;`,
  );
  await pool.query(
    `ALTER TABLE announcements ADD COLUMN IF NOT EXISTS target_username TEXT;`,
  );
  // Watch history: one row per channel/title CHANGE (not per beat) so the device
  // detail page can show what a box has been watching. Pruned by retention.
  await pool.query(`
    CREATE TABLE IF NOT EXISTS watch_log (
      id SERIAL PRIMARY KEY,
      device_id TEXT NOT NULL,
      title TEXT,
      kind TEXT,
      at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
  `);
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_watch_log_device ON watch_log(device_id, at DESC);`,
  );
  // Network-test history (the devices.last_nettest column is last-only).
  await pool.query(`
    CREATE TABLE IF NOT EXISTS nettest_log (
      id SERIAL PRIMARY KEY,
      device_id TEXT NOT NULL,
      summary TEXT,
      at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
  `);
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_nettest_log_device ON nettest_log(device_id, at DESC);`,
  );
  // User requests / complaints from the app's Home "İstek & Şikayet" dialog. New
  // table, so CREATE IF NOT EXISTS is enough (no prod ALTER needed). status:
  // 'new' until the operator marks it 'done' in the panel.
  await pool.query(`
    CREATE TABLE IF NOT EXISTS requests (
      id SERIAL PRIMARY KEY,
      device_id TEXT,
      username TEXT,
      type TEXT NOT NULL DEFAULT 'other',
      message TEXT NOT NULL,
      app_version TEXT,
      version_code INTEGER,
      manufacturer TEXT,
      model TEXT,
      status TEXT NOT NULL DEFAULT 'new',
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
  `);
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_requests_created ON requests(status, created_at DESC);`,
  );
  // Resolved-notify: the app checks each heartbeat for requests the operator
  // marked 'done' and pops the user a confirmation, then acks. Added via ALTER
  // because deployed DBs already have the table (the CREATE above no-ops there).
  // DEFAULT true first so EXISTING rows backfill as already-notified (no popup
  // storm for history on the next beat); then flip the default to false so NEW
  // requests start un-notified and surface once resolved.
  await pool.query(
    `ALTER TABLE requests ADD COLUMN IF NOT EXISTS notified BOOLEAN NOT NULL DEFAULT true;`,
  );
  await pool.query(`ALTER TABLE requests ALTER COLUMN notified SET DEFAULT false;`);
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_requests_device ON requests(device_id, created_at DESC);`,
  );
  await refreshAnnouncementCache();
}

async function refreshAnnouncementCache() {
  try {
    const { rows } = await pool.query(
      `SELECT id, message, target_device_id, target_username
         FROM announcements WHERE active = true ORDER BY id DESC`,
    );
    activeAnnouncements = rows;
  } catch (e) {
    console.error("announcement cache refresh failed", e);
  }
}

// Pick the single announcement a device should receive: the highest-id active
// row targeting this device, this account, or everyone. The app dedups by
// high-water id (monotonic SERIAL), so the newest relevant one shows once.
function pickAnnouncement(deviceId, username) {
  let best = null;
  for (const a of activeAnnouncements) {
    const match =
      (a.target_device_id && a.target_device_id === deviceId) ||
      (a.target_username && username && a.target_username === username) ||
      (!a.target_device_id && !a.target_username);
    if (match && (!best || a.id > best.id)) best = a;
  }
  return best ? { id: best.id, message: best.message } : null;
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
  // Blank -> null so a logged-out beat doesn't wipe the account mapping, and so
  // username targeting matches the same value we store.
  const uname = clip(
    b.username && String(b.username).trim() ? b.username : null,
    120,
  );
  try {
    // Watch history: log a row only when the title CHANGES. Compare against the
    // device's previous now_playing BEFORE the upsert below overwrites it; a
    // first-ever beat logs too (subselect is NULL -> IS DISTINCT FROM -> true).
    // Best-effort: a logging failure must never break the heartbeat.
    const np = clip(b.nowPlaying, 200);
    if (np) {
      await pool
        .query(
          `INSERT INTO watch_log (device_id, title, kind)
           SELECT $1, $2, $3
           WHERE $2 IS DISTINCT FROM (
             SELECT now_playing FROM devices WHERE device_id = $1
           )`,
          [deviceId, np, clip(b.nowPlayingKind, 40)],
        )
        .catch((e) => console.error("watch_log insert failed", e));
    }
    await pool.query(
      `INSERT INTO devices
        (device_id, last_seen, ip, manufacturer, model, device,
         android_version, api_level, app_version, version_code,
         now_playing, now_playing_kind, username)
       VALUES ($1, now(), $2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
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
         now_playing_kind = EXCLUDED.now_playing_kind,
         -- Keep the last known username if a beat omits it (M3U sources / logged
         -- out) so the panel doesn't lose the account-to-device mapping.
         username = COALESCE(EXCLUDED.username, devices.username)`,
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
        // See uname above: blank coalesced to null; COALESCE in SQL preserves
        // the last known account mapping when a beat omits it.
        uname,
      ],
    );
    // Resolved-request notifications for this device: requests the operator
    // marked 'done' that the app hasn't been told about yet. Best-effort and
    // isolated so a lookup failure can never break the heartbeat.
    let resolvedRequests = [];
    try {
      const rr = await pool.query(
        `SELECT id, type, message FROM requests
          WHERE device_id = $1 AND status = 'done' AND notified = false
          ORDER BY id ASC LIMIT 10`,
        [deviceId],
      );
      resolvedRequests = rr.rows.map((r) => ({
        id: r.id,
        type: r.type,
        message: clip(r.message, 200),
      }));
    } catch (e) {
      console.error("resolved-requests lookup failed", e);
    }
    const payload = { announcement: pickAnnouncement(deviceId, uname) };
    if (resolvedRequests.length) payload.resolvedRequests = resolvedRequests;
    res.status(200).json(payload);
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
    // History row too (the devices.last_nettest column is last-only). Best-effort.
    if (summary) {
      await pool
        .query(`INSERT INTO nettest_log (device_id, summary) VALUES ($1, $2)`, [
          deviceId,
          summary,
        ])
        .catch((e) => console.error("nettest_log insert failed", e));
    }
    return res.status(204).end();
  } catch (e) {
    console.error("nettest failed", e);
    return res.status(500).json({ error: "store_failed" });
  }
});

// ---- User request / complaint (app -> server) ----
// The Home "İstek & Şikayet" dialog posts a typed request. Validated, clipped and
// stored; surfaced in the panel and (best-effort) forwarded to Telegram.
const REQUEST_TYPES = ["channel", "movie", "series", "complaint"];
app.post("/api/request", async (req, res) => {
  if ((req.get("X-Kululu-Key") || "") !== INGEST_KEY) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const b = req.body || {};
  const message = clip(b.message, 2000);
  if (!message || !message.trim()) {
    return res.status(400).json({ error: "missing_message" });
  }
  const type = REQUEST_TYPES.includes(b.type) ? b.type : "other";
  const username = clip(typeof b.username === "string" ? b.username.trim() || null : null, 120);
  const manufacturer = clip(b.manufacturer, 80);
  const model = clip(b.model, 80);
  try {
    await pool.query(
      `INSERT INTO requests
         (device_id, username, type, message, app_version, version_code, manufacturer, model)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [
        clip(b.deviceId, 128),
        username,
        type,
        message.trim(),
        clip(b.appVersion, 40),
        toInt(b.versionCode),
        manufacturer,
        model,
      ],
    );
    // Best-effort nudge to the operator; never blocks the response.
    forwardRequestTelegram({ type, message: message.trim(), username, manufacturer, model }).catch(
      () => {},
    );
    return res.status(204).end();
  } catch (e) {
    console.error("request store failed", e);
    return res.status(500).json({ error: "store_failed" });
  }
});

// ---- App: the user's own request history (shown in the İstek & Şikayet dialog) ----
// Device-scoped (no per-user auth): the unguessable device id + shared ingest key
// match the trust model of /api/heartbeat and /api/request.
app.get("/api/requests/mine", async (req, res) => {
  if ((req.get("X-Kululu-Key") || "") !== INGEST_KEY) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const deviceId = clip(req.query.deviceId, 128);
  if (!deviceId) return res.status(400).json({ error: "missing_device_id" });
  try {
    const { rows } = await pool.query(
      `SELECT id, type, message, status, created_at
         FROM requests WHERE device_id = $1
         ORDER BY id DESC LIMIT 20`,
      [deviceId],
    );
    return res.status(200).json({
      requests: rows.map((r) => ({
        id: r.id,
        type: r.type,
        message: r.message,
        status: r.status,
        createdAt: r.created_at,
      })),
    });
  } catch (e) {
    console.error("requests/mine failed", e);
    return res.status(500).json({ error: "query_failed" });
  }
});

// ---- App: acknowledge resolved-request popups so the server stops re-sending ----
app.post("/api/requests/ack", async (req, res) => {
  if ((req.get("X-Kululu-Key") || "") !== INGEST_KEY) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const b = req.body || {};
  const deviceId = clip(b.deviceId, 128);
  const ids = Array.isArray(b.ids)
    ? b.ids.map((x) => toInt(x)).filter((n) => n != null)
    : [];
  if (!deviceId || ids.length === 0) return res.status(204).end();
  try {
    await pool.query(
      `UPDATE requests SET notified = true
        WHERE device_id = $1 AND id = ANY($2::int[])`,
      [deviceId, ids],
    );
    return res.status(204).end();
  } catch (e) {
    console.error("requests/ack failed", e);
    return res.status(500).json({ error: "ack_failed" });
  }
});

async function sendTelegram(text) {
  if (!TELEGRAM_BOT_TOKEN || !TELEGRAM_CHAT_ID) return;
  await fetch(`https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chat_id: TELEGRAM_CHAT_ID, text }),
  });
}

async function forwardTelegram(b) {
  await sendTelegram(
    `🛑 Kululu çökme\n` +
      `${b.manufacturer || "?"} ${b.model || ""} · Android ${b.androidVersion || "?"} · v${b.appVersion || "?"}\n` +
      `${String(b.message || "").slice(0, 500)}`,
  );
}

// Operator-facing labels for each request type (panel + Telegram).
const REQUEST_TYPE_LABEL = {
  channel: "📺 Canlı kanal",
  movie: "🎬 Film",
  series: "📺 Dizi",
  complaint: "⚠️ Şikayet",
  other: "📝 İstek",
};

async function forwardRequestTelegram(r) {
  const who = [r.username, `${r.manufacturer || ""} ${r.model || ""}`.trim()]
    .filter(Boolean)
    .join(" · ");
  await sendTelegram(
    `${REQUEST_TYPE_LABEL[r.type] || REQUEST_TYPE_LABEL.other} — Kululu isteği\n` +
      `${who ? who + "\n" : ""}` +
      `${String(r.message || "").slice(0, 800)}`,
  );
}

// Debounced account-sharing alert: warns (once per window per account) when one
// username is active from multiple cities. No-ops unless Telegram is configured.
const SHARING_ALERT_DEBOUNCE_MS = 12 * 3600 * 1000;
const sharingAlertedAt = new Map(); // username -> last alert ts
async function checkSharingAlerts() {
  if (!TELEGRAM_BOT_TOKEN || !TELEGRAM_CHAT_ID) return;
  try {
    const { rows } = await pool.query(
      `SELECT username,
              count(DISTINCT NULLIF(geo_city,'')) AS city_count,
              string_agg(DISTINCT NULLIF(geo_city,''), ', ') AS cities,
              count(*) AS device_count
         FROM devices
        WHERE username IS NOT NULL AND username <> ''
          AND last_seen > now() - interval '1 day'
        GROUP BY username
       HAVING count(DISTINCT NULLIF(geo_city,'')) > 1`,
    );
    const now = Date.now();
    for (const r of rows) {
      const last = sharingAlertedAt.get(r.username) || 0;
      if (now - last < SHARING_ALERT_DEBOUNCE_MS) continue;
      sharingAlertedAt.set(r.username, now);
      await sendTelegram(
        `⚠ Kululu hesap paylaşımı şüphesi\n` +
          `Hesap: ${r.username}\n` +
          `${r.city_count} şehir: ${r.cities}\n` +
          `${r.device_count} cihaz`,
      ).catch(() => {});
    }
  } catch (e) {
    console.error("sharing alert check failed", e);
  }
}

// ---- Basic auth for the panel ----
// Throttle FAILED auth per IP only (with time decay). A correct password always
// works and clears the counter, so the operator can never lock themselves out;
// this just slows brute-force guessing.
const AUTH_FAIL_LIMIT = 20;
const AUTH_FAIL_WINDOW_MS = 15 * 60 * 1000;
const authFails = new Map(); // ip -> { count, firstAt }
function authThrottled(ip) {
  const rec = authFails.get(ip);
  if (!rec) return false;
  if (Date.now() - rec.firstAt > AUTH_FAIL_WINDOW_MS) {
    authFails.delete(ip); // window elapsed -> decay
    return false;
  }
  return rec.count >= AUTH_FAIL_LIMIT;
}
function noteAuthFail(ip) {
  const now = Date.now();
  const rec = authFails.get(ip);
  if (!rec || now - rec.firstAt > AUTH_FAIL_WINDOW_MS) {
    authFails.set(ip, { count: 1, firstAt: now });
  } else {
    rec.count++;
  }
}
// Bound the throttle map so spoofed/rotating IPs can't grow it without limit:
// drop entries whose window has fully decayed. Called from the retention tick.
function sweepAuthFails() {
  const now = Date.now();
  for (const [ip, rec] of authFails) {
    if (now - rec.firstAt > AUTH_FAIL_WINDOW_MS) authFails.delete(ip);
  }
}

function auth(req, res, next) {
  const ip = clientIp(req);
  // Evaluate credentials FIRST so a correct password is never blocked, even from
  // a throttled IP — the operator can always get in. Throttling applies only to
  // wrong guesses.
  const hdr = req.get("Authorization") || "";
  const [scheme, encoded] = hdr.split(" ");
  let ok = false;
  if (scheme === "Basic" && encoded) {
    const decoded = Buffer.from(encoded, "base64").toString("utf8");
    // Split on the FIRST colon so a password containing ':' still works.
    const idx = decoded.indexOf(":");
    const user = idx >= 0 ? decoded.slice(0, idx) : decoded;
    const pass = idx >= 0 ? decoded.slice(idx + 1) : "";
    // Constant-time compare over equal-length SHA-256 digests.
    ok = crypto.timingSafeEqual(sha256(`${user}:${pass}`), ADMIN_CRED_HASH);
  }
  if (ok) {
    authFails.delete(ip); // success clears the counter
    return next();
  }
  noteAuthFail(ip);
  if (authThrottled(ip)) {
    res.set("Retry-After", "900");
    return res.status(429).send("Too many failed attempts. Try again later.");
  }
  res.set("WWW-Authenticate", 'Basic realm="Kululu Crash Panel"');
  return res.status(401).send("Authentication required");
}

// Delete rows older than the configured age, per table. 0 days disables a sweep.
async function runRetention() {
  sweepAuthFails(); // also bound the in-memory throttle map on each tick
  const sweeps = [
    ["devices", "last_seen", RETENTION_DEVICE_DAYS],
    ["crash_reports", "received_at", RETENTION_CRASH_DAYS],
    ["watch_log", "at", RETENTION_LOG_DAYS],
    ["nettest_log", "at", RETENTION_LOG_DAYS],
  ];
  for (const [table, col, days] of sweeps) {
    if (!days) continue; // 0 = disabled
    try {
      const r = await pool.query(
        `DELETE FROM ${table} WHERE ${col} < now() - make_interval(days => $1)`,
        [days],
      );
      if (r.rowCount)
        console.log(
          `[retention] ${table}: pruned ${r.rowCount} rows older than ${days}d`,
        );
    } catch (e) {
      console.error(`[retention] ${table} sweep failed`, e);
    }
  }
  // Requests: prune only HANDLED rows by the log window; pending requests are
  // kept until the operator handles or deletes them (never auto-dropped).
  if (RETENTION_LOG_DAYS) {
    try {
      const r = await pool.query(
        `DELETE FROM requests
          WHERE status = 'done' AND created_at < now() - make_interval(days => $1)`,
        [RETENTION_LOG_DAYS],
      );
      if (r.rowCount)
        console.log(
          `[retention] requests: pruned ${r.rowCount} handled rows older than ${RETENTION_LOG_DAYS}d`,
        );
    } catch (e) {
      console.error("[retention] requests sweep failed", e);
    }
  }
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
  const targetDevice = clip((req.body.target_device_id || "").trim() || null, 128);
  const targetUser = clip((req.body.target_username || "").trim() || null, 120);
  // Scope-aware: deactivate only same-scope active rows (IS NOT DISTINCT FROM
  // treats NULL=NULL, so a global publish only retires the previous global and a
  // targeted publish only retires the previous one for that exact target). This
  // keeps a global announcement alive when you add a targeted one, and vice versa.
  await pool.query(
    `UPDATE announcements SET active = false
      WHERE active = true
        AND target_device_id IS NOT DISTINCT FROM $1
        AND target_username  IS NOT DISTINCT FROM $2`,
    [targetDevice, targetUser],
  );
  // Always INSERT a NEW row (never reactivate an old one): the app only shows
  // ids ABOVE its high-water mark, so a reused lower id would never display.
  await pool.query(
    `INSERT INTO announcements (message, active, target_device_id, target_username)
     VALUES ($1, true, $2, $3)`,
    [message.trim(), targetDevice, targetUser],
  );
  await refreshAnnouncementCache();
  res.redirect("/");
});

app.post("/api/announcement/clear", auth, async (req, res) => {
  // Per-row clear (id from the panel) so retiring a targeted message leaves the
  // others active; no id falls back to clearing everything.
  const id = toInt(req.body && req.body.id);
  if (id) {
    await pool.query(`UPDATE announcements SET active = false WHERE id = $1`, [id]);
  } else {
    await pool.query(`UPDATE announcements SET active = false WHERE active = true`);
  }
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

// ---- Request / complaint management ----
app.post("/api/requests/:id/done", auth, async (req, res) => {
  // Toggle handled <-> new so a mis-click is reversible. Reset notified on every
  // toggle: marking done re-arms the user's "resolved" popup; reverting clears
  // it (harmless while status='new', and lets a later re-resolve notify again).
  await pool.query(
    `UPDATE requests
        SET status = CASE WHEN status = 'done' THEN 'new' ELSE 'done' END,
            notified = false
      WHERE id = $1`,
    [toInt(req.params.id)],
  );
  res.redirect("/");
});

app.post("/api/requests/:id/delete", auth, async (req, res) => {
  await pool.query(`DELETE FROM requests WHERE id = $1`, [toInt(req.params.id)]);
  res.redirect("/");
});

app.post("/api/requests/clear-done", auth, async (req, res) => {
  // Only handled rows; pending requests are never bulk-deleted by accident.
  await pool.query(`DELETE FROM requests WHERE status = 'done'`);
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
  const [
    crashRes,
    deviceRes,
    topChannelsRes,
    accountsRes,
    crashSigRes,
    crashVerRes,
    requestsRes,
  ] = await Promise.all([
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
      // Top channels right now — aggregate what ONLINE devices are watching.
      pool.query(
        `SELECT now_playing AS title, max(now_playing_kind) AS kind, count(*) AS n
           FROM devices
          WHERE last_seen > now() - interval '${ONLINE_WINDOW_SEC} seconds'
            AND now_playing IS NOT NULL AND now_playing <> ''
          GROUP BY now_playing
          ORDER BY n DESC, now_playing
          LIMIT 15`,
      ),
      // Accounts — group by username; distinct devices/IPs/cities flag sharing.
      pool.query(
        `SELECT username,
                count(*) AS device_count,
                count(DISTINCT ip) AS ip_count,
                count(DISTINCT NULLIF(geo_city,'')) AS city_count,
                count(*) FILTER (
                  WHERE last_seen > now() - interval '${ONLINE_WINDOW_SEC} seconds'
                ) AS online_count,
                string_agg(DISTINCT NULLIF(geo_city,''), ', ') AS cities
           FROM devices
          WHERE username IS NOT NULL AND username <> ''
          GROUP BY username
          ORDER BY city_count DESC, device_count DESC, username
          LIMIT 100`,
      ),
      // Crash grouping by signature (first message line) over ALL crashes.
      pool.query(
        `SELECT NULLIF(split_part(coalesce(message,''), E'\\n', 1), '') AS signature,
                count(*) AS n,
                count(DISTINCT device_id) AS devices,
                max(received_at) AS last_at,
                string_agg(DISTINCT NULLIF(app_version,''), ', ') AS versions
           FROM crash_reports
          GROUP BY NULLIF(split_part(coalesce(message,''), E'\\n', 1), '')
          ORDER BY n DESC
          LIMIT 30`,
      ),
      // Crashes per app version (all crashes) — combined with device counts in JS
      // to get a per-version crash rate.
      pool.query(
        `SELECT coalesce(NULLIF(app_version,''),'?') AS app_version,
                count(*) AS crashes,
                count(DISTINCT device_id) AS crashed_devices
           FROM crash_reports
          GROUP BY coalesce(NULLIF(app_version,''),'?')`,
      ),
      // User requests / complaints — newest first, unhandled on top.
      pool.query(
        `SELECT r.*,
                trim(coalesce(d.manufacturer,'') || ' ' || coalesce(d.model,'')) AS device_name
           FROM requests r
           LEFT JOIN devices d ON d.device_id = r.device_id
          ORDER BY (r.status = 'new') DESC, r.created_at DESC
          LIMIT 200`,
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
      const user = d.username
        ? `<span class="strong">${esc(d.username)}</span>`
        : '<span class="muted">—</span>';
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
        <td class="strong"><a class="devlink" href="/device/${encodeURIComponent(d.device_id)}">${esc(name)}</a></td>
        <td>${user}</td>
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
          <th>Durum</th><th>Cihaz</th><th>Kullanıcı</th><th>IP / Konum</th><th>Şu an izliyor</th>
          <th>Sürüm</th><th>Android</th><th>Çökme</th><th>Ağ testi</th><th>Son görülme</th><th>İlk görülme</th>
        </tr></thead>
        <tbody>${deviceRows}</tbody>
      </table>`
    : '<div class="empty">Henüz bağlı cihaz yok. (Yalnızca güncel sürümü yükleyen cihazlar görünür.)</div>';

  const scopeLabel = (a) =>
    a.target_device_id
      ? `🎯 Cihaz: <span class="mono">${esc(a.target_device_id)}</span>`
      : a.target_username
        ? `👤 Hesap: ${esc(a.target_username)}`
        : "📢 Herkese";
  const announcementBox = activeAnnouncements.length
    ? `<div class="ann-list">${activeAnnouncements
        .map(
          (a) => `<div class="ann-current">
         <span class="ann-label">${scopeLabel(a)}</span>
         <span class="ann-msg">${esc(a.message)}</span>
         <form method="post" action="/api/announcement/clear" style="margin:0">
           <input type="hidden" name="id" value="${a.id}">
           <button class="clearbtn" type="submit">Kaldır</button>
         </form>
       </div>`,
        )
        .join("")}</div>`
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

  // ---- Top channels right now ----
  const topChannels = topChannelsRes.rows;
  const topChannelsSection = topChannels.length
    ? `<table class="agg">
         <thead><tr><th>#</th><th>Kanal / İçerik</th><th>Tür</th><th>İzleyici</th></tr></thead>
         <tbody>${topChannels
           .map(
             (c, i) => `
           <tr>
             <td class="muted">${i + 1}</td>
             <td class="strong">${esc(c.title)}</td>
             <td class="muted">${esc(c.kind || "—")}</td>
             <td><span class="count-badge">${c.n}</span></td>
           </tr>`,
           )
           .join("")}
         </tbody>
       </table>`
    : '<div class="empty">Şu an çevrimiçi izleyici yok.</div>';

  // ---- Accounts / sharing ----
  const accounts = accountsRes.rows;
  const accountRows = accounts
    .map((a) => {
      const cities = Number(a.city_count);
      const ips = Number(a.ip_count);
      const sharing = cities > 1;
      const badge = sharing
        ? `<span class="badge err" title="${cities} farklı şehir">⚠ paylaşım?</span>`
        : ips > 1
          ? `<span class="badge warn" title="${ips} farklı IP">${ips} IP</span>`
          : "";
      return `
      <tr class="${sharing ? "flag" : ""}">
        <td class="strong">${esc(a.username)} ${badge}</td>
        <td>${a.online_count}/${a.device_count}</td>
        <td>${a.ip_count}</td>
        <td>${a.city_count}${a.cities ? `<div class="muted">${esc(a.cities)}</div>` : ""}</td>
      </tr>`;
    })
    .join("");
  const accountsSection = accounts.length
    ? `<table class="agg">
         <thead><tr><th>Hesap (kullanıcı adı)</th><th>Çevrimiçi/Cihaz</th><th>IP</th><th>Şehir</th></tr></thead>
         <tbody>${accountRows}</tbody>
       </table>
       <div class="muted" style="margin-top:8px">⚠ paylaşım? = aynı hesap birden çok şehirden aktif (olası hesap paylaşımı).</div>`
    : '<div class="empty">Henüz kullanıcı adı bildiren cihaz yok.</div>';

  // ---- Crash summary: per-version rate + grouped signatures ----
  const crashSigs = crashSigRes.rows;
  const verRows = crashVerRes.rows
    .sort((a, b) => Number(b.crashes) - Number(a.crashes))
    .map((v) => {
      const devs = versionCounts.get(v.app_version) || 0;
      const pct =
        devs > 0 ? Math.round((Number(v.crashed_devices) / devs) * 100) : null;
      const rate =
        pct === null
          ? '<span class="muted">—</span>'
          : `<span class="rate ${pct >= 30 ? "hi" : pct >= 10 ? "mid" : "lo"}">%${pct}</span>`;
      return `
      <tr>
        <td class="strong">v${esc(v.app_version)}</td>
        <td>${v.crashes}</td>
        <td>${v.crashed_devices}/${devs}</td>
        <td>${rate}</td>
      </tr>`;
    })
    .join("");
  const sigRows = crashSigs
    .map((s) => {
      const sig = s.signature && s.signature.trim() ? s.signature : "(mesaj yok)";
      return `
      <tr>
        <td class="msg-sig">${esc(sig)}</td>
        <td><span class="badge err">${s.n}</span></td>
        <td>${s.devices}</td>
        <td class="muted">${esc(s.versions || "—")}</td>
        <td class="muted" title="${esc(fmt(s.last_at))}">${esc(ago(s.last_at))}</td>
      </tr>`;
    })
    .join("");
  const crashSummarySection = crashSigs.length
    ? `<div class="grid2">
         <div>
           <h3>Sürüme göre çökme oranı <span class="muted">(çöken cihaz / o sürümdeki cihaz)</span></h3>
           <table class="agg"><thead><tr><th>Sürüm</th><th>Çökme</th><th>Cihaz</th><th>Oran</th></tr></thead><tbody>${verRows}</tbody></table>
         </div>
         <div>
           <h3>En sık çökmeler</h3>
           <table class="agg"><thead><tr><th>Hata (ilk satır)</th><th>Adet</th><th>Cihaz</th><th>Sürüm</th><th>Son</th></tr></thead><tbody>${sigRows}</tbody></table>
         </div>
       </div>`
    : "";

  // ---- User requests / complaints ----
  const requests = requestsRes.rows;
  const pendingRequests = requests.filter((r) => r.status === "new").length;
  const REQ_TYPE_BADGE = {
    channel: '<span class="rtype rtype-channel">📺 Canlı kanal</span>',
    movie: '<span class="rtype rtype-movie">🎬 Film</span>',
    series: '<span class="rtype rtype-series">📺 Dizi</span>',
    complaint: '<span class="rtype rtype-complaint">⚠️ Şikayet</span>',
    other: '<span class="rtype rtype-other">📝 İstek</span>',
  };
  const requestRows = requests
    .map((r) => {
      const dev = (r.device_name || "").trim();
      const who = r.username
        ? `<span class="strong">${esc(r.username)}</span>`
        : dev
          ? esc(dev)
          : '<span class="muted">Bilinmeyen</span>';
      const link = r.device_id
        ? `<a class="devlink" href="/device/${encodeURIComponent(r.device_id)}">${who}</a>`
        : who;
      const done = r.status === "done";
      return `
      <div class="req ${done ? "done" : "new"}">
        <div class="req-head">
          ${REQ_TYPE_BADGE[r.type] || REQ_TYPE_BADGE.other}
          <span class="req-who">${link}</span>
          ${dev && r.username ? `<span class="muted">${esc(dev)}</span>` : ""}
          ${r.app_version ? `<span class="pill">v${esc(r.app_version)}</span>` : ""}
          <span class="time" title="${esc(fmt(r.created_at))}">${esc(ago(r.created_at))}</span>
        </div>
        <div class="req-msg">${esc(r.message)}</div>
        <div class="req-actions">
          <form method="post" action="/api/requests/${r.id}/done" style="margin:0">
            <button class="${done ? "undonebtn" : "donebtn"}" type="submit">${done ? "↩ Geri al" : "✓ Çözüldü"}</button>
          </form>
          <form method="post" action="/api/requests/${r.id}/delete" style="margin:0" onsubmit="return confirm('Bu istek silinsin mi?')">
            <button class="clearbtn" type="submit">Sil</button>
          </form>
        </div>
      </div>`;
    })
    .join("");
  const requestsSection = requests.length
    ? requestRows
    : '<div class="empty">Henüz istek veya şikayet yok.</div>';

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
  .grid2 { display:grid; grid-template-columns:1fr 1fr; gap:16px; }
  @media (max-width:900px){ .grid2 { grid-template-columns:1fr; } }
  h3 { font-size:13px; margin:0 0 8px; color:#9AA7B4; font-weight:600; }
  h3 .muted { font-weight:400; }
  tbody tr.flag { background:rgba(224,83,61,.08); }
  .rate { font-weight:600; }
  .rate.lo { color:#2FBF71; }
  .rate.mid { color:#FFC93C; }
  .rate.hi { color:#F2766A; }
  .msg-sig { font-family:monospace; color:#FFB300; font-size:12px; max-width:420px; white-space:normal; word-break:break-word; }
  a.devlink { color:inherit; text-decoration:none; border-bottom:1px dotted #2A3340; }
  a.devlink:hover { border-bottom-color:#3DA9FC; color:#3DA9FC; }
  .ann-list { display:flex; flex-direction:column; gap:8px; margin-bottom:12px; }
  .ann-targets { display:flex; gap:8px; flex-wrap:wrap; margin-top:8px; }
  .ann-targets input { flex:1; min-width:220px; background:#0E1116; color:#F5F7FA; border:1px solid #2A3340; border-radius:8px; padding:8px 10px; font:13px system-ui, sans-serif; }
  .req { background:#161B22; border:1px solid #1C232D; border-radius:12px; padding:14px 16px; margin-bottom:12px; }
  .req.new { border-left:3px solid #FFB300; }
  .req.done { opacity:.6; }
  .req-head { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
  .req-head .time { margin-left:auto; }
  .req-who { font-size:13px; }
  .req-msg { margin:10px 0; color:#EAF0F6; font-size:14px; white-space:pre-wrap; word-break:break-word; }
  .req-actions { display:flex; gap:8px; }
  .req-actions button { border:1px solid #2A3340; background:#1C232D; color:#F5F7FA; padding:6px 14px; border-radius:8px; cursor:pointer; font-size:12px; }
  .donebtn { color:#9fe6b8; border-color:#2FBF71 !important; }
  .undonebtn { color:#9AA7B4; }
  .req-pending { background:#3a2f10; color:#FFC93C; border:1px solid #6a5410; }
  .rtype { display:inline-block; padding:2px 10px; border-radius:20px; font-size:11px; font-weight:700; }
  .rtype-channel { background:#10283f; color:#5BB0F5; border:1px solid #1d4a73; }
  .rtype-movie { background:#2a1840; color:#C39BF5; border:1px solid #4a2d73; }
  .rtype-series { background:#10302e; color:#5BE0D0; border:1px solid #1d6359; }
  .rtype-complaint { background:#3a1714; color:#F2766A; border:1px solid #6a201a; }
  .rtype-other { background:#1C232D; color:#9AA7B4; border:1px solid #2A3340; }
</style></head>
<body>
<header>
  <h1>Kululu IPTV — Çökme &amp; Cihaz Paneli</h1>
  <div class="stats">
    <span class="live">● Canlı <b class="live">${onlineCount}</b></span> · Kayıtlı cihaz <b>${devices.length}</b> · Toplam çökme <b>${rows.length}</b> · Son 24 saat <b>${last24}</b> · Bekleyen istek <b>${pendingRequests}</b>
  </div>
  <div class="actions">
    <a href="/">↻ Yenile</a>
    <form method="post" action="/api/devices/clear" style="display:inline" onsubmit="return confirm('Tüm cihaz kayıtları silinsin mi?')">
      <button class="clearbtn" type="submit">Cihazları temizle</button>
    </form>
    <form method="post" action="/api/crashes/clear" style="display:inline" onsubmit="return confirm('Tüm çökme raporları silinsin mi?')">
      <button class="clearbtn" type="submit">Çökmeleri temizle</button>
    </form>
    <form method="post" action="/api/requests/clear-done" style="display:inline" onsubmit="return confirm('Çözülmüş tüm istekler silinsin mi?')">
      <button class="clearbtn" type="submit">Çözülen istekleri temizle</button>
    </form>
  </div>
</header>
<main>
  <section>
    <h2>Duyuru</h2>
    ${announcementBox}
    <form class="ann" method="post" action="/api/announcement">
      <textarea name="message" maxlength="500" placeholder="Mesaj (ör. 'Yarın 02:00-04:00 arası bakım yapılacaktır')"></textarea>
      <div class="ann-targets">
        <input name="target_username" maxlength="120" placeholder="Hedef hesap (kullanıcı adı) — boş = herkese">
        <input name="target_device_id" maxlength="128" placeholder="Hedef cihaz ID — boş = herkese">
      </div>
      <button class="sendbtn" type="submit">Duyuruyu yayınla</button>
      <div class="muted" style="margin-top:6px">Uygulama her cihaza yalnızca <b>en son yayınlanan</b> ilgili mesajı gösterir. Bu yüzden hedefli duyuruyu, genel duyurudan <b>sonra</b> yayınlayın — aksi halde sonradan yayınlanan genel duyuru hedefli mesajı gölgeler.</div>
    </form>
  </section>
  <section>
    <h2>İstekler &amp; Şikayetler ${pendingRequests ? `<span class="count-badge req-pending">${pendingRequests} bekliyor</span>` : ""}</h2>
    ${requestsSection}
  </section>
  <section>
    <h2>Canlı Cihazlar <span class="count-badge">${onlineCount} çevrimiçi</span></h2>
    ${versionChips ? `<div style="margin-bottom:12px">${versionChips}</div>` : ""}
    ${deviceSection}
  </section>
  <section>
    <h2>Şu an en çok izlenen kanallar</h2>
    ${topChannelsSection}
  </section>
  <section>
    <h2>Hesaplar &amp; Paylaşım</h2>
    ${accountsSection}
  </section>
  <section>
    <h2>Çökme Özeti</h2>
    ${crashSummarySection || '<div class="empty">Henüz çökme yok.</div>'}
  </section>
  <section>
    <h2>Çökme Raporları</h2>
    ${rows.length ? cards : '<div class="empty">Henüz çökme raporu yok.</div>'}
  </section>
</main>
</body></html>`);
});

// ---- Per-device detail page: crash / nettest / watch history ----
app.get("/device/:id", auth, async (req, res) => {
  const deviceId = clip(req.params.id, 128);
  const [devRes, crashRes, nettestRes, watchRes, requestsRes] = await Promise.all([
    pool.query(
      `SELECT *, (last_seen > now() - interval '${ONLINE_WINDOW_SEC} seconds') AS online
         FROM devices WHERE device_id = $1`,
      [deviceId],
    ),
    pool.query(
      `SELECT * FROM crash_reports WHERE device_id = $1 ORDER BY received_at DESC LIMIT 100`,
      [deviceId],
    ),
    pool.query(
      `SELECT * FROM nettest_log WHERE device_id = $1 ORDER BY at DESC LIMIT 50`,
      [deviceId],
    ),
    pool.query(
      `SELECT * FROM watch_log WHERE device_id = $1 ORDER BY at DESC LIMIT 100`,
      [deviceId],
    ),
    pool.query(
      `SELECT * FROM requests WHERE device_id = $1 ORDER BY created_at DESC LIMIT 50`,
      [deviceId],
    ),
  ]);
  const d = devRes.rows[0];
  const pageStyle = `
  :root { color-scheme: dark; }
  body { margin:0; background:#0E1116; color:#F5F7FA; font:14px/1.5 system-ui, sans-serif; }
  header { padding:20px 24px; border-bottom:1px solid #1C232D; }
  a.back { color:#3DA9FC; text-decoration:none; font-size:13px; }
  h1 { margin:8px 0 4px; font-size:20px; }
  h2 { font-size:15px; margin:0 0 12px; }
  main { padding:16px 24px; max-width:1000px; }
  section { margin-bottom:28px; }
  .empty { color:#5B6877; padding:16px 0; }
  .muted { color:#5B6877; font-size:12px; }
  .mono { font-family:monospace; }
  .strong { font-weight:600; }
  .dot { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:7px; vertical-align:middle; }
  .dot.on { background:#2FBF71; box-shadow:0 0 0 3px rgba(47,191,113,.18); }
  .dot.off { background:#5B6877; }
  .info { display:grid; grid-template-columns:repeat(auto-fit,minmax(200px,1fr)); gap:12px; background:#161B22; border:1px solid #1C232D; border-radius:12px; padding:16px; }
  .info .k { color:#9AA7B4; font-size:12px; }
  .info .v { font-size:14px; }
  table { width:100%; border-collapse:collapse; background:#161B22; border:1px solid #1C232D; border-radius:12px; overflow:hidden; }
  thead th { text-align:left; font-size:12px; color:#9AA7B4; padding:10px 12px; border-bottom:1px solid #1C232D; background:#12171E; }
  tbody td { padding:9px 12px; border-bottom:1px solid #161B22; font-size:13px; vertical-align:top; }
  tbody tr:last-child td { border-bottom:none; }
  .play { color:#FFB300; }
  details summary { cursor:pointer; color:#3DA9FC; font-size:12px; }
  pre { background:#0E1116; border:1px solid #1C232D; border-radius:8px; padding:12px; overflow:auto; max-height:360px; font-size:11px; color:#9AA7B4; white-space:pre-wrap; word-break:break-word; }`;
  if (!d) {
    return res.status(404).send(`<!doctype html><html lang="tr"><head><meta charset="utf-8">
<title>Cihaz bulunamadı</title><style>${pageStyle}</style></head><body>
<header><a class="back" href="/">← Panele dön</a><h1>Cihaz bulunamadı</h1>
<div class="muted mono">${esc(deviceId)}</div></header></body></html>`);
  }
  const name =
    `${d.manufacturer || ""} ${d.model || ""}`.trim() || "Bilinmeyen cihaz";
  const geo = [d.geo_city, d.geo_country].filter(Boolean).join(", ") || "—";
  const watchRows = watchRes.rows.length
    ? `<table><thead><tr><th>Zaman</th><th>İçerik</th><th>Tür</th></tr></thead><tbody>${watchRes.rows
        .map(
          (w) => `<tr>
        <td title="${esc(fmt(w.at))}">${esc(ago(w.at))}<div class="muted">${esc(fmt(w.at))}</div></td>
        <td class="play">▶ ${esc(w.title || "—")}</td>
        <td class="muted">${esc(w.kind || "—")}</td>
      </tr>`,
        )
        .join("")}</tbody></table>`
    : '<div class="empty">İzleme geçmişi yok.</div>';
  const nettestRows = nettestRes.rows.length
    ? `<table><thead><tr><th>Zaman</th><th>Sonuç</th></tr></thead><tbody>${nettestRes.rows
        .map(
          (n) => `<tr>
        <td title="${esc(fmt(n.at))}">${esc(ago(n.at))}<div class="muted">${esc(fmt(n.at))}</div></td>
        <td>${esc(n.summary || "—")}</td>
      </tr>`,
        )
        .join("")}</tbody></table>`
    : '<div class="empty">Ağ testi geçmişi yok.</div>';
  const crashRows = crashRes.rows.length
    ? `<table><thead><tr><th>Zaman</th><th>Sürüm</th><th>Hata</th></tr></thead><tbody>${crashRes.rows
        .map((r) => {
          const firstLine = (r.message || "").split("\n")[0] || "(mesaj yok)";
          return `<tr>
        <td title="${esc(fmt(r.received_at))}">${esc(ago(r.received_at))}<div class="muted">${esc(fmt(r.received_at))}</div></td>
        <td>${versionLabel(r.app_version, r.version_code)}</td>
        <td><div class="play mono">${esc(firstLine)}</div>
          <details><summary>Tam kaydı göster</summary><pre>${esc(r.log || "(kayıt yok)")}</pre></details>
        </td>
      </tr>`;
        })
        .join("")}</tbody></table>`
    : '<div class="empty">Çökme geçmişi yok.</div>';
  const REQ_LABEL = {
    channel: "📺 Canlı kanal",
    movie: "🎬 Film",
    series: "📺 Dizi",
    complaint: "⚠️ Şikayet",
    other: "📝 İstek",
  };
  const requestRows = requestsRes.rows.length
    ? `<table><thead><tr><th>Zaman</th><th>Tür</th><th>Mesaj</th><th>Durum</th></tr></thead><tbody>${requestsRes.rows
        .map(
          (r) => `<tr>
        <td title="${esc(fmt(r.created_at))}">${esc(ago(r.created_at))}<div class="muted">${esc(fmt(r.created_at))}</div></td>
        <td>${esc(REQ_LABEL[r.type] || REQ_LABEL.other)}</td>
        <td style="white-space:pre-wrap;word-break:break-word">${esc(r.message)}</td>
        <td>${r.status === "done" ? '<span class="muted">Çözüldü</span>' : '<span class="play">Bekliyor</span>'}</td>
      </tr>`,
        )
        .join("")}</tbody></table>`
    : '<div class="empty">İstek / şikayet yok.</div>';
  res.send(`<!doctype html>
<html lang="tr"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(name)} — Cihaz detayı</title>
<style>${pageStyle}</style></head>
<body>
<header>
  <a class="back" href="/">← Panele dön</a>
  <h1><span class="dot ${d.online ? "on" : "off"}"></span>${esc(name)}</h1>
  <div class="muted mono">${esc(d.device_id)}</div>
</header>
<main>
  <section>
    <div class="info">
      <div><div class="k">Durum</div><div class="v">${d.online ? "Çevrimiçi" : "Çevrimdışı"}</div></div>
      <div><div class="k">Kullanıcı</div><div class="v">${d.username ? esc(d.username) : "—"}</div></div>
      <div><div class="k">IP</div><div class="v mono">${esc(d.ip || "—")}</div></div>
      <div><div class="k">Konum</div><div class="v">${esc(geo)}</div></div>
      <div><div class="k">Sürüm</div><div class="v">${versionLabel(d.app_version, d.version_code)}</div></div>
      <div><div class="k">Android</div><div class="v">Android ${esc(d.android_version || "?")}${d.api_level ? " · API " + esc(d.api_level) : ""}</div></div>
      <div><div class="k">Şu an izliyor</div><div class="v">${d.online && d.now_playing ? `<span class="play">▶ ${esc(d.now_playing)}</span>` : "—"}</div></div>
      <div><div class="k">İlk görülme</div><div class="v">${esc(fmt(d.first_seen))}</div></div>
      <div><div class="k">Son görülme</div><div class="v">${esc(ago(d.last_seen))}</div></div>
    </div>
  </section>
  <section>
    <h2>Bu cihaza mesaj gönder</h2>
    <form method="post" action="/api/announcement" style="display:flex;gap:8px;flex-wrap:wrap;align-items:flex-start">
      <input type="hidden" name="target_device_id" value="${esc(d.device_id)}">
      <textarea name="message" maxlength="500" placeholder="Yalnızca bu cihaza gösterilecek mesaj" style="flex:1;min-width:280px;background:#0E1116;color:#F5F7FA;border:1px solid #2A3340;border-radius:8px;padding:10px;min-height:54px;resize:vertical;font:13px system-ui,sans-serif"></textarea>
      <button type="submit" style="background:#1f5a35;color:#D6FBE5;border:1px solid #2FBF71;padding:9px 18px;border-radius:8px;cursor:pointer">Gönder</button>
    </form>
  </section>
  <section><h2>İstek &amp; Şikayet geçmişi</h2>${requestRows}</section>
  <section><h2>İzleme geçmişi</h2>${watchRows}</section>
  <section><h2>Ağ testi geçmişi</h2>${nettestRows}</section>
  <section><h2>Çökme geçmişi</h2>${crashRows}</section>
</main>
</body></html>`);
});

initDb()
  .then(() => {
    app.listen(PORT, "0.0.0.0", () =>
      console.log(`[crash-receiver] listening on :${PORT}`),
    );
    if (TELEGRAM_BOT_TOKEN && TELEGRAM_CHAT_ID) {
      setTimeout(checkSharingAlerts, 30 * 1000);
      setInterval(checkSharingAlerts, 10 * 60 * 1000);
    }
    runRetention();
    setInterval(runRetention, 24 * 3600 * 1000);
  })
  .catch((e) => {
    console.error("[crash-receiver] DB init failed", e);
    process.exit(1);
  });
