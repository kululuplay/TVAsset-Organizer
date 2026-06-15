---
name: Nullable lambda smart-cast inside a closure
description: Why a null-checked nullable member lambda still needs ?. when invoked inside a nested lambda (e.g. runCatching{}); local LSP misses it, only CI Kotlin compiler catches it.
---

A nullable **member** property — even a `val` ctor param like `onAnr: ((String) -> Unit)? = null` — does NOT smart-cast to non-null inside a nested lambda/closure, even immediately after an `if (onAnr != null)` guard. Calling it directly as `onAnr.invoke(x)` inside `runCatching { ... }` fails to compile:
`Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type ((String) -> Unit)?`

**Why:** Kotlin can't prove a member property is still non-null at the moment the closure actually runs (a closure may execute later, and members can in principle be changed via another path), so the outer guard's smart-cast is dropped inside the lambda body. (A *local* `val` would smart-cast; a member property captured by a lambda does not.)

**How to apply:** Use `onAnr?.invoke(x)` — the outer `if (onAnr != null)` still gates it, so `?.` is harmless and correct. The local Kotlin LSP in this repo does NOT flag this (no Android classpath, so LSP "clean" is unreliable); it only surfaces as a CI `:app:compileDebugKotlin` error. Before trusting a green local LSP, audit any nullable-lambda `.invoke(`/direct call that sits inside a closure.
