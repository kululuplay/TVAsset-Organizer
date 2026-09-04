"use strict";
const crypto = require("node:crypto");
const { promisify } = require("node:util");
const scrypt = promisify(crypto.scrypt);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SECRET = /^[A-Za-z0-9_-]{43}$/;
const TYPES = new Set(["diagnostic", "channel", "movie", "series", "complaint"]);
const STATUSES = new Set(["new", "reviewing", "done"]);
const hash = (value) => crypto.createHash("sha256").update(String(value)).digest("hex");
const equal = (a, b) => crypto.timingSafeEqual(Buffer.from(hash(a), "hex"), Buffer.from(hash(b), "hex"));

function redact(value) {
  return String(value || "")
    .replace(/(https?:\/\/)[^/@\s]+:[^/@\s]+@/gi, "$1<redacted>@")
    .replace(/(\/(?:live|movie|series)\/)[^/\s?#]+\/[^/\s?#]+\//gi, "$1<redacted>/<redacted>/")
    .replace(/([?&](?:username|user|password|pass|token|access_token|refresh_token|api_key|apikey|key)=)[^&#\s]+/gi, "$1<redacted>")
    .replace(/\b((?:authorization|proxy-authorization|x-kululu-key|cookie|set-cookie)\s*:\s*).+$/gim, "$1<redacted>")
    .replace(/("(?:username|user|password|pass|token|secret|authorization|cookie|access_token|refresh_token|api_key|apikey|key)"\s*:\s*")[^"]*(")/gi, "$1<redacted>$2")
    .replace(/\bBearer\s+[A-Za-z0-9._~+\/-]+=*/gi, "Bearer <redacted>")
    .replace(/\b(password|passwd|token|secret|api[_-]?key)\s*[:=]\s*[^\s,;]+/gi, "$1=<redacted>")
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, "");
}

function ticketPayload(input) {
  if (!input || typeof input.requestId !== "string" || !UUID.test(input.requestId) || !TYPES.has(input.type)) return null;
  if (typeof input.message !== "string" || !input.message.trim() || input.message.length > (input.type === "diagnostic" ? 5000 : 500)) return null;
  if (input.log != null && (input.type !== "diagnostic" || typeof input.log !== "string" || Buffer.byteLength(input.log) > 131072)) return null;
  const metadata = {};
  const fields = { manufacturer: 64, model: 96, androidVersion: 32, appVersion: 32, engine: 32, transport: 32, decoder: 32, buffer: 32 };
  for (const [key, max] of Object.entries(fields)) {
    const value = input.metadata?.[key];
    if (typeof value === "string") metadata[key] = redact(value).slice(0, max);
  }
  for (const key of ["apiLevel", "versionCode"]) {
    const value = input.metadata?.[key];
    if (Number.isSafeInteger(value) && value > 0 && value <= 2147483647) metadata[key] = value;
  }
  const logBytes = Buffer.from(redact(input.log));
  let start = Math.max(0, logBytes.length - 131072);
  while (start < logBytes.length && (logBytes[start] & 0xc0) === 0x80) start++;
  return { requestId: input.requestId.toLowerCase(), type: input.type, message: redact(input.message.trim()).slice(0, input.type === "diagnostic" ? 5000 : 500), log: logBytes.subarray(start).toString("utf8"), metadata };
}

class RateLimit {
  constructor(maxKeys = 10000, clock = Date.now) { this.rows = new Map(); this.maxKeys = maxKeys; this.clock = clock; }
  take(key, limit, windowMs) {
    const now = this.clock();
    let row = this.rows.get(key);
    if (!row || row.until <= now) {
      if (this.rows.size >= this.maxKeys) {
        for (const [id, value] of this.rows) if (value.until <= now) this.rows.delete(id);
        if (this.rows.size >= this.maxKeys && !this.rows.has(key)) return false;
      }
      row = { count: 0, until: now + windowMs }; this.rows.set(key, row);
    }
    return ++row.count <= limit;
  }
}

async function passwordHash(password, salt = crypto.randomBytes(16).toString("hex")) {
  return `scrypt$${salt}$${(await scrypt(password, salt, 64)).toString("hex")}`;
}
async function verifyPassword(password, expected) {
  if (typeof password !== "string" || password.length > 256) return false;
  if (!/^scrypt\$[a-f0-9]{32}\$[a-f0-9]{128}$/.test(expected || "")) return false;
  const [, salt] = expected.split("$");
  return equal(await passwordHash(password, salt), expected);
}

module.exports = { UUID, SECRET, TYPES, STATUSES, hash, equal, redact, ticketPayload, RateLimit, passwordHash, verifyPassword };
