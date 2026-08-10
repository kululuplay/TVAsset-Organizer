/*
 * Result.kt
 * Tiny result wrapper + user-facing error mapping. Keeps error handling explicit
 * (no silent fallbacks) while still showing friendly, non-technical messages.
 */
package com.iptv.player.util

/** Simple loadable state for one-shot operations. */
sealed class Outcome<out T> {
    data class Success<T>(val data: T) : Outcome<T>()
    data class Failure(
        val error: AppError,
        /** Exact provider response code when the failure came from HTTP. */
        val httpStatus: Int? = null,
    ) : Outcome<Nothing>() {
        init {
            require(httpStatus == null || httpStatus in 400..599) {
                "HTTP status must be a client or server error"
            }
        }
    }
}

/** Categorised errors mapped to translatable, jargon-free messages. */
enum class AppError(val messageRes: Int) {
    MISSING_CREDENTIALS(com.iptv.player.R.string.error_missing_credentials),
    NO_INTERNET(com.iptv.player.R.string.error_no_internet),
    CANNOT_CONNECT(com.iptv.player.R.string.error_cannot_connect),
    BAD_CREDENTIALS(com.iptv.player.R.string.error_bad_credentials),
    ACCESS_DENIED(com.iptv.player.R.string.error_access_denied),
    ACCOUNT_DISABLED(com.iptv.player.R.string.error_account_disabled),
    SERVICE_NOT_FOUND(com.iptv.player.R.string.error_service_not_found),
    REQUEST_TIMEOUT(com.iptv.player.R.string.error_request_timeout),
    TOO_MANY_REQUESTS(com.iptv.player.R.string.error_too_many_requests),
    SERVER_UNAVAILABLE(com.iptv.player.R.string.error_server_unavailable),
    SECURE_CONNECTION_FAILED(com.iptv.player.R.string.error_secure_connection_failed),
    SUBSCRIPTION_EXPIRED(com.iptv.player.R.string.error_subscription_expired),
    ACCOUNT_IN_USE(com.iptv.player.R.string.error_account_in_use),
    STREAM_NOT_RESPONDING(com.iptv.player.R.string.error_stream_not_responding),
    EMPTY_PLAYLIST(com.iptv.player.R.string.error_empty_playlist),
    UNKNOWN(com.iptv.player.R.string.error_unknown)
}

/** Closed HTTP-to-product mapping shared by login and repository operations. */
object HttpAppErrorPolicy {
    fun fromStatus(status: Int): AppError = when (status) {
        400, 401 -> AppError.BAD_CREDENTIALS
        403, 423 -> AppError.ACCESS_DENIED
        404, 410 -> AppError.SERVICE_NOT_FOUND
        408 -> AppError.REQUEST_TIMEOUT
        409 -> AppError.ACCOUNT_IN_USE
        429 -> AppError.TOO_MANY_REQUESTS
        in 500..599 -> AppError.SERVER_UNAVAILABLE
        else -> AppError.CANNOT_CONNECT
    }
}
