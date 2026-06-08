---
name: Decoder default = AUTO
description: The app's default DecoderMode is AUTO (per explicit user request); the green-screen tradeoff that argues for SOFTWARE and why it was overridden.
---

Default `DecoderMode` (the `fromName(null)` fallback in `Models.kt`) is **AUTO**:
hardware-first with automatic software fallback on decode failure. The
`PlayerController` constructor default param is also AUTO so call sites that omit
`decoderMode` (e.g. `MiniPlayerView`) stay consistent. Real playback paths
(`PlayerActivity`, `HomeActivity` preview) pass the persisted setting explicitly.

**Why AUTO:** the user explicitly asked for engine=Auto, decoder=Auto,
buffer=Normal as the out-of-box defaults, with the user free to change them in
Settings. This overrides the earlier SOFTWARE-default decision below.

**The tradeoff that argued for SOFTWARE (still true, kept for context):** there
are TWO distinct green-screen failures pulling opposite directions:
- *No-frame green* (decoder stuck, zero pictures): DETECTABLE — ExoPlayer's
  "READY but no first frame within timeout" watchdog fires `onVideoInvalid` and
  the ladder falls back.
- *Wrong-colour-plane green* (frames DO arrive but in the wrong chroma, e.g.
  Amlogic NV21/NV12 mismatch on Xiaomi Stick): UNDETECTABLE — libVLC reports a
  healthy decode, `onRenderedFirstFrame` fires, pictures keep incrementing, the
  opaque HW SurfaceView can't be read back. Audio fine; user sees green. No
  software signal distinguishes it from good playback, so AUTO/HARDWARE can hit
  it on some boxes with no possible auto-recovery. SOFTWARE decodes to I420 on
  the CPU which every TV sink shows correctly -> no green out of the box, at the
  cost of possible macroblocking on heavy channels on weak sticks (softened by
  `--avcodec-skiploopfilter=nonref` + `--avcodec-fast`).

**How to apply:** the default only affects users who never picked a decoder; an
explicitly persisted choice is preserved. A user hitting chroma-green should be
told to switch to **Software** in Settings (it cannot be auto-detected or fixed
with a VLC watchdog; RV32 chroma override froze Amlogic). If shipping AUTO causes
green-screen complaints, the fallback is to flip both defaults (enum `fromName`
AND the `PlayerController` constructor param) back to SOFTWARE in lockstep.
