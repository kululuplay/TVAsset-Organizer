---
name: Parental/PIN gate must cover metadata, not just playback
description: A locked (adult) category leaks via auto-preview unless the metadata path is gated too
---

On the Live TV screen, PIN-gating only the *playback* paths (drill-in + open
player) is NOT enough. The left category list auto-previews the **first channel**
of whatever category is merely *focused* (name, logo, EPG program titles) in the
right panel. So scrolling onto a locked adult category exposed its content
metadata with no PIN, even though pressing OK/play was still blocked.

**Why:** parental control is about not *showing* locked content, not only about
not *playing* it. Logos and EPG titles can themselves be explicit.

**How to apply:** any auto-preview / focus-driven info panel must check the
parental lock of the focused item before rendering. We added
`isCurrentCategoryLocked()` (focused category `isAdult()` && not in the
session `unlockedCategories` set) and gate the auto-preview: render `showInfo`
only when unlocked, else `clearPreview()`. Re-check every surface that can paint
locked content without a drill-in: auto-preview, number-zap, preview-card click,
the RIGHT-arrow shortcut. The channel *list* itself is safe only because it's
hidden (GONE) in category view — if that ever changes, it needs gating too.

**Every playback entry point needs its own gate — and Favorites/Recent bypass
category locks.** The Home screen has MULTIPLE ways to start live video: the
fullscreen open (`openPlayer`) AND the in-panel preview start
(`onChannelClicked` → `startPreviewFor`). Gating only one leaks the other —
`startPreviewFor` played adult channels PIN-free. The catch is that
Favorites/Recent are **synthetic cross-section categories**: an adult channel
shows up there but the synthetic category is never `isAdult()`, so the
category-level lock (`isCurrentCategoryLocked`/`drillIntoCategory`) does NOT
cover it. So per-playback gate on `channel.isAdult()`, but skip the prompt when
`lastSelectedCategoryId in unlockedCategories` (a real locked category can't be
browsed without unlocking first, so re-prompting per channel is pure friction).
Whenever you add a new way to start/show a stream, mirror this gate.
