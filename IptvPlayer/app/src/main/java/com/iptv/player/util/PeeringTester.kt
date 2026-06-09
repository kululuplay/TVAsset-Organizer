/*
 * PeeringTester.kt
 * Runs a ~3-minute COMPARATIVE network-quality test from the customer's box to
 * several targets at once and works out whether a streaming problem is a
 * peering/route issue toward OUR server or the customer's own line.
 *
 * Why TCP-connect (not MTR/ping): a non-root Android TV box can't send raw ICMP,
 * so there's no per-hop traceroute/MTR available; our server host also blocks
 * ICMP. A plain TCP connect to an OPEN port costs exactly one network round trip
 * (SYN -> SYN/ACK), so its timing is a sound latency proxy and a connect timeout
 * is a usable loss signal — all with stock java.net, no permission, no native code.
 *
 * Each target runs its own once-per-second loop; success = an RTT sample, a
 * timeout = loss. We compare OUR server against well-peered anycast anchors
 * (Cloudflare/Google): if our server is bad while the anchors are healthy, the
 * problem is on the path toward us; if every target is bad it's the local line.
 */
package com.iptv.player.util

import android.content.Context
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

object PeeringTester {

    /** Our IPTV/panel server. Port 8080 is the open service port (verified). */
    const val SERVER_HOST = "212.95.63.171"
    const val SERVER_PORT = 8080

    private const val DEFAULT_DURATION_MS = 180_000L
    private const val DEFAULT_INTERVAL_MS = 1_000L
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 2_000
    // A connect that only completes after a TCP SYN retransmit (Linux RTO ~1s)
    // reports ~1000ms+ instead of failing; treat such samples as loss rather than
    // letting a retransmit artifact masquerade as a (terrible) good RTT.
    private const val LOSS_THRESHOLD_MS = 800.0
    // Minimum attempts before we trust a verdict.
    private const val MIN_SAMPLES = 30

    /** A host to probe. Anchors are anycast IP literals (no DNS, nearest PoP). */
    data class PeerTarget(val label: String, val host: String, val port: Int, val isServer: Boolean)

    /** Immutable snapshot of one target's running stats (all ms values rounded). */
    data class PeerStat(
        val label: String,
        val isServer: Boolean,
        val attempts: Int,
        val samples: Int,
        val lossCount: Int,
        val refusedCount: Int,
        val minMs: Int,
        val avgMs: Int,
        val maxMs: Int,
        val jitterMs: Int,
    ) {
        val lossPct: Int get() = if (attempts > 0) (lossCount * 100) / attempts else 0
        val reachable: Boolean get() = samples > 0 || refusedCount > 0
    }

    enum class Verdict { OK, PEERING, CUSTOMER_LINE, SERVER_DOWN, INCONCLUSIVE }

    /** Default target set: our server + two well-peered anycast anchors. */
    fun defaultTargets(serverLabel: String): List<PeerTarget> = listOf(
        PeerTarget(serverLabel, SERVER_HOST, SERVER_PORT, isServer = true),
        PeerTarget("Cloudflare", "1.1.1.1", 443, isServer = false),
        PeerTarget("Google", "8.8.8.8", 443, isServer = false),
    )

    private sealed interface Outcome {
        data class Rtt(val ms: Double) : Outcome
        object Timeout : Outcome
        object Refused : Outcome
    }

    /** Thread-safe accumulator for one target. */
    private class Acc(val target: PeerTarget) {
        private var attempts = 0
        private var samples = 0
        private var sumMs = 0.0
        private var minMs = Double.MAX_VALUE
        private var maxMs = 0.0
        private var lastMs = -1.0
        private var jitterSum = 0.0
        private var jitterPairs = 0
        private var lossCount = 0
        private var refusedCount = 0

        @Synchronized
        fun add(o: Outcome) {
            attempts++
            when (o) {
                is Outcome.Rtt -> if (o.ms >= LOSS_THRESHOLD_MS) {
                    lossCount++
                } else {
                    samples++
                    sumMs += o.ms
                    if (o.ms < minMs) minMs = o.ms
                    if (o.ms > maxMs) maxMs = o.ms
                    if (lastMs >= 0) {
                        jitterSum += abs(o.ms - lastMs)
                        jitterPairs++
                    }
                    lastMs = o.ms
                }
                Outcome.Timeout -> lossCount++
                Outcome.Refused -> refusedCount++
            }
        }

        @Synchronized
        fun snapshot(): PeerStat = PeerStat(
            label = target.label,
            isServer = target.isServer,
            attempts = attempts,
            samples = samples,
            lossCount = lossCount,
            refusedCount = refusedCount,
            minMs = if (samples > 0) minMs.toInt() else 0,
            avgMs = if (samples > 0) (sumMs / samples).toInt() else 0,
            maxMs = maxMs.toInt(),
            jitterMs = if (jitterPairs > 0) (jitterSum / jitterPairs).toInt() else 0,
        )
    }

