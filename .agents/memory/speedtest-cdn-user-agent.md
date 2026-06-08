---
name: Speed test fails on real devices — bare app User-Agent blocked by CDN/WAF
description: Why the Settings speed test (Cloudflare download) reports "Test başarısız" on some networks even though IPTV streams work.
---

Symptom: Settings -> Hız Testi reports "Test başarısız" (speedtest_failed) on a
real device/network, while normal IPTV playback works fine.

**Why:** A global OkHttp interceptor force-stamped EVERY request with the app's
bare, non-browser User-Agent ("KULULUPLAY"). The speed test downloads from a
CDN/WAF-fronted endpoint (Cloudflare `speed.cloudflare.com/__down`); Cloudflare's
bot management challenges/403s requests with suspicious UAs depending on the
client IP/network reputation. Blocked response -> SpeedTester returns -1.0 ->
"failed". This is network/IP dependent, so it works for the dev but fails for the
user — classic "works for me" CDN-WAF trap.

**How to apply:**
- Requests to third-party CDNs must send a realistic *browser* User-Agent, not the
  app identity. Make the global UA interceptor conditional: only stamp the app UA
  when the request has no User-Agent set, so specialized callers can override.
- A single CDN can be blocked/unreachable on a customer network: give the speed
  test an ordered list of endpoints (Cloudflare + a non-Cloudflare mirror) and only
  fail when ALL fail.
- Bound worst-case "failed" latency: run the speed test on a *dedicated* OkHttp
  client (clone via newBuilder, strip the RetryInterceptor, add a hard callTimeout).
  Retries+backoff over multiple endpoints otherwise stretch a doomed test to minutes.
