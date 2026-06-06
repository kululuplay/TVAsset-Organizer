/*
 * NewContentPopup.kt
 * A small, non-focusable "N new movies/series/channels added" notice that fades
 * in at the top-right corner of a content screen and auto-dismisses after a few
 * seconds. It attaches itself to the activity's content frame, so no screen
 * layout needs to reserve space for it, and it never steals D-pad focus.
 */
package com.iptv.player.ui.common

import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.iptv.player.R

object NewContentPopup {

    private const val VISIBLE_MS = 3000L
    private const val FADE_MS = 220L

    /**
     * Shows the notice in [activity]. Safe to call repeatedly; each call adds a
     * fresh, self-removing view. Does nothing if the content frame is missing.
     */
    fun show(activity: Activity, @DrawableRes iconRes: Int, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Keep only one notice on screen: drop any still-animating previous popup.
        for (i in root.childCount - 1 downTo 0) {
            if (root.getChildAt(i).id == R.id.popupRoot) root.removeViewAt(i)
        }

        val view = LayoutInflater.from(activity)
            .inflate(R.layout.view_new_content_popup, root, false)
        view.findViewById<ImageView>(R.id.popupIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.popupText).text = message

        val margin = activity.resources.getDimensionPixelSize(R.dimen.space_l)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(margin, margin, margin, margin)
        }
        root.addView(view, params)

        val drop = 16f * activity.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = -drop
        view.animate().alpha(1f).translationY(0f).setDuration(FADE_MS).start()

        view.postDelayed({ dismiss(root, view, drop) }, VISIBLE_MS)
    }

    private fun dismiss(root: ViewGroup, view: View, drop: Float) {
        view.animate()
            .alpha(0f)
            .translationY(-drop)
            .setDuration(FADE_MS)
            .withEndAction { root.removeView(view) }
            .start()
    }
}
