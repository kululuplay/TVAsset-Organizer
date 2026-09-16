"use strict";

/*
 * Small, dependency-free request-hardening helpers for the crash receiver:
 *   - BoundedMap:        insertion-ordered Map with a hard size cap (LRU-ish
 *                        eviction of the oldest entry) + predicate sweep.
 *   - createRateLimiter: fixed-budget token bucket per key, bounded key map.
 *   - createCsrf:        per-process token bound to the admin credential.
 *   - sameOrigin:        Origin/Referer + Sec-Fetch-Site check for admin forms.
 *   - normalizeIp:       strips the IPv4-mapped prefix and rejects garbage.
 *   - maskAccount:       short masked account label for out-of-band alerts.
 *   - parseTrustProxyHops
 * Everything here is pure / in-memory so it can be unit-tested without a DB.
 */

const crypto = require("node:crypto");
const net = require("node:net");

class BoundedMap extends Map {
  constructor(max = 5000) {
    super();
    this.max = Math.max(1, max | 0);
  }
  set(key, value) {
    // Re-inserting moves the key to the newest position (LRU on write).
    if (super.has(key)) super.delete(key);
    super.set(key, value);
    while (this.size > this.max) {
      const oldest = this.keys().next().value;
      super.delete(oldest);
    }
    return this;
  }
  /** Delete every entry for which `stale(value, key)` returns true. */
  sweep(stale) {
    let n = 0;
    for (const [key, value] of this) {
      if (stale(value, key)) {
        super.delete(key);
        n += 1;
      }
    }
    return n;
  }
}

/**
 * Token bucket: each key gets `max` tokens per `windowMs`, refilled
 * continuously. `check(key)` consumes one token when available.
 * Returns { ok, retryAfterSec, remaining }.
 */
function createRateLimiter({ max, windowMs, maxKeys = 5000, now = Date.now }) {
  if (!(max > 0) || !(windowMs > 0)) throw new Error("rate limiter needs max>0 and windowMs>0");
  const buckets = new BoundedMap(maxKeys);
  const refillPerMs = max / windowMs;
  const check = (key) => {
    const t = now();
    const id = String(key || "");
    let b = buckets.get(id);
    if (!b) {
      b = { tokens: max, at: t };
    } else {
      b.tokens = Math.min(max, b.tokens + (t - b.at) * refillPerMs);
      b.at = t;
    }
    if (b.tokens >= 1) {
      b.tokens -= 1;
      buckets.set(id, b);
      return { ok: true, retryAfterSec: 0, remaining: Math.floor(b.tokens) };
    }
    buckets.set(id, b);
    const retryAfterSec = Math.max(1, Math.ceil((1 - b.tokens) / refillPerMs / 1000));
    return { ok: false, retryAfterSec, remaining: 0 };
  };
  // Drop buckets that have fully refilled: they carry no state worth keeping.
  const sweep = () => {
    const t = now();
    return buckets.sweep((b) => t - b.at >= windowMs);
  };
  return { check, sweep, buckets };
}

function timingSafeEqualStr(a, b) {
  const ha = crypto.createHash("sha256").update(String(a ?? ""), "utf8").digest();
  const hb = crypto.createHash("sha256").update(String(b ?? ""), "utf8").digest();
  // Hashing first makes both sides fixed-length, so the compare never leaks
  // length and never throws on mismatched buffers.
  return crypto.timingSafeEqual(ha, hb);
}

/**
 * CSRF token bound to the admin credential: HMAC(credentialHash, processNonce).
 * A fresh nonce per process means a leaked token dies on restart; binding to the
 * credential means rotating the password invalidates it too. One token per
 * process is enough for a single-operator HTTP Basic panel (there is no
 * session to bind to).
 */
function createCsrf(credentialKey, nonce = crypto.randomBytes(32)) {
  const token = crypto
    .createHmac("sha256", credentialKey)
    .update(nonce)
    .digest("hex");
  return {
    token,
    field: `<input type="hidden" name="_csrf" value="${token}">`,
    verify: (candidate) =>
      typeof candidate === "string" &&
      candidate.length === token.length &&
      timingSafeEqualStr(candidate, token),
  };
}

function hostOf(url) {
  try {
    return new URL(String(url)).hostname.toLowerCase();
  } catch {
    return null;
  }
}

/**
 * Same-origin check for state-changing admin requests.
 * `headers` is a lookup function (name -> value | undefined), `hostname` the
 * server's own hostname (Express req.hostname, port already stripped).
 * Returns null when accepted, otherwise a short rejection reason.
 */
function sameOrigin({ header, hostname }) {
  const fetchSite = String(header("sec-fetch-site") || "").toLowerCase();
  if (fetchSite === "cross-site") return "cross_site";
  const origin = header("origin");
  const referer = header("referer");
  const source = origin && origin !== "null" ? origin : referer;
  if (!source) return "missing_origin";
  const host = hostOf(source);
  const self = String(hostname || "").toLowerCase();
  if (!host || !self || host !== self) return "origin_mismatch";
  return null;
}

/** Strip the IPv4-mapped-IPv6 prefix and reject anything that is not an IP. */
function normalizeIp(raw) {
  let ip = String(raw || "").trim();
  if (ip.startsWith("::ffff:") && net.isIPv4(ip.slice(7))) ip = ip.slice(7);
  // Drop a zone id on link-local v6 (fe80::1%eth0) before validating.
  const zone = ip.indexOf("%");
  if (zone > 0) ip = ip.slice(0, zone);
  return net.isIP(ip) ? ip : null;
}

/** "kululu_user" -> "ku***" ; short/blank -> "***". */
function maskAccount(v) {
  const s = String(v ?? "").trim();
  if (!s) return "***";
  return `${s.slice(0, 2)}***`;
}

function parseTrustProxyHops(raw, def = 1) {
  const n = parseInt(raw, 10);
  return Number.isFinite(n) && n >= 0 && n <= 16 ? n : def;
}

module.exports = {
  BoundedMap,
  createRateLimiter,
  createCsrf,
  sameOrigin,
  normalizeIp,
  maskAccount,
  parseTrustProxyHops,
  timingSafeEqualStr,
};
