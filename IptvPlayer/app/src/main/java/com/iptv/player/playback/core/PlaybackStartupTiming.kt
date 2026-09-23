package com.iptv.player.playback.core

/** Transport opening includes DNS/TLS/network/server waiting; it is not server processing time. */
data class PlaybackStartupTiming(
    val sourceOpenMs: Long? = null,
    val timeToFirstByteMs: Long? = null,
    val firstByteToFirstFrameMs: Long? = null,
) {
    fun toSafeFields(): Map<String, Any> = buildMap {
        sourceOpenMs?.takeIf { it in 0..MAX_MS }?.let { put("source_open_ms", it) }
        timeToFirstByteMs?.takeIf { it in 0..MAX_MS }?.let { put("time_to_first_byte_ms", it) }
        firstByteToFirstFrameMs?.takeIf { it in 0..MAX_MS }?.let { put("first_byte_to_first_frame_ms", it) }
        if (isNotEmpty()) put("source_timing_scope", "INITIAL_ATTEMPT")
    }
    companion object { private const val MAX_MS = 86_400_000L }
}

/** Small generation-guarded clock shared by loader and player threads. Never retains a URI. */
class PlaybackStartupClock(private val clock: () -> Long) {
    private var generation = 0L
    private var startedAt: Long? = null
    private var openingAt: Long? = null
    private var openedAt: Long? = null
    private var firstByteAt: Long? = null
    private var firstFrameAt: Long? = null
    private var token = 0L
    private var invalid = false

    @Synchronized fun reset(): Long {
        generation++
        startedAt = clock()
        openingAt = null; openedAt = null; firstByteAt = null; firstFrameAt = null
        invalid = false
        return generation
    }
    @Synchronized fun stop() { generation++; startedAt = null; invalid = true }
    @Synchronized fun generation(): Long = generation
    @Synchronized fun initializing(owner: Long, network: Boolean): Long? {
        if (owner != generation || !network || startedAt == null || invalid || firstFrameAt != null) return null
        // A retry or range reopen before first frame cannot be labelled one initial request.
        if (openingAt != null) { invalid = true; return null }
        openingAt = clock()
        return ++token
    }
    @Synchronized fun opened(owner: Long, transfer: Long?) {
        if (valid(owner, transfer) && openedAt == null) openedAt = clock()
    }
    @Synchronized fun bytes(owner: Long, transfer: Long?, count: Int) {
        if (valid(owner, transfer) && openedAt != null && count > 0 && firstByteAt == null) firstByteAt = clock()
    }
    @Synchronized fun firstFrame(renderedAtMs: Long = clock()) {
        val start = startedAt ?: return
        if (firstFrameAt == null && renderedAtMs in start..clock()) firstFrameAt = renderedAtMs
    }
    @Synchronized fun interruptBeforeFirstFrame() { if (firstFrameAt == null) invalid = true }
    @Synchronized fun snapshot(): PlaybackStartupTiming? {
        val start = startedAt ?: return null
        if (invalid) return null
        val opened = openedAt
        val bytes = firstByteAt
        val frame = firstFrameAt
        val result = PlaybackStartupTiming(
            sourceOpenMs = opened?.let { openingAt?.let { begin -> elapsed(begin, it) } },
            timeToFirstByteMs = bytes?.let { elapsed(start, it) },
            firstByteToFirstFrameMs = if (bytes != null && frame != null && frame >= bytes) elapsed(bytes, frame) else null,
        )
        return result.takeIf { it.toSafeFields().isNotEmpty() }
    }
    private fun valid(owner: Long, transfer: Long?) = !invalid && owner == generation && transfer != null && transfer == token
    private fun elapsed(from: Long, to: Long): Long? = (to - from).takeIf { it >= 0 }
}
