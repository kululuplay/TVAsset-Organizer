package com.iptv.player.ui.common

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * TV catalog focus stays in the grid while the next row is being attached.
 * Activities explicitly handle exits to the toolbar/category at the top/start.
 * Android's geometric focus search otherwise selects the category behind a
 * scrolling poster when repeated keys overtake RecyclerView's layout.
 */
class PosterGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private var anchor = 0
    private var pendingPosition: Int? = null
    private var attempts = 0

    init {
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        preserveFocusAfterLayout = true
        // Insert/remove animations can detach the focused card during Paging diffs.
        itemAnimator = null
    }

    private val focusPending = object : Runnable {
        override fun run() {
            val requested = pendingPosition ?: return
            if (!isAttachedToWindow || !hasFocus()) {
                pendingPosition = null
                return
            }
            if (!isComputingLayout && !hasPendingAdapterUpdates()) {
                val count = adapter?.itemCount ?: 0
                if (count > 0) {
                    val target = requested.coerceIn(0, count - 1)
                    val holder = findViewHolderForAdapterPosition(target)
                    if (holder != null && holder.bindingAdapterPosition == target &&
                        holder.itemView.isFocusable && holder.itemView.requestFocus()) {
                        pendingPosition = null
                        return
                    }
                    scrollToPosition(target)
                }
            }
            if (++attempts < 30) postOnAnimation(this) else pendingPosition = null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> PosterGridNavigation.Direction.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PosterGridNavigation.Direction.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> if (layoutDirection == LAYOUT_DIRECTION_RTL)
                PosterGridNavigation.Direction.NEXT else PosterGridNavigation.Direction.PREVIOUS
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (layoutDirection == LAYOUT_DIRECTION_RTL)
                PosterGridNavigation.Direction.PREVIOUS else PosterGridNavigation.Direction.NEXT
            else -> return super.dispatchKeyEvent(event)
        }
        if (!hasFocus()) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // Coalesce repeats until a card is laid out; never queue hundreds of
        // delayed requests that would steal focus after the user exits the grid.
        if (pendingPosition != null || isComputingLayout || hasPendingAdapterUpdates()) return true
        val focused = findFocus()
        val position = focused?.let(::findContainingViewHolder)?.bindingAdapterPosition
            ?.takeIf { it != NO_POSITION } ?: anchor
        val columns = (layoutManager as? GridLayoutManager)?.spanCount ?: return true
        val target = PosterGridNavigation.target(position, adapter?.itemCount ?: 0, columns, direction)
            ?: return true
        if (target != position) {
            pendingPosition = target
            attempts = 0
            focusPending.run()
        }
        return true
    }

    override fun requestChildFocus(child: View, focused: View) {
        getChildAdapterPosition(child).takeIf { it != NO_POSITION }?.let { anchor = it }
        super.requestChildFocus(child, focused)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus && pendingPosition == null) {
            pendingPosition = anchor
            attempts = 0
            postOnAnimation(focusPending)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(focusPending)
        pendingPosition = null
        super.onDetachedFromWindow()
    }
}
