---
name: Comparative peering network test
description: How the in-app Settings→Diagnostics network test decides peering-toward-us vs customer-line, and why it's built on TCP-connect not ICMP.
---

# Comparative peering / network-quality test

A Settings→Diagnostics ~3-min test that probes OUR server alongside two anycast
anchors (Cloudflare 1.1.1.1:443, Google 8.8.8.8:443) and decides whether a
streaming problem is on the route toward us or on the customer's own line.

**Why TCP-connect, not ping/MTR:** a non-root Android TV box can't send raw ICMP,
and the server host blocks ICMP — there is NO per-hop traceroute/MTR available. A
TCP connect to an OPEN port costs exactly one round trip (SYN→SYN/ACK), so its
timing is a sound latency proxy and a connect timeout is a usable loss signal,
all with stock `java.net`, no permission, no native code.

**Discriminators — loss% and jitter, NOT raw RTT.** Anchors are anycast (often
<10ms to the nearest PoP) while our server is one distant host, so raw RTT ALWAYS
favours the anchors. Baseline = the BEST anchor.
- **≥800ms connect = loss**, not a (terrible) good RTT — it only completed after a
  TCP SYN retransmit (Linux RTO ~1s); counting it as RTT poisons avg/jitter.
- **SocketTimeout = loss** (path). **ConnectException (RST) = "refused" =** host
  reachable but service down — NOT path loss; drives a separate SERVER_DOWN verdict.

**Verdict thresholds** (need ≥30 attempts on the server, else INCONCLUSIVE; and a
best anchor with ≥30 samples, else INCONCLUSIVE):
- CUSTOMER_LINE: best anchor loss≥5% or jitter≥40ms (even the top anchor is bad).
- anchorHealthy gate = best anchor loss<2% AND jitter<15ms.
- SERVER_DOWN: anchors healthy, server samples==0 and refused≥~15.
- PEERING: anchors healthy AND (server loss≥5% / jitter≥40ms OR server avg >
  bestAnchor+120ms AND >2.5× bestAnchor).
- else OK.

**Coroutine shape that matters:** `coroutineScope` returns its LAST expression
immediately, so you must `probeJobs.joinAll()` BEFORE computing the final
snapshot, then cancel the progress emitter — otherwise you return an empty
early snapshot. Each target runs its own once-per-second `launch` loop (not
async/awaitAll per second). Tie the blocking `Socket.connect` to the Job via
`invokeOnCompletion { close() }` so cancel aborts a blocked connect promptly.

**UI/TV:** keep the busy peering button ENABLED (disabling a focused button steals
D-pad focus); block re-entry with a flag instead. FLAG_KEEP_SCREEN_ON for the run,
cleared in finally. Cancel the job in onStop. Result is uploaded to the
crash-receiver `/api/nettest` (upsert one line per device) and shown in the panel.

**Why:** customers blame the IPTV service for what is often their own Wi-Fi/line;
this gives a defensible, comparative answer without root or ICMP.
