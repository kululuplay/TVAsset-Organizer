---
name: Disabling a focused button steals D-pad focus
description: On Android TV, setting isEnabled=false on the currently-focused view drops focus to another focusable, whose focus listener can navigate away.
---

# Don't disable the currently-focused button on Android TV

Setting `isEnabled = false` on the view that currently holds D-pad focus makes
Android immediately reassign focus to the nearest focusable. In a master/detail
settings screen the nearest focusable is often the first left-rail nav row, whose
`setOnFocusChangeListener { if (hasFocus) showPanel(...) }` then switches the
visible panel — so the user is "thrown" to that panel and any in-flight work tied
to the old panel gets cancelled.

**Concrete incident:** the Speed Test "Start" button disabled itself on click;
focus jumped to the "General Info" row, which switched panels AND cancelled the
running speed test. The user saw General Info open and the test never run.

**Why:** Disabled views cannot hold focus; focus-change listeners on rails/lists
double as navigation, so an involuntary focus move = an involuntary navigation.

**How to apply:** For a busy/running action button, keep it `isEnabled = true`
and guard re-entry with a job/`isActive` check instead of disabling. Use a visual
cue (alpha dim + "…" label) for the running state so the button retains focus.
Same caution applies anywhere a focus listener performs navigation.
