/*
 * AppModels.kt
 * App-level models: user profiles (multiple subscriptions), Xtream account info,
 * aspect-ratio cycle, and diagnostics results.
 */
package com.iptv.player.data.model

/** A saved profile = one source/subscription with an optional PIN-locked flag. */
data class Profile(
    val id: Long,
    val name: String,
    val config: SourceConfig,
    val lockAdult: Boolean = false
)

/** Parsed Xtream account status for the diagnostics / account screen. */
data class AccountInfo(
    val status: String?,
    val isActive: Boolean,
    val expiryDateMs: Long?,
    val daysRemaining: Long?,
    val activeConnections: Int?,
    val maxConnections: Int?
)

/** Aspect-ratio modes cycled in the player. */
enum class AspectRatio {
    ORIGINAL, RATIO_16_9, RATIO_4_3, FILL, ZOOM;

    fun next(): AspectRatio = entries[(ordinal + 1) % entries.size]

    val label: String
        get() = when (this) {
            ORIGINAL -> "Original"
            RATIO_16_9 -> "16:9"
            RATIO_4_3 -> "4:3"
            FILL -> "Fill"
            ZOOM -> "Zoom"
        }
}

/** Result of a single diagnostic test. */
data class DiagnosticResult(
    val label: String,
    val ok: Boolean,
    val detail: String
)
