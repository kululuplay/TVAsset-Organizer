package com.iptv.player.player

/**
 * Confirms an underrun episode against the sink's playout clock, not ExoPlayer's
 * media clock (which may be video/system driven). Position samples arrive on the
 * playback thread; underrun events and polls arrive on the application thread.
 * No Android APIs, decoder names or media identifiers are retained here.
 */
internal class AudioUnderrunMonitor {
    data class Observation(
        val pending: Boolean = false,
        val recovered: Boolean = false,
        val underruns: Int = 0,
        val stalledDurationMs: Long = 0L,
    )

    private var positionUs: Long? = null
    private var sampledAtMs: Long? = null
    private var episodeSinceMs: Long? = null
    private var baselineUs: Long? = null
    private var underruns = 0
    private var recovered = false

    @Synchronized
    fun onPosition(positionUs: Long?, nowMs: Long) {
        // An unset position, seek, flush or new AudioTrack breaks continuity.
        // Never compare timestamps across that boundary as proof of a stall.
        if (positionUs == null || positionUs < 0L ||
            this.positionUs?.let { positionUs < it } == true
        ) {
            reset()
        }
        this.positionUs = positionUs?.takeIf { it >= 0L }
        sampledAtMs = this.positionUs?.let { nowMs }
        val baseline = baselineUs
        if (baseline != null && positionUs != null &&
            positionUs - baseline >= RECOVERY_PROGRESS_US
        ) {
            clearEpisode()
            recovered = true
        }
    }

    /** Returns true for the first underrun in a new, bounded observation window. */
    @Synchronized
    fun onUnderrun(nowMs: Long): Boolean {
        if (episodeSinceMs?.let { nowMs - it > LiveAudioStallPolicy.UNDERRUN_WINDOW_MS } == true) {
            clearEpisode()
        }
        val started = episodeSinceMs == null
        if (started) {
            episodeSinceMs = nowMs
            baselineUs = positionUs.takeIf { sampleIsFresh(nowMs) }
            recovered = false
        }
        underruns++
        return started
    }

    @Synchronized
    fun poll(nowMs: Long): Observation {
        val since = episodeSinceMs ?: return Observation(recovered = recovered).also {
            recovered = false
        }
        val duration = nowMs - since
        if (duration < 0L || duration > LiveAudioStallPolicy.UNDERRUN_WINDOW_MS) {
            clearEpisode()
            return Observation()
        }
        // Missing/stale clock samples are UNKNOWN, not a confirmed dead clock.
        val hasClockEvidence = baselineUs != null && positionUs != null && sampleIsFresh(nowMs)
        return Observation(
            pending = true,
            underruns = underruns,
            stalledDurationMs = if (hasClockEvidence) duration else 0L,
        )
    }

    @Synchronized
    fun reset() {
        positionUs = null
        sampledAtMs = null
        clearEpisode()
        recovered = false
    }

    private fun sampleIsFresh(nowMs: Long): Boolean =
        sampledAtMs?.let { nowMs - it in 0L..MAX_SAMPLE_AGE_MS } == true

    private fun clearEpisode() {
        episodeSinceMs = null
        baselineUs = null
        underruns = 0
    }

    companion object {
        const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_SAMPLE_AGE_MS = 1_500L
        private const val RECOVERY_PROGRESS_US = 250_000L
    }
}
