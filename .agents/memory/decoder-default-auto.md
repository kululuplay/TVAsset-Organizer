---
name: Decoder default = SOFTWARE
description: Why the app's default DecoderMode is SOFTWARE, and the two-kinds-of-green tradeoff behind it.
---

Default `DecoderMode` (the `fromName(null)` fallback) is **SOFTWARE**.

**Why:** there are TWO distinct green-screen failures and they pull in opposite
directions:
- *No-frame green* (decoder stuck, zero pictures): DETECTABLE. ExoPlayer's
  "READY but no first frame within timeout" watchdog fires `onVideoInvalid` ->
  ladder falls back. This is the only green a watchdog can catch.
- *Wrong-colour-plane green* (frames DO arrive but in the wrong chroma, e.g.
  Amlogic NV21/NV12 mismatch on Xiaomi Stick): UNDETECTABLE. libVLC reports a
  healthy decode (`output: 21 Biplanar ... 1280x720`), `onRenderedFirstFrame`
  fires, pictures keep incrementing, and the opaque HW SurfaceView can't be read
  back. Audio plays fine; user sees a green picture with a faint ghost of the
  real image. NO software signal distinguishes it from good playback.

AUTO/HARDWARE start on the chip decoder, so the second kind of green hits some
boxes with no possible auto-recovery. SOFTWARE decodes to I420 on the CPU, which
every TV sink displays correctly -> no green anywhere out of the box.

**Tradeoff:** SOFTWARE's only downside is possible macroblocking/stutter on
high-bitrate channels on weak sticks (Firestick-class), already softened by
`--avcodec-skiploopfilter=nonref` + `--avcodec-fast`. Shipping AUTO to dodge
that macroblocking surfaced chroma-green on Amlogic boxes, so the default went
back to SOFTWARE: a green screen looks totally broken, while macroblocking still
shows a usable picture.

**How to apply:** the default only affects users who never picked a decoder in
Settings; an explicitly persisted choice is preserved. Users on weak sticks can
opt into Auto/Hardware. Keep the implicit fallback consistent everywhere: the
`PlayerController` constructor default must also be SOFTWARE, not AUTO, or a call
site that omits `decoderMode` (e.g. MiniPlayerView) silently reintroduces green.
Do NOT try to "fix" chroma-green with a VLC watchdog — it cannot be detected;
only forcing software decode (or a per-device chroma override, which RV32 already
proved freezes Amlogic) avoids it.
