package com.iptv.player.player

import com.iptv.player.data.model.BufferMode

/**
 * Resolves the user-visible buffer preference to one of the proven concrete
 * profiles. Explicit LOW/NORMAL/HIGH choices are never changed. ADAPTIVE starts
 * fast, grows only after real rebuffers, and caps low-RAM devices at NORMAL so a
 * weak stick cannot trade playback stalls for memory pressure/OOM kills.
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
            rebuffers == 0 -> BufferMode.LOW
            lowRamDevice -> BufferMode.NORMAL
            rebuffers <= 2 -> BufferMode.NORMAL
            else -> BufferMode.HIGH
        }
    }

    private const val MAX_REBUFFER_HISTORY = 6
}
