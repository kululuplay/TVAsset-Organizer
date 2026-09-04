"use strict";
// Run locally on the VPS as root once. No credentials are written to stdout.
const fs = require("node:fs");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { passwordHash } = require("../security");
async function main() {
  const config = "/etc/kululu-support/service.env";
  if (fs.existsSync(config)) { console.log("Existing support credentials preserved."); return; }
  const query = sql => execFileSync("runuser", ["-u", "postgres", "--", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-tA"], { input: sql, stdio: ["pipe", "pipe", "pipe"] }).toString().trim();
  if (query("SELECT 1 FROM pg_roles WHERE rolname='kululu_support'") || query("SELECT 1 FROM pg_database WHERE datname='kululu_support'")) throw new Error("Existing support DB/role found without config; refusing overwrite");
  const databasePassword = crypto.randomBytes(32).toString("hex"), adminPassword = crypto.randomBytes(24).toString("base64url");
  query(`CREATE ROLE kululu_support LOGIN PASSWORD '${databasePassword}';`);
  query("CREATE DATABASE kululu_support OWNER kululu_support;");
  query("REVOKE CONNECT ON DATABASE kululu_support FROM PUBLIC; GRANT CONNECT ON DATABASE kululu_support TO kululu_support;");
  fs.writeFileSync(config, `NODE_ENV=production\nSUPPORT_ORIGIN=https://212.95.41.130:8443\nSUPPORT_PORT=5086\nDATABASE_URL=postgresql://kululu_support:${databasePassword}@127.0.0.1:5432/kululu_support\nSUPPORT_ADMIN_USER=kululu\nSUPPORT_ADMIN_PASSWORD_HASH=${await passwordHash(adminPassword)}\n`, { mode: 0o600, flag: "wx" });
  fs.writeFileSync("/etc/kululu-support/admin-access.txt", `Kululu Support Panel\nURL: https://212.95.41.130:8443\nKullanici: kululu\nParola: ${adminPassword}\n\nBu parolayi guvenli saklayin; GitHub veya musteri raporlarina eklemeyin.\n`, { mode: 0o600, flag: "wx" });
  console.log("Isolated support database and credentials created.");
}
main().catch(error => { console.error(error.message); process.exit(1); });
