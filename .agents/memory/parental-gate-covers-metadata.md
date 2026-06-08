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
