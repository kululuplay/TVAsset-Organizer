---
name: Decoder default = AUTO
description: Why the app's default DecoderMode is AUTO (not SOFTWARE), and the green-screen tradeoff behind it.
---

Default `DecoderMode` (the `fromName(null)` fallback) is **AUTO**, not SOFTWARE.

**Why:** SOFTWARE-as-default decoded every stream on the CPU. On low-power
sticks (Firestick-class) high-bitrate channels couldn't keep up -> dropped
frames / macroblocking ("rain"/breakup) with audio still fine. Device logs
confirmed channels opening at `start=VLC_SW` / `forceSoftware=true`. AUTO is
hardware-first (VLC_HW, or Exo per PlayerMode) with automatic fallback to
software libVLC on green/blank/error, so most channels use the chip decoder and
the problematic ones still recover.

**History/tradeoff:** SOFTWARE was once made the default specifically to dodge
green-screen on a problematic Amlogic box. The reason AUTO is now safe-enough:
on that device AUTO starts directly on VLC_HW, avoiding the EXO->VLC swap that
caused green. BUT there is no reliable VLC-side green/frozen-frame watchdog
(VLC_HW only auto-falls back on `EncounteredError`), so a silent green frame on
VLC_HW may not self-heal on some devices.

**How to apply:** the default only affects users who never explicitly picked a
decoder in Settings; an explicitly persisted SOFTWARE choice is preserved. If
green-screen-without-error reports resurface on the AUTO default, add a VLC vout/
no-first-frame watchdog rather than reverting the default to SOFTWARE.
