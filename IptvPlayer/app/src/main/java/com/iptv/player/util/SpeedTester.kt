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
import okhttp3.Request
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

object SpeedTester {

    // Cloudflare's keyless speed-test endpoint serves an arbitrary byte count from
    // a global CDN, so the result reflects the customer's true line speed rather
    // than a possibly throttled IPTV origin. The cap is large enough that even a
    // fast line never drains it within the window; we always stop on the timer.
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=500000000"
    private const val DURATION_NS = 10_000_000_000L
    private const val EMIT_INTERVAL_NS = 250_000_000L
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun measureMbps(onProgress: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val client = ServiceLocator.httpClient
        val call = client.newCall(Request.Builder().url(DOWNLOAD_URL).build())

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
