/*
 * MaxHeightScrollView.kt
 * A ScrollView that actually honours android:maxHeight. The framework ScrollView
 * (and FrameLayout) ignore maxHeight — only TextView/ImageView read it — so a
 * "height-capped" ScrollView declared in XML is in fact uncapped, and inside a
 * vertical LinearLayout it greedily eats all remaining space, squeezing any
 * sibling pinned BELOW it (e.g. the dialog's Cancel/Send row) down to zero height.
 * This subclass reads android:maxHeight from the inflated attributes and clamps
 * its measured height to it, so content scrolls past the cap while the pinned CTAs
 * always keep their full height. Used by dialog_request.xml.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    /** Cap in px, read from android:maxHeight; 0 means "no cap" (plain ScrollView). */
    private var maxHeightPx: Int = 0

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
            maxHeightPx = a.getDimensionPixelSize(0, 0)
            a.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSpec = if (maxHeightPx > 0) {
            // AT_MOST so short content still wraps; tall content stops at the cap
            // and scrolls, instead of expanding and starving the pinned siblings.
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }
}
