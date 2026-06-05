/*
 * Result.kt
 * Tiny result wrapper + user-facing error mapping. Keeps error handling explicit
 * (no silent fallbacks) while still showing friendly, non-technical messages.
 */
package com.iptv.player.util

/** Simple loadable state for one-shot operations. */
sealed class Outcome<out T> {
    data class Success<T>(val data: T) : Outcome<T>()
    data class Failure(val error: AppError) : Outcome<Nothing>()
}

/** Categorised errors mapped to translatable, jargon-free messages. */
enum class AppError(val messageRes: Int) {
    NO_INTERNET(com.iptv.player.R.string.error_no_internet),
    CANNOT_CONNECT(com.iptv.player.R.string.error_cannot_connect),
    BAD_CREDENTIALS(com.iptv.player.R.string.error_bad_credentials),
    SUBSCRIPTION_EXPIRED(com.iptv.player.R.string.error_subscription_expired),
    ACCOUNT_IN_USE(com.iptv.player.R.string.error_account_in_use),
    STREAM_NOT_RESPONDING(com.iptv.player.R.string.error_stream_not_responding),
    EMPTY_PLAYLIST(com.iptv.player.R.string.error_empty_playlist),
    UNKNOWN(com.iptv.player.R.string.error_unknown)
}
