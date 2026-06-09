---
name: Gradient angle must be a multiple of 45
description: Linear-gradient android:angle that isn't ×45 throws InflateException on API≤28, crashing layout inflation on pre-Android-10 devices.
---

# Linear-gradient `android:angle` must be a multiple of 45

A `<shape>`/`GradientDrawable` linear `<gradient android:angle="...">` whose angle is
NOT a multiple of 45 (e.g. `110`) throws `XmlPullParserException: <gradient> tag
requires 'angle' attribute to be a multiple of 45` during drawable inflation on
**API ≤ 28**. When that drawable is a View's `android:background`/`foreground`, the
View constructor rethrows it as `InvocationTargetException` → `InflateException:
Error inflating class <unknown>` at the offending view's line.

**Why:** Android 10+ (API 29+) removed the restriction and tolerates arbitrary
angles, so the bug passes on modern test devices/emulators but hard-crashes older
hardware (e.g. Amazon Fire TV Stick on Android 9 / API 28) at Activity launch.
This bit us: dashboard tiles (`bg_tile_surface`, `bg_tile_hero`) used `angle="110"`
and crashed DashboardActivity on launch for ALL pre-Android-10 users.

**How to apply:** Keep every linear-gradient angle in `0/45/90/135/180/225/270/315`.
When picking colors/diagonals, snap to the nearest valid angle. Grep the whole res
tree before shipping: `rg 'android:angle' app/src/main/res` and verify none are
off-grid. Radial/sweep gradients are unaffected by this rule.
