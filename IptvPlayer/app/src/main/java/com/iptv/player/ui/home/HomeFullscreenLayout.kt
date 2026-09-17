/*
 * HomeFullscreenLayout.kt
 *
 * Architecture note (Home screen decomposition):
 *   Fullscreen is a layout state of HomeActivity, not a second player screen:
 *   the same controller, SurfaceView, decoder and network connection stay alive.
 *   This class snapshots the browse layout metrics at inflate time and swaps the
 *   panes/padding/split/preview-card params between browse and fullscreen. No
 *   player API is called here. The Activity interleaves its own overlay/EPG
 *   work between the two halves of each transition, hence the split methods.
 *
 * Moved verbatim from HomeActivity (BrowseLayoutSnapshot + layout mutations of
 * enterPreviewFullscreen/exitPreviewFullscreen).
 */
package com.iptv.player.ui.home

import android.view.View
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.iptv.player.databinding.ActivityHomeBinding

internal class HomeFullscreenLayout(private val binding: ActivityHomeBinding) {

    private data class BrowseLayoutSnapshot(
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val splitPercent: Float,
        val previewTopMargin: Int,
        val previewWeight: Float,
        val previewClipToOutline: Boolean,
        val previewFocusable: Boolean,
        val previewClickable: Boolean,
    )

    /** Captured at construction, i.e. right after inflate and before setContentView. */
    private val browseLayoutSnapshot: BrowseLayoutSnapshot = captureBrowseLayout()

    private fun captureBrowseLayout(): BrowseLayoutSnapshot {
        val guide = binding.splitGuide.layoutParams as ConstraintLayout.LayoutParams
        val preview = binding.previewCard.layoutParams as LinearLayout.LayoutParams
        return BrowseLayoutSnapshot(
            paddingLeft = binding.root.paddingLeft,
            paddingTop = binding.root.paddingTop,
            paddingRight = binding.root.paddingRight,
            paddingBottom = binding.root.paddingBottom,
            splitPercent = guide.guidePercent,
            previewTopMargin = preview.topMargin,
            previewWeight = preview.weight,
            previewClipToOutline = binding.previewCard.clipToOutline,
            previewFocusable = binding.previewCard.isFocusable,
            previewClickable = binding.previewCard.isClickable,
        )
    }

    private fun updateSplitGuide(percent: Float) {
        val params = binding.splitGuide.layoutParams as ConstraintLayout.LayoutParams
        params.guidePercent = percent
        binding.splitGuide.layoutParams = params
    }

    /** Fullscreen, step 1: drop card focus and hide the browse panes. */
    fun enterPanes() {
        binding.previewCard.clearFocus()
        binding.previewCard.isFocusable = false
        binding.previewCard.isClickable = false
        binding.previewCard.clipToOutline = false
        binding.leftPane.visibility = View.GONE
        binding.previewHeader.visibility = View.GONE
        binding.guidePanel.visibility = View.GONE
        binding.catchupHint.visibility = View.GONE
        binding.previewCaption.visibility = View.GONE
    }

    /** Fullscreen, step 2: edge-to-edge padding/split and full-weight card. */
    fun enterMetrics() {
        binding.root.setPadding(0, 0, 0, 0)
        updateSplitGuide(0f)
        (binding.previewCard.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.topMargin = 0
            params.weight = 1f
            binding.previewCard.layoutParams = params
        }
    }

    /** Browse, step 1: restore padding/split and reveal the browse panes. */
    fun exitPanes() {
        val snapshot = browseLayoutSnapshot
        binding.root.setPadding(
            snapshot.paddingLeft,
            snapshot.paddingTop,
            snapshot.paddingRight,
            snapshot.paddingBottom,
        )
        updateSplitGuide(snapshot.splitPercent)
        binding.leftPane.visibility = View.VISIBLE
        binding.previewHeader.visibility = View.GONE
        binding.guidePanel.visibility = View.VISIBLE
        binding.previewCaption.visibility = View.VISIBLE
    }

    /** Browse, step 2: restore the preview card's params and interactivity. */
    fun exitCard() {
        val snapshot = browseLayoutSnapshot
        (binding.previewCard.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.topMargin = snapshot.previewTopMargin
            params.weight = snapshot.previewWeight
            binding.previewCard.layoutParams = params
        }
        binding.previewCard.clipToOutline = snapshot.previewClipToOutline
        binding.previewCard.isClickable = snapshot.previewClickable
        binding.previewCard.isFocusable = snapshot.previewFocusable
    }
}
