package com.iptv.player.player

import java.util.concurrent.atomic.AtomicBoolean

/** Exactly one outcome, even if a retired JNI worker returns after its deadline. */
internal class BoundedPlaybackWait(
    private val onComplete: () -> Unit,
    private val onTimeout: () -> Unit,
) {
    private val finished = AtomicBoolean(false)
    fun complete() { if (finished.compareAndSet(false, true)) onComplete() }
    fun timeout() { if (finished.compareAndSet(false, true)) onTimeout() }
}
