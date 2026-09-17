package com.iptv.player.playback.core

/**
 * Sink for "the stream's video format is now known" so a display-mode switcher
 * can match the TV's refresh rate. Android-free so the VOD coordinator and the
 * live controller can be unit-tested with a fake.
 */
interface FrameRateMatcher {
    /** [fps] may be 0/-1 while the backend has not populated it yet (ignored). */
    fun onVideoFormat(fps: Float, width: Int, height: Int)

    /** Put the display back to what it was before the first switch, if any. */
    fun restore()
}
