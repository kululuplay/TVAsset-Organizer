---
name: Fullscreen CH+/CH- zap must debounce
description: Why rapid channel zapping needs an auto-repeat guard + settle debounce, not an instant play() per press
---

In the fullscreen live player, CH+/CH- (and DPAD UP/DOWN) zapping must NOT start a stream on every key press.

**Rule:** ignore key auto-repeat (`event.repeatCount > 0`) so a held key can't runaway-zap, and debounce discrete presses (~280ms): advance the channel pointer + update the overlay instantly on each press, but start the actual stream only once the user settles. Cancel the pending zap on hand-back/destroy.

**Why:** every press previously called `controller.play()`, which on the fast-zap reuse path hits `VlcPlayerEngine.play()` = `mp.stop(); mp.media=...; mp.play()`. libVLC `stop()` is asynchronous on Amlogic; a burst of presses overlaps decoder teardown/startup and intermittently freezes the picture ("bazen takılma"). Collapsing a burst into one start removes the churn.

**How to apply:** keep the single-connection contract and the green fallback ladder untouched — this is purely an input-rate fix in the Activity, not the engine. The overlay update during scrolling should be lightweight (name/number/placeholder logo, EPG cleared); load the real logo + EPG only on the settled `startChannel`. Same pattern as the existing focus-driven EPG debounce.
