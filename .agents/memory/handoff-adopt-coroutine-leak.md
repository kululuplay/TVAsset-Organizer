---
name: Preview→fullscreen adopt leak (ghost audio)
description: Why the consumed hand-off controller must be tracked in a field, not only a coroutine local, in the fullscreen player.
---

The fullscreen player consumes the handed-over preview controller SYNCHRONOUSLY in
onCreate (`consumePendingLiveController()`), but only assigns it to the `lateinit
controller` LATER, inside a `lifecycleScope.launch` after suspending on settings +
`resolveChannel()` (DataStore + Room reads).

**Rule:** Track the consumed controller in an Activity field the moment it is
consumed; release it in onDestroy when the field is still set (ownership never
transferred). Clear the field the instant `controller = adopted` runs (and in any
early-return that already releases it).

**Why:** A fast BACK during those suspensions cancels the coroutine before
`controller = adopted`, so onDestroy's `::controller.isInitialized` guard is false
and the adopted controller is NEVER released. Its ExoPlayer/libVLC native engine
keeps decoding and **playing audio in the background** with no owner → ghost audio +
native leak + breaks the single-connection contract (GC won't free native players).

**How to apply:** `controller = adopted` and the field-clear must be consecutive
synchronous statements (no suspension between). onDestroy: `if (!handingBack) { if
(::controller.isInitialized) controller.release() else pendingField?.release() }`.
The branches are mutually exclusive → no double-release; `handingBack` implies the
field is already null (it is cleared in the same block that sets `adoptedPreview`),
so a controller handed back to Home is never wrongly released.

**Known residual (pre-existing, out of scope):** merely BACKGROUNDING (HOME) during
the setup window skips onStop's `controller.pause()` (not yet initialized), so the
coroutine finishes the rebind while stopped and plays audio off-screen until
return/destroy. Cannot leak (onDestroy covers it). Fix later only if reported:
pause the pending field in onStop, or check `lifecycle.currentState` after assign.
