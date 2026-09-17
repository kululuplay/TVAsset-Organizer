/*
 * FullscreenEpgCardBinder.kt
 *
 * Architecture note (Home screen decomposition):
 *   View binding for the fullscreen EPG card of HomeActivity (channel meta,
 *   current/next programme, progress, the locked-channel mask and the layout
 *   shift that makes room for the channel guide rail). Pure rendering: it holds
 *   no playback or focus state, so any caller can rebind it at any time.
 *
 * Moved verbatim from HomeActivity.
 */
package com.iptv.player.ui.home

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.Program
import com.iptv.player.databinding.ActivityHomeBinding
import com.iptv.player.ui.common.ChannelText
import com.iptv.player.ui.common.LogoPlaceholder
import java.text.DateFormat
import java.util.Date

internal class FullscreenEpgCardBinder(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val timeFmt: DateFormat,
) {
    /** Binds only the fullscreen card so guide focus cannot corrupt preview metadata. */
    fun bindMeta(channel: Channel) {
        val number = channel.number?.toString().orEmpty()
        binding.fullscreenEpgNumber.text = number
        binding.fullscreenEpgNumber.visibility =
            if (number.isEmpty()) View.GONE else View.VISIBLE
        binding.fullscreenEpgChannelName.text = ChannelText.clean(channel.name)
        val category = channel.categoryName.orEmpty()
        binding.fullscreenEpgCategory.text = category
        binding.fullscreenEpgCategory.visibility =
            if (category.isBlank()) View.GONE else View.VISIBLE
        val placeholder = LogoPlaceholder.forName(activity, channel.name)
        if (channel.logoUrl.isNullOrBlank()) {
            binding.fullscreenEpgLogo.load(placeholder) { crossfade(false) }
        } else {
            binding.fullscreenEpgLogo.load(channel.logoUrl) {
                crossfade(false)
                placeholder(placeholder)
                error(placeholder)
                size(160, 120)
            }
        }
    }

    /** Loading placeholder shown atomically as soon as a new channel is committed. */
    fun renderLoading() {
        binding.fullscreenEpgNowTitle.setText(R.string.loading)
        binding.fullscreenEpgRemaining.visibility = View.GONE
        binding.fullscreenEpgProgress.progress = 0
        binding.fullscreenEpgProgress.visibility = View.GONE
        binding.fullscreenEpgTimingRow.visibility = View.GONE
        binding.fullscreenEpgStart.text = ""
        binding.fullscreenEpgEnd.text = ""
        binding.fullscreenEpgNextRow.visibility = View.GONE
        binding.fullscreenEpgNextTitle.text = ""
        binding.fullscreenEpgNextStart.text = ""
    }

    /** Binds current, progress, remaining time and next for the active EPG card. */
    fun bind(programs: List<Program>, now: Long) {
        val state = LiveEpgOverlayPolicy.resolve(programs, now)
        val current = state.current
        if (current == null) {
            binding.fullscreenEpgNowTitle.setText(R.string.no_guide)
            binding.fullscreenEpgRemaining.visibility = View.GONE
            binding.fullscreenEpgProgress.progress = 0
            binding.fullscreenEpgProgress.visibility = View.GONE
            binding.fullscreenEpgTimingRow.visibility = View.GONE
            binding.fullscreenEpgStart.text = ""
            binding.fullscreenEpgEnd.text = ""
        } else {
            binding.fullscreenEpgNowTitle.text = current.title
            val remainingMs = (current.stopMs - now).coerceAtLeast(0L)
            binding.fullscreenEpgRemaining.text =
                if (remainingMs < 60_000L) {
                    activity.getString(R.string.epg_ending)
                } else {
                    activity.getString(
                        R.string.epg_min_left,
                        state.remainingMinutes ?: 0,
                    )
                }
            binding.fullscreenEpgRemaining.visibility = View.VISIBLE
            binding.fullscreenEpgProgress.progress = state.progressPercent
            binding.fullscreenEpgProgress.visibility = View.VISIBLE
            binding.fullscreenEpgStart.text = timeFmt.format(Date(current.startMs))
            binding.fullscreenEpgEnd.text = timeFmt.format(Date(current.stopMs))
            binding.fullscreenEpgTimingRow.visibility = View.VISIBLE
        }

        val next = state.next
        if (next == null) {
            binding.fullscreenEpgNextRow.visibility = View.GONE
            binding.fullscreenEpgNextTitle.text = ""
            binding.fullscreenEpgNextStart.text = ""
        } else {
            binding.fullscreenEpgNextTitle.text = next.title
            binding.fullscreenEpgNextStart.text = timeFmt.format(Date(next.startMs))
            binding.fullscreenEpgNextRow.visibility = View.VISIBLE
        }
    }

    fun bindLocked() {
        binding.fullscreenEpgNumber.text = ""
        binding.fullscreenEpgNumber.visibility = View.GONE
        binding.fullscreenEpgChannelName.setText(R.string.adult_locked_title)
        binding.fullscreenEpgCategory.setText(R.string.pin_locked_content)
        binding.fullscreenEpgCategory.visibility = View.VISIBLE
        binding.fullscreenEpgLogo.load(R.drawable.ic_lock) { crossfade(false) }
        binding.fullscreenEpgNowTitle.setText(R.string.pin_locked_content)
        binding.fullscreenEpgRemaining.visibility = View.GONE
        binding.fullscreenEpgProgress.progress = 0
        binding.fullscreenEpgProgress.visibility = View.GONE
        binding.fullscreenEpgTimingRow.visibility = View.GONE
        binding.fullscreenEpgStart.text = ""
        binding.fullscreenEpgEnd.text = ""
        binding.fullscreenEpgNextRow.visibility = View.GONE
        binding.fullscreenEpgNextTitle.text = ""
        binding.fullscreenEpgNextStart.text = ""
    }

    /** Makes room for the rail while keeping the EPG card in the TV safe area. */
    fun updateGuideLayout(guideVisible: Boolean) {
        val resources = activity.resources
        val params = binding.fullscreenEpgOverlay.layoutParams as FrameLayout.LayoutParams
        val safe = resources.getDimensionPixelSize(R.dimen.safe_area_h)
        params.marginStart = if (guideVisible) {
            safe +
                resources.getDimensionPixelSize(R.dimen.fullscreen_guide_width) +
                resources.getDimensionPixelSize(R.dimen.fullscreen_guide_epg_gap)
        } else {
            safe
        }
        params.marginEnd = safe
        binding.fullscreenEpgOverlay.layoutParams = params
        // The guide's full-screen scrim sits at 20dp while the EPG card starts at
        // 18dp. Raise the card above that scrim only while both are visible.
        binding.fullscreenEpgOverlay.translationZ =
            if (guideVisible) resources.getDimension(R.dimen.space_xs) else 0f
        if (guideVisible) binding.fullscreenEpgOverlay.bringToFront()
    }
}
