/*
 * SpeedTester.kt
 * Measures the customer's real download bandwidth by streaming a large file from
 * a CDN for ~10 seconds and dividing bytes-read by elapsed time. Emits a live
 * Mbps estimate via [onProgress] (delivered on the main thread) so the UI can
 * animate while the test runs, and returns the final average. Returns -1.0 when
 * the test could not run (no connection / server error).
 */
package com.iptv.player.util

import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

object SpeedTester {

    // Keyless speed-test endpoints that serve an arbitrary byte count from a global
    // CDN, so the result reflects the customer's true line speed rather than a
    // possibly throttled IPTV origin. The cap is large enough that even a fast line
    // never drains it within the window; we always stop on the timer. We try them
    // in order so a single CDN being unreachable/blocked on the customer's network
    // doesn't doom the whole test.
    private val DOWNLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=500000000",
        "https://speed.hetzner.de/1GB.bin"
    )
    // Some CDN/WAF front-ends (notably Cloudflare) challenge or 403 requests that
    // carry a bare, non-browser User-Agent. Send a realistic browser UA so the
    // download isn't blocked; ServiceLocator's UA interceptor leaves this intact
    // because it only stamps the app identity when no UA is already set.
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val DURATION_NS = 10_000_000_000L
    private const val EMIT_INTERVAL_NS = 250_000_000L
    private const val BUFFER_SIZE = 64 * 1024

    // A dedicated client derived from the shared one but WITHOUT the RetryInterceptor
    // and with a hard call timeout. The measurement window is ~10s, so retries +
    // backoff (and the shared 20s read timeout) would only stretch a doomed attempt
    // for minutes; capping each attempt keeps the worst-case "failed" wait bounded
    // even when both endpoints are unreachable.
    private val speedClient: OkHttpClient by lazy {
        ServiceLocator.httpClient.newBuilder()
            .apply { interceptors().removeAll { it is RetryInterceptor } }
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun measureMbps(onProgress: (Double) -> Unit): Double {
        for (url in DOWNLOAD_URLS) {
            val mbps = measureFrom(url, onProgress)
            if (mbps >= 0) return mbps
        }
        return -1.0
    }

    private suspend fun measureFrom(
        url: String,
        onProgress: (Double) -> Unit
    ): Double = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()
        val call = speedClient.newCall(request)

        // Tie the blocking OkHttp call to the coroutine lifecycle: if the coroutine
        // is cancelled (panel switch / onPause), abort the call so a blocked read()
        // returns immediately instead of lingering on the network.
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }

        var total = 0L
        val start = System.nanoTime()
        var lastEmit = start

        val ok = try {
            call.execute().use { resp ->
                val input = resp.body?.byteStream()
                if (!resp.isSuccessful || input == null) return@use false
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    val now = System.nanoTime()
                    if (now - start >= DURATION_NS) break
                    if (now - lastEmit >= EMIT_INTERVAL_NS) {
                        lastEmit = now
                        val secs = (now - start) / 1e9
                        if (secs > 0) {
                            val mbps = total * 8.0 / 1_000_000.0 / secs
                            withContext(Dispatchers.Main) { onProgress(mbps) }
                        }
                    }
                }
                true
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        } finally {
            cancelHandle?.dispose()
        }

        if (!ok || total <= 0L) return@withContext -1.0
        val secs = (System.nanoTime() - start) / 1e9
        if (secs > 0) total * 8.0 / 1_000_000.0 / secs else -1.0
    }
}
