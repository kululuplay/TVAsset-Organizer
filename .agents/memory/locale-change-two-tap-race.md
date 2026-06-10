---
name: Language change needed two taps
description: Why selecting a UI language applied only on the second tap, and the ordering rule that prevents it.
---

# Language change required two taps

Selecting a different UI language in Settings did nothing on the first tap and
only "stuck" on the second.

**Why:** BaseActivity.attachBaseContext applies the locale by reading it
SYNCHRONOUSLY from a SharedPreferences "locale_mirror"
(SettingsStore.languageTagBlocking()), because DataStore is suspend-only and
can't run on the attach path. But the language was persisted DataStore-first,
mirror-second, and the click handler fired the write as a fire-and-forget
coroutine then called recreate() immediately. So the first recreate ran before
the mirror was updated → old locale; the second tap saw the now-current mirror.

**How to apply — two rules whenever a setting drives attachBaseContext/recreate:**
1. Write the synchronous mirror BEFORE any suspend point in the setter
   (`apply()` updates the in-memory value synchronously). Persisting the
   DataStore copy after is fine.
2. The caller must AWAIT the suspend write before recreate() — do
   `lifecycleScope.launch { settings.setLanguageTag(tag); recreate() }`, never
   `viewModel.setX(tag); recreate()` (fire-and-forget + immediate recreate races).

Same latent race exists anywhere else that sets the language then navigates
(e.g. the first-run wizard) — the mirror-first ordering covers those too.
