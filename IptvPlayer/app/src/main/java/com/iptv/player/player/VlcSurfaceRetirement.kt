package com.iptv.player.player

/**
 * A SurfaceView removal is a native operation: AWindow's surfaceDestroyed
 * callback calls MediaPlayer.setVideoTrackEnabled. A timeout is not proof that
 * this callback is safe. Keep the removal until the exact owner has released.
 * No native action or UI callback runs while holding this Java-only lock.
 */
internal class VlcSurfaceRetirement {
    private var released = false
    private var removal: (() -> Unit)? = null
    private var removed = false

    fun requestRemoval(action: () -> Unit) {
        val ready = synchronized(this) {
            if (removed) return
            removal = action
            takeReadyRemoval()
        }
        ready?.invoke()
    }

    fun ownershipReleased() {
        val ready = synchronized(this) {
            released = true
            takeReadyRemoval()
        }
        ready?.invoke()
    }

    private fun takeReadyRemoval(): (() -> Unit)? {
        if (!released || removed) return null
        val ready = removal ?: return null
        removed = true
        removal = null
        return ready
    }
}
