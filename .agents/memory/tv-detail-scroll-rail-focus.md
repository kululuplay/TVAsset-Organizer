---
name: TV detail screen — scrolling stacked rails + D-pad focus
description: How to build an Android TV detail page that stacks horizontal rails in a ScrollView without overlap or D-pad dead-ends.
---

# TV detail page: scrolling stacked horizontal rails

**Two ConstraintLayout blocks, one top-anchored and one bottom-anchored, both
wrap_content with NO chain between them, WILL paint over each other** once their
content grows (long title/plot vs. seasons+episodes). This is a structural bug,
not a styling one.

**Fix pattern (the modern, robust one):** put the whole page in a single bounded
**plain `ScrollView`** (`fillViewport=true`, `scrollbars=none`,
`clipToPadding=false`, safe-area padding), constrained to all parent edges, with a
faint backdrop/scrim behind it. One vertical `LinearLayout` child: hero row
(info/actions + poster) first, then the rails stacked (seasons, episodes, cast,
similar). Overlap becomes impossible regardless of content length.

**Why a ScrollView and not a fixed hero:** on this app's TV surface (~1120×630dp
locked density) a fixed hero cannot fit hero + seasons + episode rail + cast +
similar without clipping. The Dashboard already proves `ScrollView` + D-pad
auto-scroll-to-focused-child works in this codebase, so it is low-risk.

## D-pad focus rules for stacked horizontal rails
- Focus lands on the rails' **item views**, not the RecyclerViews. A plain
  geometry FocusFinder search **stalls** when a focused card is scrolled off-axis
  (e.g. episode #6 at x=900 finds nothing directly below). So **stamp
  `nextFocusUpId`/`nextFocusDownId` onto each child as it attaches** via
  `RecyclerView.OnChildAttachStateChangeListener`. Pointing at the neighbour
  RecyclerView id makes Up/Down hop reliably (the RecyclerView re-dispatches to
  its selected child).
- **Skip display-only rails** (e.g. cast): `android:focusable="false"` +
  `descendantFocusability="blocksDescendants"`, and route the rail above it
  straight to the next focusable rail (episodes → similar, skipping cast).
- **Async / visibility-gated rails:** never point `nextFocusDown` at a GONE view.
  Keep a flag (e.g. `similarVisible`); the attach-stamp reads it for newly
  attached cards, and when the rail's visibility resolves, re-stamp the
  already-attached children of the rail above it (loop `getChildAt`).
- **Initial focus:** `scrollBody.post { scrollTo(0,0); playButton.requestFocus() }`
  in onCreate, so list population doesn't auto-scroll the page down to whatever
  rail grabs focus first. Do NOT request focus from adapter callbacks.
- Keep `supportsChangeAnimations=false` on every rail (known focus ping-pong/ANR
  guard); a ScrollView needs no extra animation guard.

**Non-blocking edge:** action buttons wired DOWN to the season rail can land on an
empty RecyclerView on a cold load before seasons arrive (no crash, just a no-op).
Only worth gating DOWN until data exists if a real-device test shows a dead-end.
