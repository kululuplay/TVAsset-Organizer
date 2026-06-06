---
name: Android TV density normalization + content density lever
description: Why the TV UI looks zoomed/tiny, how to lock layout to a fixed dp design width, and how that same width controls content density.
---

Symptom: on some Android TV boxes the whole UI (all pages, every dp/sp element) looks zoomed/huge; on others tiny. Not a layout bug — the box reports an arbitrary `densityDpi` for the same physical screen.

Fix: normalize `Configuration.densityDpi` in `LocaleManager.wrap` (called from the single base activity's `attachBaseContext`; every screen extends it) so the display is always treated as `DESIGN_WIDTH_DP` dp wide:
`densityDpi = (max(widthPx, heightPx) / DESIGN_WIDTH_DP) * DisplayMetrics.DENSITY_DEFAULT` (roundToInt), then `createConfigurationContext(config)`. Use `max(width,height)` because pre-landscape the metrics can be swapped.

**Content-density lever:** DESIGN_WIDTH_DP is currently **1120f** (was 960f). A WIDER reference = more dp to lay out in = denser UI: the auto-fit poster grid (`autoFitColumns`, span = availableDp / poster_width) gains columns (960→3 cols, 1120→4 cols) and more category rows fit vertically. Density normalization scales pixels-per-dp uniformly but does NOT change dp relationships, so column count / truncation only move when DESIGN_WIDTH_DP changes — raising it is the single global knob to make the whole app less "zoomed" and fit more, matching denser competitor IPTV layouts.

**Why:** dp/sp are density-relative; a box that over-reports DPI makes dp content consume more px (zoomed). Locking to a reference width neutralizes that. Boxes already correct are ~unaffected.

**How to apply:** keep it in `LocaleManager.wrap` chained with locale; do NOT early-return when language is blank or normalization gets skipped. Player/detail surfaces use MATCH_PARENT so they're unaffected. If text feels too small at higher widths, lower DESIGN_WIDTH_DP slightly rather than hand-tuning every dimen. Validate 720p/1080p/4K from sofa distance after changes.
