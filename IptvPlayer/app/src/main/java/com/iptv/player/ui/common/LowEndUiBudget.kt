package com.iptv.player.ui.common

/**
 * Pure sizing rules for UI resources on weak Android TV sticks. Android-free so
 * the numbers are unit-testable; [com.iptv.player.IptvApp] applies them.
 */
object LowEndUiBudget {

    /** Coil cache sizing for one device class. */
    data class ImageCachePolicy(
        /** Fraction of the app heap for the bitmap cache, or null for Coil's default. */
        val memoryCachePercent: Double?,
        val crossfade: Boolean,
        val diskCacheBytes: Long,
    )

    enum class TrimAction { NONE, TRIM, CLEAR }

    /** Constrained = compatibility profile OR the OS flags the device as low-RAM. */
    fun imageCache(compat: Boolean, lowRamDevice: Boolean): ImageCachePolicy =
        if (compat || lowRamDevice) {
            ImageCachePolicy(
                memoryCachePercent = COMPAT_MEMORY_CACHE_PERCENT,
                crossfade = false,
                diskCacheBytes = COMPAT_DISK_CACHE_BYTES,
            )
        } else {
            ImageCachePolicy(
                memoryCachePercent = null,
                crossfade = true,
                diskCacheBytes = DEFAULT_DISK_CACHE_BYTES,
            )
        }

    /**
     * Reaction to `ComponentCallbacks2.onTrimMemory(level)`. UI_HIDDEN only trims
     * (clears on constrained devices); any "running low" level and above clears.
     */
    fun trimAction(level: Int, constrained: Boolean): TrimAction =
        when {
            level == TRIM_MEMORY_UI_HIDDEN -> if (constrained) TrimAction.CLEAR else TrimAction.TRIM
            level >= TRIM_MEMORY_RUNNING_LOW -> TrimAction.CLEAR
            else -> TrimAction.NONE
        }

    /** Overlay/EPG timers tick half as often on compatibility devices. */
    fun refreshIntervalMs(baseMs: Long, compat: Boolean): Long =
        if (compat) baseMs * COMPAT_REFRESH_MULTIPLIER else baseMs

    private const val COMPAT_MEMORY_CACHE_PERCENT = 0.10
    private const val COMPAT_DISK_CACHE_BYTES = 64L * 1024 * 1024
    private const val DEFAULT_DISK_CACHE_BYTES = 128L * 1024 * 1024
    private const val COMPAT_REFRESH_MULTIPLIER = 2L

    // Mirrors android.content.ComponentCallbacks2 so this file stays JVM-only.
    const val TRIM_MEMORY_RUNNING_LOW = 10
    const val TRIM_MEMORY_UI_HIDDEN = 20
}
