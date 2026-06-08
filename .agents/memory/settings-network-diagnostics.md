---
name: Settings network diagnostics (speed test + public IPv4)
description: Keyless endpoint choices and lifecycle rules for the in-Settings speed test and public-IP row
---

# In-Settings network diagnostics

The Settings screen hosts two support-oriented diagnostics: a download **speed test**
section and a **public IPv4** row under General Info.

## Endpoint choices (keyless, no API key)
- **Real download speed** must stream from a CDN, not the IPTV provider origin
  (the origin can be throttled / single-connection limited and would understate
  the customer's line). Use a large CDN file and stop on a ~10s timer; divide
  bytes-read by elapsed time for Mbps.
  - Use SEVERAL geographically diverse keyless mirrors tried in order, not one or
    two. Single hosts fail per-ISP: `speed.hetzner.de` can return
    `UnknownHostException` (DNS) on some networks, and Cloudflare `__down` can be
    WAF/403-challenged — so a 2-entry list (Cloudflare + Hetzner) dooms the whole
    test for those customers. Current list: Cloudflare `__down` (anycast, first),
    `proof.ovh.net`, `speedtest.tele2.net`, `speed.hetzner.de`.
- **Public IPv4**: do NOT reuse the weather provider's `ipapi.co/json` for this —
  it can return the **IPv6** address. Use an IPv4-only plain-text endpoint and
  **validate with an IPv4 regex** before showing it (so an IPv6 reply or an HTML
  error page never leaks into the UI). Keep a fallback list of endpoints.

**Why:** support staff need the customer's home WAN IPv4 specifically, and a
trustworthy real-world speed number; a throttled origin or an IPv6 string defeats
both.

## Lifecycle rule for the speed test
A long download tied to a screen must be cancelled deterministically. Wire the
blocking OkHttp `Call.cancel()` to coroutine cancellation (via the Job's
`invokeOnCompletion`) so a blocked `read()` aborts immediately, AND restore the
button/progress/status UI in a `try/finally` so a panel-switch/onPause
cancellation never leaves the controls stuck in the "running/disabled" state.

**How to apply:** any future "run a network job from a TV settings panel" should
cancel on leaving the panel + onPause and clean its UI in finally, not only on the
success path.