    /**
     * Runs every target in parallel for [durationMs]. [onProgress] is delivered on
     * the main thread roughly twice a second with the live stats and the remaining
     * milliseconds (for a countdown). Returns the final per-target stats.
     */
    suspend fun run(
        targets: List<PeerTarget>,
        durationMs: Long = DEFAULT_DURATION_MS,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        onProgress: (List<PeerStat>, Long) -> Unit,
    ): List<PeerStat> = coroutineScope {
        val accs = targets.map { Acc(it) }
        val deadline = System.nanoTime() + durationMs * 1_000_000

        val probeJobs = targets.indices.map { idx ->
            launch(Dispatchers.IO) {
                val t = targets[idx]
                val acc = accs[idx]
                while (System.nanoTime() < deadline) {
                    coroutineContext.ensureActive()
                    val started = System.nanoTime()
                    acc.add(probe(t.host, t.port, connectTimeoutMs))
                    val spentMs = (System.nanoTime() - started) / 1_000_000
                    val sleep = intervalMs - spentMs
                    if (sleep > 0) delay(sleep)
                }
            }
        }

        // Emit live stats + a countdown until the probe loops drain at the deadline.
        val emitter = launch {
            while (isActive) {
                val snap = accs.map { it.snapshot() }
                val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
                withContext(Dispatchers.Main) { onProgress(snap, remaining) }
                delay(500)
            }
        }

        probeJobs.joinAll()
        emitter.cancel()
        accs.map { it.snapshot() }
    }

    /** One TCP-connect attempt. Closes the socket on every path and on cancellation. */
    private suspend fun probe(host: String, port: Int, timeoutMs: Int): Outcome {
        val socket = Socket()
        // Abort a blocked connect promptly if the coroutine is cancelled (panel
        // leave / onStop), mirroring SpeedTester tying the OkHttp call to the Job.
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { socket.close() }
        }
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            Outcome.Rtt((System.nanoTime() - start) / 1_000_000.0)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: SocketTimeoutException) {
            Outcome.Timeout
        } catch (_: ConnectException) {
            // RST: the host answered but the port is closed -> server reachable,
            // service down (NOT path loss).
            Outcome.Refused
        } catch (_: Throwable) {
            // No route / unreachable / socket closed by cancellation -> loss.
            Outcome.Timeout
        } finally {
            cancelHandle?.dispose()
            runCatching { socket.close() }
        }
    }

    /**
     * Classifies the result. Anchors are anycast (often <10ms to the nearest PoP)
     * while the server is a single distant host, so RAW RTT always favours the
     * anchors — loss% and jitter are the primary discriminators; RTT only counts
     * with a generous absolute delta.
     */
    fun verdict(stats: List<PeerStat>): Verdict {
        val server = stats.firstOrNull { it.isServer } ?: return Verdict.INCONCLUSIVE
        val anchors = stats.filter { !it.isServer }
        if (server.attempts < MIN_SAMPLES) return Verdict.INCONCLUSIVE

        val bestAnchor = anchors.filter { it.samples >= MIN_SAMPLES }.minByOrNull { it.avgMs }
            ?: return Verdict.INCONCLUSIVE

        // The customer's own line is the problem when even a top-tier anchor is bad.
        if (bestAnchor.lossPct >= 5 || bestAnchor.jitterMs >= 40) return Verdict.CUSTOMER_LINE

        val anchorHealthy = bestAnchor.lossPct < 2 && bestAnchor.jitterMs < 15

        // Server reachable (port answers with RST) but never completes a TCP
        // session = the service is down, not a peering problem.
        if (anchorHealthy && server.samples == 0 && server.refusedCount >= MIN_SAMPLES / 2) {
            return Verdict.SERVER_DOWN
        }

        val serverLossy = server.lossPct >= 5 || server.jitterMs >= 40
        val serverFar = server.samples > 0 &&
            server.avgMs > bestAnchor.avgMs + 120 &&
            server.avgMs > bestAnchor.avgMs * 2.5
        if (anchorHealthy && (serverLossy || serverFar)) return Verdict.PEERING

        return Verdict.OK
    }

    /**
     * Fire-and-forget upload of the (already human-readable) result line so the ops
     * panel can show each box's last peering test. Best-effort; never throws.
     */
    suspend fun upload(context: Context, summary: String) {
        runCatching {
            val json = JSONObject().apply {
                put("deviceId", DeviceId.get(context))
                put("summary", summary.take(1000))
            }
            val body = json.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(Telemetry.NETTEST_ENDPOINT)
                .header("X-Kululu-Key", Telemetry.INGEST_KEY)
                .post(body)
                .build()
            withContext(Dispatchers.IO) {
                ServiceLocator.httpClient.newCall(request).execute().use { /* ignore body */ }
            }
        }
    }
}
