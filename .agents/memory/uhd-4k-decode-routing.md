---
name: UHD/4K decode routing
description: Why 4K must use the hardware decoder, and the resolution-adaptive escalation on both live and VOD paths.
---

# UHD/4K must hardware-decode

**Rule:** The default DecoderMode is SOFTWARE (to dodge Amlogic chroma-green on high-bitrate 1080p), but no TV-box CPU can software-decode UHD/4K at 80–100 Mbps in real time — it stutters badly. So genuine UHD (height ≥ 1440 OR width ≥ 2560) must be escalated to the hardware decoder, while SD/HD stays on the safe software/default path.

**Why:** Software-default was chosen deliberately for the green-screen problem (see android-green-screen + decoder-default-auto notes). But that same default makes 4K unplayable. The fix is resolution-adaptive: keep software for SD/HD, force hardware only for true 4K.

**How to apply:**
- The green-prone HW→SW downgrade must EXCLUDE UHD (`is1080pClass = !isUhd && ...`). Without the upper bound, 4K@≥49fps on hardware gets wrongly downgraded to software and stutters.
- Live path: the engine emits a "software too slow" signal when `forceSoftware && isUhd`; the controller treats it as an UPGRADE (VLC_SW→VLC_HW) that runs regardless of decoderMode (opposite direction of the green downgrade ladder). The `triedStages` guard already prevents SW↔HW oscillation.
- VOD path is a SEPARATE libVLC setup with no controller/ladder — it must mirror this itself: detect UHD on the Playing event and rebuild the player on hardware, resuming at the captured position (one-shot guard). Tear down from inside the event callback only via a deferred post, never synchronously.
- Residual tradeoff: 4K via hardware may green-tint on some Amlogic boxes — accepted, since software-decoded 4K is unwatchable anyway.
