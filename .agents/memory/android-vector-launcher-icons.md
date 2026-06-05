---
name: API21-safe vector launcher icons
description: Why launcher/icon VectorDrawables must avoid inline gradients on minSdk21.
---

# Launcher icons must use flat fills on minSdk 21

The system (PackageManager / launcher) renders the app icon with the **framework**
`VectorDrawable`, NOT AndroidX `VectorDrawableCompat`. Inline gradient fills
(`<aapt:attr name="android:fillColor"><gradient .../>`) only render dependably on
**API 24+** in the framework. On API 21–23 the gradient silently fails.

**Rule:** any drawable rendered by the system as an icon (e.g. `@drawable/ic_launcher`,
adaptive backgrounds) must use **flat solid fills** when minSdk is 21. To fake depth,
stack solid paths and use `fillAlpha` / `strokeAlpha` (both supported on framework
VectorDrawable since API 21). A two-tone diagonal wedge reads as a gradient without one.

**Why:** caught in code review for the Kululu IPTV icon — the gradient tile would
not have shown on older Android TV sticks.

**How to apply:** GradientDrawable (`<shape><gradient>` in a layer-list, e.g. the TV
banner) is fine because it's not a VectorDrawable. The restriction is only on
gradients *inside* `<vector>` that the system draws. App-drawn vectors (loaded via
AppCompat into ImageViews) can use gradients since they go through the compat lib.
