package com.iptv.player.util

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException

/** Bounded retries for read requests. Never replay a mutation, TLS failure or cancellation. */
class RetryInterceptor(
    private val maxRetries: Int = 2,
    private val baseDelayMs: Long = 600L,
    private val maxDelayMs: Long = 2_500L,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method !in setOf("GET", "HEAD")) return chain.proceed(request)
        var retries = 0
        while (true) {
            if (chain.call().isCanceled() || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("Request cancelled")
            }
            val response = try {
                chain.proceed(request)
            } catch (failure: IOException) {
                if (failure is SSLException || chain.call().isCanceled() ||
                    Thread.currentThread().isInterrupted || retries >= maxRetries) throw failure
                waitBeforeRetry(backoff(retries++))
                continue
            }
            if (retries >= maxRetries || (response.code != 429 && response.code !in 500..599)) {
                return response
            }
            val requestedDelay = retryAfter(response.header("Retry-After"))
            // Do not send earlier than a server's wait that exceeds our budget.
            if (requestedDelay != null && requestedDelay > maxDelayMs) return response
            val delay = maxOf(backoff(retries++), requestedDelay ?: 0L)
            response.close()
            waitBeforeRetry(delay)
        }
    }

    private fun backoff(retries: Int): Long =
        (baseDelayMs * (1L shl retries.coerceAtMost(20))).coerceAtMost(maxDelayMs)

    private fun retryAfter(value: String?): Long? {
        value ?: return null
        value.trim().toLongOrNull()?.let {
            return if (it < 0) null else if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000
        }
        return runCatching {
            val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            parser.parse(value)?.time?.minus(now())?.coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun waitBeforeRetry(delay: Long) {
        try { sleep(delay) } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Retry interrupted").apply { initCause(failure) }
        }
    }
}
