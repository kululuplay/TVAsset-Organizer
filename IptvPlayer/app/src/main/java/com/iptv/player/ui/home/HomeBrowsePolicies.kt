/*
 * HomeBrowsePolicies.kt
 *
 * Architecture note (Home screen decomposition):
 *   Pure, JVM-testable pieces of HomeActivity's browse pane:
 *   - [HomeCaptionText]: the "now playing" / "next" caption lines under the
 *     preview card (locale clock formatter injected, no Android dependency).
 *   - [HomeBrowseStatePolicy]: loading / retry / empty visibility rules.
 *
 * Extracted from HomeActivity; formatting and decision tables are unchanged.
 */
package com.iptv.player.ui.home

import com.iptv.player.data.model.Program
import java.text.DateFormat
import java.util.Date

internal class HomeCaptionText(
    private val nextLabel: String,
    private val timeFmt: DateFormat,
) {
    /**
     * Caption text under the card: the live program, plus the following one when
     * no preview video will fill the card (preview disabled and nothing playing).
     */
    fun captionProgramLabel(programs: List<Program>, now: Long, includeNext: Boolean): String {
        val nowLine = nowPlayingLabel(programs, now)
        if (!includeNext) return nowLine
        val next = programs.firstOrNull { it.startMs > now } ?: return nowLine
        val nextLine = "$nextLabel: " +
            "${timeFmt.format(Date(next.startMs))}  ${next.title}"
        return if (nowLine.isEmpty()) nextLine else "$nowLine\n$nextLine"
    }

    /** Formats the "now playing" caption line from [programs], or "" if none is live. */
    fun nowPlayingLabel(programs: List<Program>, now: Long): String {
        val current = programs.firstOrNull { it.isLiveAt(now) } ?: return ""
        return "${timeFmt.format(Date(current.startMs))} - " +
            "${timeFmt.format(Date(current.stopMs))}  ${current.title}"
    }
}

internal object HomeBrowseStatePolicy {
    data class Flags(
        val blockingLoading: Boolean,
        val blockingFailure: Boolean,
        val empty: Boolean,
    )

    /**
     * Favorites/Recent are synthetic rows and do not prove that a usable live
     * catalogue exists ([hasRealCategories] excludes them). A first-load failure
     * must still show Retry when those two placeholders are the only rows.
     */
    fun resolve(
        loading: Boolean,
        failed: Boolean,
        hasRealCategories: Boolean,
        hasChannels: Boolean,
        visibleItemCount: Int,
    ): Flags {
        val hasCachedBrowseData = hasRealCategories || hasChannels
        val blockingLoading = loading && !hasCachedBrowseData
        val blockingFailure = failed && !hasCachedBrowseData
        val empty = visibleItemCount == 0 && !loading && !blockingFailure
        return Flags(blockingLoading, blockingFailure, empty)
    }
}
