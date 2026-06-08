---
name: VLC live-TS frame rate populates late
description: fps-gated green-prone routing must re-check; track frame rate is often 0 on the first Playing/Vout for live streams
---

For live TS streams libVLC frequently reports `frameRateDen = 0` (frame rate unknown) on the **first** `Playing`/`Vout` event. The track frame rate populates a moment later (the diagnostics overlay reads the real fps because it polls on a timer).

**Why it matters:** the green-prone HW→SW routing (`VlcPlayerEngine.maybeRouteByProfile`) gates 1080p50/60 H.264 on `fps >= 49`. A single early read sees fps=0, the gate fails, and the stream stays stuck on the failing Amlogic HW decoder (`OMX.amlogic.avc.decoder.awesome2` in a `dequeue_in timeout` reset loop) = green picture with working audio. Symptom: ONLY 1080p@50fps (often MP2 audio, so it arrives via EXO→VLC_HW fallback) greens; everything else is fine.

**How to apply:** any resolution/fps-keyed routing on libVLC must re-evaluate until the metadata is available — don't trust the first event. Re-run on delayed handlers after start AND on each `Buffering(100%)` top-up (long tail), with a one-shot claim.

**Thread-safety:** the re-check runs on the main thread while VLC events fire on the native thread, so the one-shot "already routed" flag must be an `AtomicBoolean.compareAndSet`, or you can emit a duplicate fallback. Cancel pending re-checks on claim/play/stop/release.

**Don't drop the fps gate:** 1080i25 broadcast reports ~25fps and decodes fine on HW; routing all 1080p to software would needlessly stutter it. The fps threshold is what separates progressive 1080p50/60 from interlaced 1080i25.
