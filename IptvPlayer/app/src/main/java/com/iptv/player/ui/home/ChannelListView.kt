package com.iptv.player.ui.home

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Keeps vertical remote navigation in the channel list while rows are attaching. */
class ChannelListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    var onNavigateAboveStart: (() -> Unit)? = null

    private data class Target(val channelId: String?, val position: Int, val childId: Int)
    private var anchor = Target(null, 0, View.NO_ID)
    private var pending: Target? = null
    private var attempts = 0
    private var applyingFocus = false

    init {
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        preserveFocusAfterLayout = true
        itemAnimator = null
    }

    private fun channelId(position: Int): String? =
        (adapter as? ChannelAdapter)?.currentList?.getOrNull(position)?.id

    private fun resolve(target: Target): Int? {
        val count = adapter?.itemCount ?: 0
        if (count == 0) return null
        if (target.channelId != null) {
            val position = (adapter as? ChannelAdapter)?.currentList
                ?.indexOfFirst { it.id == target.channelId } ?: -1
            // A removed/filtered channel must not focus a different row by stale index.
            return position.takeIf { it >= 0 }
        }
        return target.position.coerceIn(0, count - 1)
    }

    private fun cancelPending() {
        removeCallbacks(focusPending)
        pending = null
    }

    private val focusPending = object : Runnable {
        override fun run() {
            val request = pending ?: return
            if (!isAttachedToWindow || !isShown || !hasFocus()) {
                cancelPending()
                return
            }
            if (!isComputingLayout && !hasPendingAdapterUpdates()) {
                val position = resolve(request)
                if (position == null) {
                    cancelPending()
                    return
                }
                val holder = findViewHolderForAdapterPosition(position)
                if (holder != null && holder.bindingAdapterPosition == position) {
                    val child = if (request.childId != View.NO_ID)
                        holder.itemView.findViewById<View>(request.childId) else null
                    val target = child?.takeIf { it.isShown && it.isFocusable } ?: holder.itemView
                    applyingFocus = true
                    val accepted = try { target.requestFocus() } finally { applyingFocus = false }
                    if (accepted) {
                        cancelPending()
                        return
                    }
                }
                scrollToPosition(position)
            }
            if (++attempts < 30) postOnAnimation(this) else cancelPending()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            KeyEvent.KEYCODE_DPAD_UP -> false
            else -> return super.dispatchKeyEvent(event)
        }
        if (!hasFocus()) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // Coalesce repeats while the next row is laid out. Never let a missed
        // geometric focus candidate send DOWN back to search or into the EPG.
        if (pending != null || isComputingLayout || hasPendingAdapterUpdates()) return true
        val focused = findFocus()
        val row = focused?.let(::findContainingViewHolder)
        val position = row?.bindingAdapterPosition?.takeIf { it != NO_POSITION }
            ?: resolve(anchor) ?: return true
        val count = adapter?.itemCount ?: 0
        if (position !in 0 until count) return true
        if (!down && position == 0) {
            // Reaching the top while holding UP stays in the list. A fresh UP
            // press is the intentional exit to search.
            if (event.repeatCount == 0) onNavigateAboveStart?.invoke()
            return true
        }
        val next = (position + if (down) 1 else -1).coerceIn(0, count - 1)
        if (next == position) return true
        val childId = focused?.takeUnless { it === row?.itemView || it === this }?.id ?: View.NO_ID
        pending = Target(channelId(next), next, childId)
        attempts = 0
        focusPending.run()
        return true
    }

    override fun requestChildFocus(child: View, focused: View) {
        // Horizontal navigation or an explicit category/EPG return supersedes
        // any delayed vertical move, even if focus is still inside this list.
        if (!applyingFocus) cancelPending()
        val position = getChildAdapterPosition(child)
        if (position != NO_POSITION) {
            anchor = Target(channelId(position), position, if (focused === child) View.NO_ID else focused.id)
        }
        super.requestChildFocus(child, focused)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus && pending == null) {
            pending = anchor
            attempts = 0
            postOnAnimation(focusPending)
        }
    }

    override fun onDetachedFromWindow() {
        cancelPending()
        super.onDetachedFromWindow()
    }
}
