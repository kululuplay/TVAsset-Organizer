---
name: Launch dialog priority & chaining
description: How the launch-time dialogs (expiry vs update) are ordered and kept from overlapping
---

Launch-time dialogs are chained via an `onNoPrompt` callback so only ONE shows
per launch and they never overlap. Each prompt object owns its own
`shownThisSession` guard.

**Order (deliberate):** subscription-expiry check runs FIRST, update prompt is
the fallback (`ExpiryWarningPrompt.maybeShow(this) { UpdatePrompt.maybeShow(this) }`).

**Why:** an already-expired account cannot watch anything, so that notice must
always appear at launch even when an update is also available. Earlier the order
was reversed (update first), which silently swallowed the expiry notice whenever
an update existed.

**How to apply:** any new launch dialog must (a) accept an `onNoPrompt` and invoke
it on every no-show path, and (b) be inserted into the chain at a priority that
reflects how blocking it is. The expired notice is non-cancelable
(`setCancelable(false)` + `setCanceledOnTouchOutside(false)`); the soft
"expiring soon" reminder stays cancelable and is suppressible per expiry date.

Expired is detected as `status == "Expired"` (case-insensitive) OR
`daysRemaining < 0`; "expiring soon" is `daysRemaining in 0..5`.
