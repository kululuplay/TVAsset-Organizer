package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/**
 * A progress clock in the source period, read on the player's application thread.
 *
 * Media3 currentPosition is relative to the current live window. HLS removes old
 * segments on every playlist refresh, so that position can repeatedly move back
 * during healthy playback. HlsMediaSource records the removed duration in the
 * window's positionInFirstPeriod; adding it recovers the source clock even when
 * the playlist has no PROGRAM-DATE-TIME. A frozen player moves backwards relative
 * to a sliding window by the same amount, so refreshes alone cannot fake progress.
 * The caller reuses its window to avoid allocating on each watchdog poll.
 */
@OptIn(markerClass = [UnstableApi::class])
internal fun exoPlaybackClockPositionMs(player: Player?, window: Timeline.Window): Long {
    if (player == null) return -1L
    val positionMs = player.currentPosition
    if (positionMs < 0L) return -1L
    val timeline = player.currentTimeline
    if (timeline.isEmpty) return positionMs
    val index = player.currentMediaItemIndex
    if (index !in 0 until timeline.windowCount) return -1L
    val offsetMs = timeline.getWindow(index, window).positionInFirstPeriodMs
    if (offsetMs == C.TIME_UNSET || offsetMs < 0L) return positionMs
    if (positionMs > Long.MAX_VALUE - offsetMs) return -1L
    return positionMs + offsetMs
}
