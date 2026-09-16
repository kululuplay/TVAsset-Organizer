package com.iptv.player.playback.core

enum class CodecQueueMode { DEFAULT, ASYNC }

data class CodecEvidence(
    val baselineBadWindows: Int = 0,
    val baselineDropPermille: Int = 0,
    val asyncGoodSessions: Int = 0,
    val blockedUntilMs: Long = 0,
    val updatedAtMs: Long = 0,
)

/** Never experiments because of network buffering. Only hardware video on API 23–30. */
object VerifiedCodecPolicy {
    const val TTL_MS = 7L * 24 * 60 * 60 * 1_000

    fun select(sdk: Int, hardware: Boolean, secure: Boolean, evidence: CodecEvidence?, nowMs: Long): CodecQueueMode {
        if (sdk !in 23..30 || !hardware || secure || evidence == null) return CodecQueueMode.DEFAULT
        if (nowMs < evidence.updatedAtMs || nowMs - evidence.updatedAtMs > TTL_MS) return CodecQueueMode.DEFAULT
        if (evidence.blockedUntilMs > nowMs) return CodecQueueMode.DEFAULT
        return if (evidence.asyncGoodSessions >= 2 || evidence.baselineBadWindows >= 3) {
            CodecQueueMode.ASYNC
        } else CodecQueueMode.DEFAULT
    }

    fun observe(
        prior: CodecEvidence,
        mode: CodecQueueMode,
        rendered: Long,
        dropped: Long,
        activeMs: Long,
        healthyBuffer: Boolean,
        nowMs: Long,
        allowPromotion: Boolean = true,
    ): CodecEvidence {
        // A minute of continuously advancing, well-buffered video. A rapid zap,
        // seek, stopped clock, audio-only stream or starved stream proves nothing.
        if (!healthyBuffer || activeMs < 60_000 || rendered < 600 || dropped < 0) return prior
        val rate = (dropped.coerceAtMost(1_000_000) * 1_000 / (rendered + dropped).coerceAtLeast(1)).toInt()
        return when (mode) {
            CodecQueueMode.DEFAULT -> if (rate >= 50) prior.copy(
                baselineBadWindows = (prior.baselineBadWindows + 1).coerceAtMost(3),
                baselineDropPermille = rate,
                updatedAtMs = nowMs,
            ) else prior.copy(baselineBadWindows = 0, updatedAtMs = nowMs)
            CodecQueueMode.ASYNC -> if (rate <= 5 && rate * 2 < prior.baselineDropPermille) {
                prior.copy(asyncGoodSessions = (prior.asyncGoodSessions + if (allowPromotion) 1 else 0).coerceAtMost(2), updatedAtMs = nowMs)
            } else failed(prior, nowMs)
        }
    }

    fun failed(prior: CodecEvidence, nowMs: Long): CodecEvidence = prior.copy(
        baselineBadWindows = 0,
        asyncGoodSessions = 0,
        blockedUntilMs = nowMs + TTL_MS,
        updatedAtMs = nowMs,
    )
}
