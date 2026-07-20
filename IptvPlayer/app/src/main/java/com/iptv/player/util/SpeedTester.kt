/*
 * SpeedTester.kt
 * Measures the customer's real download bandwidth the way browser speed tests
 * (Ookla / fast.com) do: SEVERAL parallel HTTP/1.1 connections to a CDN, a
 * warm-up period that is excluded from the average (TCP slow-start + TLS ramp),
 * and a clock that starts at the FIRST RECEIVED BYTE rather than at request
 * creation. Emits a live Mbps estimate via [onProgress] (delivered on the main
 * thread without ever blocking the download loops) and returns the final
 * average. Returns -1.0 when the test could not run (no connection / server
 * error).
 *
 * Why parallel: a single TCP stream is capped by congestion-window growth and
 * any packet loss on the path — 4-5 Mbps on a fast line is a classic
 * single-stream artifact, which is exactly why browser tests open 4-16 sockets.
 * HTTP/1.1 is forced because OkHttp would otherwise multiplex all "parallel"
 * requests onto ONE HTTP/2 connection (one congestion window + h2 flow-control
 * windows), silently defeating the parallelism.
 */
package com.iptv.player.util

import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

object SpeedTester {

    // Keyless speed-test endpoints that serve an arbitrary byte count from a global
    // CDN, so the result reflects the customer's true line speed rather than a
    // possibly throttled IPTV origin. The cap is large enough that even a fast line
    // never drains it within the window; we always stop on the timer. We try them
    // in order so a single CDN being unreachable/blocked on the customer's network
    // doesn't doom the whole test.
    // Several geographically diverse mirrors: a single host failing DNS (e.g.
    // speed.hetzner.de returning UnknownHostException on some ISPs) or being
    // WAF-challenged (Cloudflare 403 on a bare network) must not doom the test.
    // Cloudflare's anycast __down is first (closest PoP, most reliable globally);
    // OVH, Tele2 and Hetzner are HTTPS fallbacks if the customer's network can't
    // reach the one ahead of it.
    private val DOWNLOAD_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=500000000",
        "https://proof.ovh.net/files/1Gb.dat",
        "https://speedtest.tele2.net/1GB.zip",
        "https://speed.hetzner.de/1GB.bin"
    )

    // Some CDN/WAF front-ends (notably Cloudflare) challenge or 403 requests that
    // carry a bare, non-browser User-Agent. Send a realistic browser UA so the
    // download isn't blocked; ServiceLocator's UA interceptor leaves this intact
    // because it only stamps the app identity when no UA is already set.
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Four sockets saturate a typical home line without stressing weak TV boxes:
    // they already sustain a 4K TLS video stream, so four bulk downloads are cheap.
    private const val PARALLEL_STREAMS = 4

    // Bytes/time before this mark (measured from the first received byte) are
    // EXCLUDED from the final average: TCP slow-start and the per-connection TLS
    // ramp would otherwise drag the mean well below the line's steady rate.
    private const val WARMUP_NS = 1_500_000_000L

    // Counted measurement window after the warm-up (total active test ~9.5s).
    private const val MEASURE_NS = 8_000_000_000L

    // If no stream has produced a single byte within this budget the endpoint is
    // unreachable/blocked -> fail this URL so the caller tries the next mirror.
    private const val CONNECT_WAIT_NS = 8_000_000_000L

    private const val EMIT_INTERVAL_MS = 250L
    private const val BUFFER_SIZE = 64 * 1024

    // A dedicated client derived from the shared one but WITHOUT the
    // RetryInterceptor and with a hard call timeout: retries + backoff would only
    // stretch a doomed attempt for minutes, and the timeout bounds the worst-case
    // "failed" wait even when every endpoint is unreachable. HTTP/1.1 is forced so
    // each parallel request really gets its own TCP connection (see file header).
    private val speedClient: OkHttpClient by lazy {
        ServiceLocator.httpClient.newBuilder()
            .apply { interceptors().removeAll { it is RetryInterceptor } }
            .protocols(listOf(Protocol.HTTP_1_1))
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Runs the test against the public CDN mirrors first (true unthrottled line
     * speed), then against any [fallbackUrls] — typically the customer's own IPTV
     * portal. Many IPTV boxes sit on locked-down networks that only whitelist the
     * portal host, so every public mirror is unreachable and the test would show
     * a bare failure; the portal is the one endpoint guaranteed to be reachable
     * (their streams play), so it keeps the test from erroring out entirely.
     */
    suspend fun measureMbps(
        fallbackUrls: List<String> = emptyList(),
        onProgress: (Double) -> Unit
    ): Double {
        for (url in DOWNLOAD_URLS + fallbackUrls) {
            val mbps = measureFrom(url, onProgress)
            if (mbps >= 0) return mbps
        }
        return -1.0
    }

    private suspend fun measureFrom(
        url: String,
        onProgress: (Double) -> Unit
    ): Double = withContext(Dispatchers.IO) {
        // Shared counters the reader coroutines feed; the orchestrator below only
        // ever READS them, so progress emission can never stall a download loop.
        val totalBytes = AtomicLong(0L)
        val firstByteAtNs = AtomicLong(0L)

        try {
            coroutineScope {
                val readers = List(PARALLEL_STREAMS) {
                    launch { streamOnce(url, totalBytes, firstByteAtNs) }
                }

                // Wait for the first byte from ANY stream. Connect/DNS/TLS time is
                // deliberately NOT part of the measurement — the old version started
                // the clock before the request was even sent, which billed the
                // handshake as seconds of zero-byte transfer.
                val connectDeadline = System.nanoTime() + CONNECT_WAIT_NS
                while (firstByteAtNs.get() == 0L) {
                    if (readers.all { it.isCompleted } || System.nanoTime() >= connectDeadline) {
                        readers.forEach { it.cancel() }
                        return@coroutineScope -1.0
                    }
                    delay(50)
                }

                val t0 = firstByteAtNs.get()
                var warmDone = false
                var warmBytes = 0L
                var warmAtNs = t0

                while (true) {
                    delay(EMIT_INTERVAL_MS)
                    val now = System.nanoTime()
                    val bytes = totalBytes.get()

                    if (!warmDone && now - t0 >= WARMUP_NS) {
                        // Snapshot at the end of the warm-up; the final average only
                        // counts bytes/time past this point.
                        warmDone = true
                        warmBytes = bytes
                        warmAtNs = now
                    }

                    // Live estimate: once the post-warm-up window has enough data,
                    // show its rate (matches the final number); before that, show
                    // the since-first-byte average so the needle moves immediately.
                    val liveMbps = if (warmDone && now - warmAtNs >= 750_000_000L) {
                        (bytes - warmBytes) * 8.0 / 1_000_000.0 / ((now - warmAtNs) / 1e9)
                    } else if (now > t0) {
                        bytes * 8.0 / 1_000_000.0 / ((now - t0) / 1e9)
                    } else {
                        -1.0
                    }
                    if (liveMbps >= 0) {
                        withContext(Dispatchers.Main) { onProgress(liveMbps) }
                    }

                    val allDone = readers.all { it.isCompleted }
                    if (allDone || (warmDone && now - warmAtNs >= MEASURE_NS)) break
                }

                val endNs = System.nanoTime()
                val endBytes = totalBytes.get()
                readers.forEach { it.cancel() }

                if (warmDone && endNs > warmAtNs && endBytes > warmBytes) {
                    // Normal path: steady-state average over the post-warm-up window.
                    (endBytes - warmBytes) * 8.0 / 1_000_000.0 / ((endNs - warmAtNs) / 1e9)
                } else if (endBytes > 0L && endNs > t0) {
                    // Streams drained/died before the warm-up window filled (small
                    // fallback file, flaky mirror): fall back to the overall average
                    // rather than reporting a failure for a test that moved data.
                    endBytes * 8.0 / 1_000_000.0 / ((endNs - t0) / 1e9)
                } else {
                    -1.0
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            -1.0
        }
    }

    /**
     * One download leg: opens [url], streams the body and feeds the shared
     * counters. Failures are swallowed (the orchestrator judges the test by the
     * byte counters, and sibling streams keep running); cancellation propagates.
     */
    private suspend fun streamOnce(
        url: String,
        totalBytes: AtomicLong,
        firstByteAtNs: AtomicLong
    ) {
        // Guard request construction: Request.Builder().url(String) throws on a
        // malformed URL, so a bad fallback target must fail this leg quietly
        // rather than crash the whole coroutine.
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return
        }
        val call = speedClient.newCall(request)

        // Tie the blocking OkHttp call to the coroutine lifecycle: if this leg is
        // cancelled (timer elapsed / panel switch / onPause), abort the call so a
        // blocked read() returns immediately instead of lingering on the network.
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }

        try {
            call.execute().use { resp ->
                val input = resp.body?.byteStream()
                if (!resp.isSuccessful || input == null) return@use
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        firstByteAtNs.compareAndSet(0L, System.nanoTime())
                        totalBytes.addAndGet(n.toLong())
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // This leg failed (403 challenge, reset, timeout). Siblings continue;
            // if every leg fails the byte counter stays at zero and the
            // orchestrator reports -1 so the next mirror is tried.
        } finally {
            cancelHandle?.dispose()
        }
    }
}
