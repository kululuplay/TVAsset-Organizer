/*
 * Kululu IPTV — crash receiver
 * A tiny Express service that the Android app silently POSTs crash reports to on
 * the next launch after a crash. Reports are stored in Postgres and viewed
 * through a password-protected web panel. Works on any device with internet
 * (no Google Play Services needed — important for Fire TV sticks).
 *
 * Endpoints:
 *   POST /api/crash            ingest (app -> server), guarded by X-Kululu-Key
 *   GET  /                     HTML panel (HTTP Basic auth)
 *   GET  /api/crashes          JSON list (auth)
 *   POST /api/crashes/:id/delete   delete one (auth)
 *   POST /api/crashes/clear        delete all (auth)
 *   GET  /healthz              health check
 */
const express = require("express");
const { Pool } = require("pg");

const app = express();
const PORT = process.env.PORT || 5000;

// Shared key the app stamps on every report. NOT a real secret (it ships inside
// the APK and is extractable) — it only deters casual spam. Rotate by setting
// CRASH_INGEST_KEY on the server AND in the app's CrashReporter if abused.
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

// ---- Ingest (app -> server) ----
app.post("/api/crash", async (req, res) => {
  if ((req.get("X-Kululu-Key") || "") !== INGEST_KEY) {
    return res.status(401).json({ error: "unauthorized" });
  }
  const b = req.body || {};
  try {
    await pool.query(
      `INSERT INTO crash_reports
        (occurred_at, app_version, version_code, manufacturer, model, device,
         android_version, api_level, message, log)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
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
      ],
    );
    forwardTelegram(b).catch(() => {});
    return res.status(204).end();
  } catch (e) {
    console.error("insert failed", e);
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

app.get("/", auth, async (req, res) => {
  const { rows } = await pool.query(
    `SELECT * FROM crash_reports ORDER BY received_at DESC LIMIT 300`,
  );
  const dayAgo = Date.now() - 24 * 3600 * 1000;
  const last24 = rows.filter((r) => new Date(r.received_at).getTime() > dayAgo).length;
  const devices = new Set(
    rows.map((r) => `${r.manufacturer || ""} ${r.model || ""}`.trim()),
  ).size;

  const cards = rows
    .map((r) => {
      const firstLine = (r.message || "").split("\n")[0] || "(mesaj yok)";
      return `
      <div class="card">
        <div class="meta">
          <span class="dev">${esc(r.manufacturer)} ${esc(r.model)}</span>
          <span class="pill">Android ${esc(r.android_version)}</span>
          <span class="pill">v${esc(r.app_version)}${r.version_code ? " (" + esc(r.version_code) + ")" : ""}</span>
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
  header { padding:20px 24px; border-bottom:1px solid #1C232D; position:sticky; top:0; background:#0E1116; }
  h1 { margin:0 0 8px; font-size:20px; }
  .stats { color:#9AA7B4; font-size:13px; }
  .stats b { color:#3DA9FC; }
  .actions { margin-top:12px; }
  .actions a, .clearbtn { background:#1C232D; color:#F5F7FA; border:1px solid #2A3340; padding:8px 14px; border-radius:8px; text-decoration:none; cursor:pointer; font-size:13px; }
  .clearbtn { color:#E0533D; }
  main { padding:16px 24px; max-width:1100px; }
  .empty { color:#5B6877; padding:40px 0; text-align:center; }
  .card { background:#161B22; border:1px solid #1C232D; border-radius:12px; padding:14px 16px; margin-bottom:12px; }
  .meta { display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
  .dev { font-weight:600; }
  .pill { background:#1C232D; color:#9AA7B4; padding:2px 8px; border-radius:20px; font-size:12px; }
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
  <h1>Kululu IPTV — Çökme Raporları</h1>
  <div class="stats">
    Toplam <b>${rows.length}</b> · Son 24 saat <b>${last24}</b> · Farklı cihaz <b>${devices}</b>
  </div>
  <div class="actions">
    <a href="/">↻ Yenile</a>
    <form method="post" action="/api/crashes/clear" style="display:inline" onsubmit="return confirm('Tüm raporlar silinsin mi?')">
      <button class="clearbtn" type="submit">Tümünü temizle</button>
    </form>
  </div>
</header>
<main>
  ${rows.length ? cards : '<div class="empty">Henüz çökme raporu yok.</div>'}
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
