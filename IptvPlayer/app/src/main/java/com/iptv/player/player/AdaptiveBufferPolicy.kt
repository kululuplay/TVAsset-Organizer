package com.iptv.player.player

import com.iptv.player.data.model.BufferMode

/**
 * Resolves the user-visible buffer preference to one of the proven concrete
 * profiles. Explicit LOW/NORMAL/HIGH choices are never changed. ADAPTIVE starts
 * at NORMAL on constrained devices and LOW elsewhere and grows after rebuffers.
 * Constrained devices skip the LOW tier but may still reach HIGH once repeated
 * rebuffers prove the link (not memory) is the problem: a weak Wi-Fi stick that
 * stalls every minute is worse off than one holding a few more MiB of samples,
 * and LiveLoadControl's byte target still bounds the memory cost.
 */
internal object AdaptiveBufferPolicy {

    fun resolve(
        configured: BufferMode,
        lowRamDevice: Boolean,
        recentRebuffers: Int,
    ): BufferMode {
        if (configured != BufferMode.ADAPTIVE) return configured
        val rebuffers = recentRebuffers.coerceIn(0, MAX_REBUFFER_HISTORY)
        return when {
            rebuffers >= CONSTRAINED_HIGH_REBUFFERS -> BufferMode.HIGH
            lowRamDevice -> BufferMode.NORMAL
            rebuffers == 0 -> BufferMode.LOW
            else -> BufferMode.NORMAL
        }
    }

    /**
     * A remote per-device override may replace only the user's ADAPTIVE choice.
     * An explicit LOW/NORMAL/HIGH the user picked in Settings always wins.
     */
    fun configuredWithOverride(user: BufferMode, remoteOverride: BufferMode?): BufferMode =
        if (user == BufferMode.ADAPTIVE && remoteOverride != null) remoteOverride else user

    private const val MAX_REBUFFER_HISTORY = 6

    /** Rebuffers in one session before a constrained device is allowed HIGH. */
    private const val CONSTRAINED_HIGH_REBUFFERS = 3
}
