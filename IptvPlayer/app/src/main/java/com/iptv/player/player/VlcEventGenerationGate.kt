package com.iptv.player.player

import java.util.ArrayDeque

/**
 * Binds libVLC's player-wide native callbacks to the MediaChanged boundary of
 * the current play request.
 *
 * A MediaPlayer is intentionally reused for fast channel zaps. Without this
 * gate, an EncounteredError queued by the stream being stopped can be delivered
 * after the next stream starts and incorrectly fail that new channel.
 */
internal class VlcEventGenerationGate {
    private var activeGeneration = 0L
    private var eventGeneration = 0L
    // setMedia calls and their MediaChanged callbacks are FIFO inside one native
    // MediaPlayer. Retain every boundary until its matching event arrives; a
    // single "latest pending" slot lets an old callback impersonate a newer zap.
    private val pendingMediaGenerations = ArrayDeque<Long>()

    @Synchronized
    fun beginPlay(): Long {
        activeGeneration++
        eventGeneration = 0L
        return activeGeneration
    }

    /**
     * Must be called immediately before MediaPlayer.setMedia. Returns false when
     * this queued play was already superseded by a newer channel request.
     */
    @Synchronized
    fun prepareMediaChange(generation: Long): Boolean {
        if (generation != activeGeneration) return false
        pendingMediaGenerations.addLast(generation)
        return true
    }

    /** Remove a queued boundary when setMedia itself throws before it can commit. */
    @Synchronized
    fun cancelPreparedMediaChange(generation: Long) {
        if (
            pendingMediaGenerations.isNotEmpty() &&
            pendingMediaGenerations.peekLast() == generation
        ) {
            pendingMediaGenerations.removeLast()
        }
    }

    /** Called only for libVLC's MediaChanged event. */
    @Synchronized
    fun onMediaChanged() {
        eventGeneration =
            if (pendingMediaGenerations.isEmpty()) 0L
            else pendingMediaGenerations.removeFirst()
    }

    /** True only for callbacks emitted after the current media boundary. */
    @Synchronized
    fun acceptsCurrentMediaEvent(): Boolean =
        eventGeneration != 0L && eventGeneration == activeGeneration

    @Synchronized
    fun isActive(generation: Long): Boolean = generation == activeGeneration

    /** Invalidates callbacks when playback is stopped or the engine is released. */
    @Synchronized
    fun invalidate() {
        activeGeneration++
        eventGeneration = 0L
    }
}
