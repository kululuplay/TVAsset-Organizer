package com.iptv.player.ui.player

import android.view.View

/**
 * Pure D-pad horizontal focus-chain builder for the VOD top action bar.
 *
 * Explicit XML `nextFocusLeft`/`nextFocusRight` attributes do not automatically
 * skip disabled views on all Android TV / Fire OS builds, so the chain is
 * rebuilt around whichever track buttons are currently available.
 */
internal object VodChromeFocusChain {

    data class ChainLink(
        val viewId: Int,
        val nextFocusLeftId: Int,
        val nextFocusRightId: Int,
    )

    data class Plan(
        val backNextFocusRightId: Int,
        val playPauseNextFocusUpId: Int,
        val links: List<ChainLink>,
    )

    fun plan(
        backId: Int,
        audioId: Int,
        subtitleId: Int,
        aspectId: Int,
        sleepId: Int,
        castId: Int,
        hasAudio: Boolean,
        hasSubtitles: Boolean,
        noId: Int = View.NO_ID,
    ): Plan {
        val actionIds = buildList {
            if (hasAudio) add(audioId)
            if (hasSubtitles) add(subtitleId)
            add(aspectId)
            add(sleepId)
            add(castId)
        }
        val links = actionIds.mapIndexed { index, id ->
            ChainLink(
                viewId = id,
                nextFocusLeftId = actionIds.getOrElse(index - 1) { backId },
                nextFocusRightId = actionIds.getOrElse(index + 1) { noId },
            )
        }
        return Plan(
            backNextFocusRightId = actionIds.first(),
            playPauseNextFocusUpId = actionIds[actionIds.size / 2],
            links = links,
        )
    }

    fun apply(
        backButton: View,
        playPauseButton: View,
        audioButton: View,
        subtitleButton: View,
        aspectButton: View,
        sleepButton: View,
        castButton: View,
        hasAudio: Boolean,
        hasSubtitles: Boolean,
    ) {
        val viewsById = mapOf(
            audioButton.id to audioButton,
            subtitleButton.id to subtitleButton,
            aspectButton.id to aspectButton,
            sleepButton.id to sleepButton,
            castButton.id to castButton,
        )
        val plan = plan(
            backId = backButton.id,
            audioId = audioButton.id,
            subtitleId = subtitleButton.id,
            aspectId = aspectButton.id,
            sleepId = sleepButton.id,
            castId = castButton.id,
            hasAudio = hasAudio,
            hasSubtitles = hasSubtitles,
        )
        backButton.nextFocusRightId = plan.backNextFocusRightId
        playPauseButton.nextFocusUpId = plan.playPauseNextFocusUpId
        plan.links.forEach { link ->
            viewsById[link.viewId]?.let { view ->
                view.nextFocusLeftId = link.nextFocusLeftId
                view.nextFocusRightId = link.nextFocusRightId
            }
        }
    }
}
