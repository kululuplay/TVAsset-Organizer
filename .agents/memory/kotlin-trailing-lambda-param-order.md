---
name: Kotlin trailing-lambda param order
description: Adding a defaulted lambda param AFTER a required lambda silently breaks all trailing-lambda call sites.
---
A Kotlin trailing lambda always binds to the LAST parameter. If a function takes a
required `onAllowed: () -> Unit` and you append `onDenied: () -> Unit = {}` after it,
every `foo(...) { ... }` call site now binds the lambda to `onDenied`, producing
"No value passed for parameter 'onAllowed'" compile errors at EVERY caller.

**Why:** Happened with `PinLockHelper.guard()` — many activities (Dashboard/Home/Search/Series/Vod)
broke at once though none were edited; only the helper signature changed.

**How to apply:** Keep the required action lambda LAST. Put optional/defaulted lambdas
(e.g. onDenied/onCancel) BEFORE it so trailing-lambda syntax keeps working. When a
build fails with the same "No value passed for parameter X" across many unrelated
files, suspect a shared function whose param order changed, not the call sites.
