---
name: Audio passthrough silence (video plays, no sound)
description: Passthrough-ON silence is runtime-undetectable; diagnose remotely via heartbeat settings snapshot + gate enabling with a warning dialog.
---

# Audio passthrough silence

Rule: when a user reports "video plays but NO sound" (typical on projectors/
Beamers and basic TVs), suspect audio passthrough ON. Passthrough sends the raw
Dolby/DTS bitstream over HDMI; a sink without a decoder silently discards it —
the player sees a healthy playing stream, so this is **undetectable at runtime**
(no error, no silent-audio heuristic fires: the bitstream track IS playing).

**Why:** operator field reports; default is passthrough OFF = PCM decode
(Exo DEFAULT_AUDIO_CAPABILITIES; VLC `--no-spdif` + `--stereo-mode=1` downmix on
both live and VOD paths), so silence means an old APK or the user enabled the
toggle.

**How to apply:**
- Remote triage: heartbeat carries `audioPassthrough` + a `playerSettings`
  summary; the ops panel shows a 🔇 passthrough warn badge in the device list
  and passthrough/player-settings rows on the device detail page (columns
  COALESCE so old APKs don't wipe values).
- Guard: enabling the toggle in Settings requires confirming a translated
  warning dialog; turning it off stays instant.
- Support answer: update APK + keep passthrough OFF unless an AV receiver /
  Dolby-capable soundbar is attached.
