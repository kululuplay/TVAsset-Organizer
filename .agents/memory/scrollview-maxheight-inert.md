---
name: ScrollView android:maxHeight is inert
description: Why a "height-capped" ScrollView in a vertical LinearLayout silently squeezes pinned siblings to zero, and the fix
---

`android:maxHeight` is read only by TextView/ImageView. ScrollView (and FrameLayout)
**ignore it**. So an XML ScrollView with `android:maxHeight="380dp"` is actually
uncapped.

**Why it bites:** in a vertical LinearLayout the ScrollView is measured before a
sibling pinned *below* it (e.g. a dialog's Cancel/Send CTA row). Once content +
chrome exceed the window height, the uncapped ScrollView eats all remaining AT_MOST
space and the pinned row measures to ~0 px — invisible AND unfocusable (D-pad dead).
The dialog looks fine while content is short, then breaks exactly when the feature
fills it (e.g. a request-history list growing past ~4 rows).

**Fix (used by dialog_request.xml):** a `MaxHeightScrollView` subclass in
`ui/common` that reads `android:maxHeight` via
`obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))` and in
`onMeasure` clamps the height spec to `MeasureSpec.makeMeasureSpec(maxPx, AT_MOST)`.
AT_MOST (not EXACTLY) so short content still wraps; tall content stops at the cap and
scrolls. Do NOT try `layout_weight` inside a `wrap_content` parent — weight needs a
bounded parent.

**How to apply:** any dialog/card that pins CTAs under a scrollable region must use
MaxHeightScrollView, never a bare ScrollView+android:maxHeight. `dialog_announcement.xml`
and `dialog_update.xml` carry the same latent bug (safe only because their content is
short). `dialog_resolved.xml` is safe — no ScrollView, its quoted text is maxLines=4
+ ellipsize.
