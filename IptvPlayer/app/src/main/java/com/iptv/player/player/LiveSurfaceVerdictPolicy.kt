package com.iptv.player.player

/**
 * Pure decisions for live Exo pixel verdicts (solid green, persistent blank,
 * validation deadline) versus Media3's own frame evidence.
 *
 * PixelCopy reads only the SurfaceView buffer. On overlay/underlay SoCs
 * (Amlogic-class sticks) the decoded picture lives on a hardware plane, so the
 * copy can return black, or a zeroed YUV placeholder that converts to solid
 * green, while the viewer sees valid video. When a VIDEO verdict cannot reach
 * another stage the controller only reopens the same Exo stage, which yields
 * the same verdict: five ~10 s sessions, then an error screen (API 25 stick,
 * 26 Sep 2026, other IPTV apps playing the same channels). Here, and only
 * until PixelCopy has shown one healthy frame on the stream, pixel verdicts
 * are advisory and sustained native frames are accepted instead, as
 * Media3VodEngine already does for startup colour.
 *
 * Wherever another video stage exists the verdicts stay authoritative, exactly
 * as in 1.5.98 (green decoder output must still move down the ladder).
 */
internal object LiveSurfaceVerdictPolicy {

    /** Remote `nativeFrameTrust`: false = STRICT, unset = DEAD_END_ONLY, true = ALWAYS. */
    enum class Trust { STRICT, DEAD_END_ONLY, ALWAYS }

    enum class Verdict {
        /** Report the pixel verdict as invalid video (1.5.98 behaviour). */
        INVALID,
        /** Accept Media3's native frames as the video-output proof. */
        ACCEPT_NATIVE,
        /** Advisory verdict without live frames yet: the deadline decides. */
        IGNORE,
        /** Media3 is rebuffering at the deadline: look again shortly. */
        DEFER,
    }

    const val MIN_TOTAL_FRAMES = 24
    const val MIN_FRAMES_LAST_SECOND = 8
    const val EARLY_ACCEPT_MS = 1_000L
    /** A green readback may be a decoder that is still settling. */
    const val EARLY_ACCEPT_AFTER_GREEN_MS = 3_000L
    /** Absolute bound after the first frame while the deadline is deferred. */
    const val DEADLINE_DEFER_LIMIT_MS = 15_000L
    /** Longest an advisory PixelCopy request may block the main thread (monitor SAMPLE_TIMEOUT_MS). */
    const val INLINE_COPY_BUDGET_MS = 4_000L
    /** The same on weak devices, where native frames can stand in for pixels. */
    const val CONSTRAINED_INLINE_COPY_BUDGET_MS = 1_500L

    fun trustOf(override: Boolean?): Trust = when (override) {
        false -> Trust.STRICT
        null -> Trust.DEAD_END_ONLY
        true -> Trust.ALWAYS
    }

    /** One healthy PixelCopy sample on the stream makes pixels authoritative again. */
    fun pixelsAdvisory(
        trust: Trust,
        fallbackAvailable: Boolean,
        provenThisStream: Boolean,
    ): Boolean =
        !provenThisStream &&
            (trust == Trust.ALWAYS || (trust == Trust.DEAD_END_ONLY && !fallbackAvailable))

    /**
     * Sustained decoder output, not a trickle: the Xiaomi MiTV interlaced stall
     * (~5 frames, then none) and a 1 fps slideshow both stay below this.
     */
    fun nativeLive(snapshot: NativeFrameCadence.Snapshot, ready: Boolean): Boolean =
        ready &&
            snapshot.total >= MIN_TOTAL_FRAMES &&
            snapshot.lastSecond >= MIN_FRAMES_LAST_SECOND

    fun earlyAccept(
        advisory: Boolean,
        live: Boolean,
        sinceFirstFrameMs: Long,
        greenSeen: Boolean,
    ): Boolean =
        advisory && live &&
            sinceFirstFrameMs >= if (greenSeen) EARLY_ACCEPT_AFTER_GREEN_MS else EARLY_ACCEPT_MS

    /** Startup solid-green / persistent-blank result of the surface monitor. */
    fun onGateVerdict(advisory: Boolean, live: Boolean, verified: Boolean): Verdict = when {
        !advisory -> Verdict.INVALID
        live && !verified -> Verdict.ACCEPT_NATIVE
        else -> Verdict.IGNORE
    }

    /**
     * Inline-copy budget of the live Exo surface monitor. Only advisory pixels
     * may stop sampling on a slow copy (and space the next one out): where the
     * pixel verdicts stay authoritative the monitor keeps the 1.5.98 cadence
     * with no budget, so a slow copy is still classified before the deadline
     * and real green output still moves down the ladder.
     */
    fun inlineCopyBudgetMs(advisory: Boolean, constrainedDevice: Boolean): Long = when {
        !advisory -> Long.MAX_VALUE
        constrainedDevice -> CONSTRAINED_INLINE_COPY_BUDGET_MS
        else -> INLINE_COPY_BUDGET_MS
    }

    /**
     * The monitor stopped because one copy blocked past [inlineCopyBudgetMs],
     * without classifying it. Slowness proves nothing about the picture: accept
     * only what the early-accept poll would; otherwise the poll and the
     * deadline decide, as for any unproven surface.
     */
    fun onSlowInlineCopy(
        advisory: Boolean,
        live: Boolean,
        sinceFirstFrameMs: Long,
        greenSeen: Boolean,
    ): Verdict =
        if (earlyAccept(advisory, live, sinceFirstFrameMs, greenSeen)) {
            Verdict.ACCEPT_NATIVE
        } else {
            Verdict.IGNORE
        }

    /** The PIXEL_VALIDATION_DEADLINE_MS runnable found no verified output. */
    fun onDeadline(
        advisory: Boolean,
        live: Boolean,
        ready: Boolean,
        sinceFirstFrameMs: Long,
    ): Verdict = when {
        !advisory -> Verdict.INVALID
        live -> Verdict.ACCEPT_NATIVE
        !ready && sinceFirstFrameMs < DEADLINE_DEFER_LIMIT_MS -> Verdict.DEFER
        else -> Verdict.INVALID
    }
}
