/*
 * FrameRateMatchSession.kt
 * Pure debounce/dedupe/restore state behind the Android DisplayModeSwitcher.
 * A display-mode switch blanks the picture for a second or two on most TVs, so
 * it must happen ONCE per content family, never on every zap re-check, and at
 * most once per MIN_SWITCH_INTERVAL. Android-free so it is unit-testable.
 */
package com.iptv.player.playback.core

import com.iptv.player.player.AfrMode
import com.iptv.player.player.DisplayModeInfo
import com.iptv.player.player.FrameRateMatchPolicy

class FrameRateMatchSession(
    private val minSwitchIntervalMs: Long = DEFAULT_MIN_SWITCH_INTERVAL_MS,
) {

    sealed interface Decision {
        /** Apply this mode id to the window now. */
        data class Switch(val targetModeId: Int) : Decision

        /** Too soon after the previous switch: ask again after [delayMs]. */
        data class Defer(val delayMs: Long) : Decision

        /** The policy found nothing to change (already fits / no candidate). */
        data object Keep : Decision

        /** AFR off, unknown fps, or a repeat of the family already handled. */
        data object Ignore : Decision
    }

    /** Family key of the last request that was fully evaluated. */
    private data class Key(val mode: AfrMode, val rate: Int, val resolution: FrameRateMatchPolicy.ResolutionClass?)

    private var lastKey: Key? = null
    private var lastSwitchAtMs: Long? = null

    /** True once a switch was applied and not yet restored. */
    var switched: Boolean = false
        private set

    fun request(
        nowMs: Long,
        afrMode: AfrMode,
        fps: Float,
        width: Int,
        height: Int,
        modes: List<DisplayModeInfo>,
        currentModeId: Int,
    ): Decision {
        if (afrMode == AfrMode.OFF) return Decision.Ignore
        val rate = FrameRateMatchPolicy.normalizeRate(fps) ?: return Decision.Ignore
        val key = Key(
            mode = afrMode,
            rate = rate,
            resolution = if (afrMode == AfrMode.REFRESH_AND_RESOLUTION) {
                FrameRateMatchPolicy.resolutionClass(width, height)
            } else {
                null
            },
        )
        // Same family as the request already handled: the zap re-checks and the
        // late-fps re-polls must not evaluate (and possibly blank) again.
        if (key == lastKey) return Decision.Ignore
        val target = FrameRateMatchPolicy.selectMode(
            contentFps = fps,
            contentWidth = width,
            contentHeight = height,
            modes = modes,
            currentModeId = currentModeId,
            afrMode = afrMode,
        )
        if (target == null) {
            lastKey = key
            return Decision.Keep
        }
        val since = lastSwitchAtMs?.let { nowMs - it }
        if (since != null && since < minSwitchIntervalMs) {
            // Not recorded as handled: the deferred retry must evaluate again.
            return Decision.Defer(minSwitchIntervalMs - since)
        }
        lastKey = key
        lastSwitchAtMs = nowMs
        switched = true
        return Decision.Switch(target)
    }

    /**
     * Forget the handled family without undoing a switch: the next request is
     * evaluated afresh (used when the user changes the AFR setting mid-play).
     */
    fun invalidate() {
        lastKey = null
    }

    /**
     * Returns true when a switch had been applied, so the caller must put the
     * window's preferred mode back. Clears all state either way.
     */
    fun restore(): Boolean {
        val hadSwitched = switched
        switched = false
        lastKey = null
        lastSwitchAtMs = null
        return hadSwitched
    }

    companion object {
        const val DEFAULT_MIN_SWITCH_INTERVAL_MS = 2_000L
    }
}
