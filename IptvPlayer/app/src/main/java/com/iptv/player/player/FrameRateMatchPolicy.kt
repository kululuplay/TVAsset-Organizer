/*
 * FrameRateMatchPolicy.kt
 * Pure (Android-free) automatic frame-rate matching decision: given what the
 * stream reports and which display modes the TV offers, pick the mode whose
 * refresh is an integer multiple of the content rate. Judder from 25 fps on
 * 60 Hz (or 24 on 50) is the single most visible playback flaw on European
 * IPTV; a correct mode switch removes it, a wrong one blanks the screen.
 */
package com.iptv.player.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** User setting for automatic frame-rate matching (persisted as the enum name). */
enum class AfrMode {
    /** Never touch the display mode (default: a wrong switch blanks some TVs). */
    OFF,

    /** Change only the refresh rate; the current resolution is kept. */
    REFRESH_ONLY,

    /** Refresh rate plus 1080p<->2160p resolution to match the content. */
    REFRESH_AND_RESOLUTION;

    companion object {
        fun fromName(name: String?): AfrMode =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** One entry of `Display.getSupportedModes()`, reduced to what the policy needs. */
data class DisplayModeInfo(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshHz: Float,
)

object FrameRateMatchPolicy {

    /**
     * NTSC-style rates are the nominal rate divided by 1.001 (23.976, 29.97,
     * 59.94, 119.88). Relative tolerance 0.2 % folds them onto their family
     * without also folding e.g. 24 onto 25.
     */
    private const val FAMILY_RELATIVE_TOLERANCE = 0.002f

    /** Content rates worth matching; anything else is treated as unknown. */
    private val KNOWN_RATES = intArrayOf(24, 25, 30, 48, 50, 60, 100, 120)

    /** Below this a reported rate is a placeholder (0/-1) or a slideshow, not video. */
    private const val MIN_RATE = 20f

    /**
     * Fold a reported frame/refresh rate onto its integer family: 23.976 -> 24,
     * 29.97 -> 30, 59.94 -> 60. Returns null for unknown (<= 0, NaN) or
     * non-standard rates, which must never trigger a display switch.
     */
    fun normalizeRate(rate: Float): Int? {
        if (rate.isNaN() || rate < MIN_RATE) return null
        val nearest = rate.roundToInt()
        if (nearest !in KNOWN_RATES) return null
        return if (abs(rate - nearest) <= nearest * FAMILY_RELATIVE_TOLERANCE) nearest else null
    }

    /** True when [refreshHz] plays [contentRate] without judder (integer multiple). */
    fun refreshFits(refreshHz: Float, contentRate: Int): Boolean {
        val refresh = normalizeRate(refreshHz) ?: return false
        return refresh >= contentRate && refresh % contentRate == 0
    }

    /**
     * Resolution class of a mode or a stream. Only 1080p and 2160p may be
     * switched between; SD content keeps the current resolution and we never
     * pick a mode below 720p.
     */
    enum class ResolutionClass { SD, HD_720, HD_1080, UHD_2160 }

    fun resolutionClass(width: Int, height: Int): ResolutionClass = when {
        height >= 1440 || width >= 3000 -> ResolutionClass.UHD_2160
        height >= 1000 || width >= 1800 -> ResolutionClass.HD_1080
        height >= 700 || width >= 1200 -> ResolutionClass.HD_720
        else -> ResolutionClass.SD
    }

    /**
     * Choose the display mode for the current stream, or null when nothing
     * should change (unknown fps, AFR off, a single mode, or the current mode
     * already fits).
     */
    fun selectMode(
        contentFps: Float,
        contentWidth: Int,
        contentHeight: Int,
        modes: List<DisplayModeInfo>,
        currentModeId: Int,
        afrMode: AfrMode,
    ): Int? {
        if (afrMode == AfrMode.OFF) return null
        if (modes.size < 2) return null
        val rate = normalizeRate(contentFps) ?: return null
        val current = modes.firstOrNull { it.id == currentModeId } ?: return null

        val targetClass = targetResolutionClass(afrMode, current, contentWidth, contentHeight, modes)
        val currentClass = resolutionClass(current.width, current.height)
        // Already judder-free at the right size: never switch (every switch blanks).
        if (refreshFits(current.refreshHz, rate) && currentClass == targetClass) return null

        val candidates = modes.filter { mode ->
            if (afrMode == AfrMode.REFRESH_ONLY) {
                mode.width == current.width && mode.height == current.height
            } else {
                resolutionClass(mode.width, mode.height) == targetClass
            }
        }.filter { refreshFits(it.refreshHz, rate) }
        if (candidates.isEmpty()) return null

        val best = candidates.minWith(
            compareBy<DisplayModeInfo> { multiplePreference(rate, normalizeRate(it.refreshHz)!! / rate) }
                // Same family (59.94 vs 60): prefer the one that exactly matches
                // the content's own cadence.
                .thenBy { exactnessError(it.refreshHz, contentFps) }
                // Among equal refreshes keep the larger canvas (e.g. 1080p over
                // 1080i-style variants that share a height), then a stable id.
                .thenByDescending { it.width.toLong() * it.height }
                .thenBy { it.id },
        )
        return best.id.takeIf { it != currentModeId }
    }

    /**
     * Resolution class we want to end up in. REFRESH_ONLY pins the current one;
     * REFRESH_AND_RESOLUTION follows 1080p/2160p content when such modes exist,
     * and SD/720p content keeps whatever is set (never below 720p).
     */
    private fun targetResolutionClass(
        afrMode: AfrMode,
        current: DisplayModeInfo,
        contentWidth: Int,
        contentHeight: Int,
        modes: List<DisplayModeInfo>,
    ): ResolutionClass {
        val currentClass = resolutionClass(current.width, current.height)
        if (afrMode != AfrMode.REFRESH_AND_RESOLUTION) return currentClass
        val wanted = when (resolutionClass(contentWidth, contentHeight)) {
            ResolutionClass.UHD_2160 -> ResolutionClass.UHD_2160
            ResolutionClass.HD_1080 -> ResolutionClass.HD_1080
            // 720p/SD content: leave the panel where it is (upscaling is the
            // TV's job and we must never drop below 720p).
            ResolutionClass.HD_720, ResolutionClass.SD -> return currentClass
        }
        val available = modes.any { resolutionClass(it.width, it.height) == wanted }
        return if (available) wanted else currentClass
    }

    /**
     * Lower is better. 25/30 fps prefer the doubled refresh (50/60 Hz) because
     * native 25/30 Hz modes flicker and are rarely wired to the panel; 24 fps
     * prefers native 24 Hz, then 48/72/120; 50/60 prefer native, then double.
     */
    private fun multiplePreference(rate: Int, multiple: Int): Int = when (rate) {
        25, 30 -> when (multiple) {
            2 -> 0
            1 -> 1
            4 -> 2
            else -> 3 + multiple
        }
        else -> multiple
    }

    /** How far the mode's cadence is from the content's (23.976 vs 24 matters here). */
    private fun exactnessError(refreshHz: Float, contentFps: Float): Float {
        val multiple = (refreshHz / contentFps).roundToInt().coerceAtLeast(1)
        return abs(refreshHz - contentFps * multiple)
    }
}
