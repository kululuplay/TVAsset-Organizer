"use strict";
const fs = require("node:fs");
const path = require("node:path");
const { Pool } = require("pg");
const { PgStore } = require("./store");
const { createApp } = require("./app");
async function main() {
  const origin = process.env.SUPPORT_ORIGIN;
  if (!origin || new URL(origin).protocol !== "https:" || new URL(origin).origin !== origin) throw new Error("SUPPORT_ORIGIN must be canonical HTTPS origin");
  if (!process.env.DATABASE_URL) throw new Error("DATABASE_URL missing");
  const databaseUrl = new URL(process.env.DATABASE_URL);
  if (!["127.0.0.1", "localhost"].includes(databaseUrl.hostname)) throw new Error("Support database must be local");
  const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 5, connectionTimeoutMillis: 5000, statement_timeout: 10000 });
  await pool.query(fs.readFileSync(path.join(__dirname, "schema.sql"), "utf8"));
  const store = new PgStore(pool);
  await store.maintain();
  const maintenance = setInterval(() => store.maintain().catch(error => console.error("[support] retention failed:", error.code || error.name)), 3600000);
  maintenance.unref();
  const app = createApp({ store, origin, adminUser: process.env.SUPPORT_ADMIN_USER || "kululu", adminPasswordHash: process.env.SUPPORT_ADMIN_PASSWORD_HASH });
  const server = app.listen(Number(process.env.SUPPORT_PORT || 5086), "127.0.0.1", () => console.log("[support] ready on loopback"));
  let stopping = false;
  const stop = () => {
    if (stopping) return; stopping = true; clearInterval(maintenance);
    server.close(async () => { await pool.end(); process.exit(0); });
    setTimeout(() => process.exit(1), 10000).unref();
  };
  process.on("SIGTERM", stop); process.on("SIGINT", stop);
}
if (require.main === module) main().catch(error => { console.error("[support] startup failed:", error.code || error.message); process.exit(1); });
