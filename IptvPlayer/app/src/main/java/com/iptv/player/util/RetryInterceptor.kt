/*
 * RetryInterceptor.kt
 * Retries transient network failures a bounded number of times with exponential
 * backoff. IPTV portals on consumer hardware frequently drop a request under
 * load or briefly return 5xx; a couple of quick retries turn those blips into a
 * successful load instead of a user-visible error. Permanent failures (4xx,
 * repeated IO errors) are surfaced immediately so the app stays responsive.
 *
 * Requests here are GET-based (Xtream REST / playlist / update checks), so
 * retrying is safe and idempotent.
 */
package com.iptv.player.util

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RetryInterceptor(
    private val maxRetries: Int = 2,
    private val baseDelayMs: Long = 600L,
    private val maxDelayMs: Long = 2_500L
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0

        while (true) {
            try {
                val response = chain.proceed(request)
                if (response.code in 500..599 && attempt < maxRetries) {
                    response.close()
                    attempt++
                    Logger.w("RetryInterceptor", "HTTP ${response.code} on ${request.url}; retry $attempt/$maxRetries")
                    backoff(attempt)
                    continue
                }
                return response
            } catch (e: IOException) {
                if (attempt >= maxRetries) {
                    Logger.w("RetryInterceptor", "Giving up on ${request.url} after $attempt retries", e)
                    throw e
                }
                attempt++
                Logger.w("RetryInterceptor", "IO error on ${request.url}; retry $attempt/$maxRetries: ${e.message}")
                backoff(attempt)
            }
        }
    }

    /**
     * Sleeps for the backoff window. If interrupted (e.g. the coroutine/call was
     * cancelled) the interrupt flag is restored so the next chain.proceed aborts
     * promptly instead of starting a fresh long network attempt.
     */
    private fun backoff(attempt: Int) {
        val delay = (baseDelayMs * (1L shl (attempt - 1))).coerceAtMost(maxDelayMs)
        try {
            Thread.sleep(delay)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
