/*
 * Copyright (c) TVAsset Organizer contributors.
 */
package com.iptv.player.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max

/**
 * Bounded, parallel download speed test designed for Android TV hardware.
 *
 * The test deliberately has no IPTV/portal fallback. Downloading a subscriber's
 * stream URL would measure that provider's throttle and could put credentials in
 * an unrelated request path. Ten independent HTTP/1.1 transfers use Cloudflare's
 * purpose-built speed endpoint instead.
 *
 * HTTP body reads happen on this object's private OkHttp dispatcher. The
 * orchestration coroutine only samples atomic counters, and cancellation calls
 * [Call.cancel] immediately, including while DNS, TLS or a body read is blocked.
 */
object SpeedTester {

    enum class Phase {
        CONNECTING,
        WARMING_UP,
        MEASURING
    }

    enum class FailureReason {
        INSUFFICIENT_ACTIVE_STREAMS,
        INSUFFICIENT_SAMPLES,
        INSUFFICIENT_DATA,
        BUSY,
        TIMEOUT,
        INTERNAL_ERROR
    }

    data class Progress(
        val phase: Phase,
        val elapsedMs: Long,
        val mbps: Double,
        val activeStreams: Int,
        val startedStreams: Int,
        val cancelledStreams: Int,
        val completedStreams: Int,
        val failedStreams: Int,
        val attemptedStreams: Int,
        val activeCdnCount: Int,
        val sampleCount: Int
    )

    data class EndpointStats(
        val cdn: String,
        val host: String,
        val attemptedStreams: Int,
        val connectingStreams: Int,
        val activeStreams: Int,
        val stalledStreams: Int,
        val startedStreams: Int,
        val cancelledStreams: Int,
        val completedStreams: Int,
        val failedStreams: Int,
        val transferredBytes: Long,
        val protocols: List<String>
    )

    sealed interface Result {
        val endpointStats: List<EndpointStats>

        data class Success(
            val mbps: Double,
            val medianMbps: Double,
            val peakMbps: Double,
            val measuredBytes: Long,
            val totalDurationMs: Long,
            val measurementDurationMs: Long,
            val samplesMbps: List<Double>,
            val activeStreamHighWaterMark: Int,
            override val endpointStats: List<EndpointStats>
        ) : Result

        data class Failure(
            val reason: FailureReason,
            val diagnostic: String,
            val elapsedMs: Long,
            val validSampleCount: Int,
            val activeStreamHighWaterMark: Int,
            override val endpointStats: List<EndpointStats>
        ) : Result
    }

    private data class Endpoint(
        val name: String,
        val url: String
    ) {
        val host: String = url.toHttpUrlOrNull()?.host.orEmpty()
    }

    private enum class StreamStatus {
        CONNECTING,
        ACTIVE,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private class StreamState(
        val id: Int,
        val endpoint: Endpoint
    ) {
        val bytes = AtomicLong(0L)
        val firstByteAtNs = AtomicLong(0L)
        val lastByteAtNs = AtomicLong(0L)
        val status = AtomicReference(StreamStatus.CONNECTING)
        val protocol = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)

        fun recordBytes(count: Int, nowNs: Long) {
            if (count <= 0 || status.get() == StreamStatus.CANCELLED) return
            firstByteAtNs.compareAndSet(0L, nowNs)
            lastByteAtNs.set(nowNs)
            status.compareAndSet(StreamStatus.CONNECTING, StreamStatus.ACTIVE)
            bytes.addAndGet(count.toLong())
        }

        fun complete() {
            if (bytes.get() > 0L) {
                status.compareAndSet(StreamStatus.ACTIVE, StreamStatus.COMPLETED)
            } else {
                fail("empty_body")
            }
        }

        fun fail(code: String) {
            while (true) {
                val previous = status.get()
                if (
                    previous == StreamStatus.COMPLETED ||
                    previous == StreamStatus.CANCELLED ||
                    previous == StreamStatus.FAILED
                ) {
                    return
                }
                if (status.compareAndSet(previous, StreamStatus.FAILED)) {
                    failure.compareAndSet(null, code)
                    return
                }
            }
        }

        fun cancel() {
            while (true) {
                val previous = status.get()
                if (
                    previous == StreamStatus.COMPLETED ||
                    previous == StreamStatus.FAILED ||
                    previous == StreamStatus.CANCELLED
                ) {
                    return
                }
                if (status.compareAndSet(previous, StreamStatus.CANCELLED)) return
            }
        }

        fun isTransferring(nowNs: Long): Boolean {
            if (status.get() != StreamStatus.ACTIVE) return false
            val lastByteNs = lastByteAtNs.get()
            return lastByteNs > 0L &&
                nowNs - lastByteNs <= ACTIVE_STREAM_FRESHNESS_NS
        }

        fun hasStarted(): Boolean = firstByteAtNs.get() != 0L
    }

    private class RunState(
        val startedAtNs: Long,
        val streams: List<StreamState>,
        val totalBytes: AtomicLong = AtomicLong(0L)
    ) {
        @Volatile
        var activeStreamHighWaterMark: Int = 0

        @Volatile
        var validSampleCount: Int = 0

        fun updateHighWaterMark(nowNs: Long): Int {
            val active = activeStreamCount(nowNs)
            return updateHighWaterMark(active)
        }

        fun updateHighWaterMark(active: Int): Int {
            activeStreamHighWaterMark = max(activeStreamHighWaterMark, active)
            return active
        }

        fun activeStreamCount(nowNs: Long): Int {
            return streams.count { it.isTransferring(nowNs) }
        }
    }

    /*
     * Keep every transfer on the one endpoint that is explicitly designed for
     * this job. The previous implementation round-robined ten streams over four
     * unrelated public mirrors. In production only the three Cloudflare legs
     * transferred data while OVH timed out, Tele2 returned 502 and Hetzner timed
     * out. The state machine then rejected the perfectly usable 3/10 result
     * because it required five streams across two CDN hosts.
     *
     * HTTP/1.1 plus maxRequestsPerHost=10 below still creates ten independent TCP
     * flows; using one anycast edge does not collapse them into one connection.
     */
    private val endpoints = listOf(
        Endpoint(
            name = "Cloudflare",
            url = "https://speed.cloudflare.com/__down?bytes=500000000"
        )
    )

    private const val STREAM_COUNT = 10
    private const val MIN_VALID_SAMPLES = 6
    private const val MIN_MEASURED_BYTES = 64L * 1024L
    private const val MIN_EXPECTED_BODY_BYTES = 32L * 1024L * 1024L

    private const val TOTAL_TIMEOUT_MS = 12_000L
    private const val STARTUP_TIMEOUT_MS = 5_000L
    private const val WARMUP_MS = 1_250L
    private const val TARGET_MEASUREMENT_MS = 8_000L
    // Call.cancel() is immediate, but the ten callback threads still need time
    // to unwind and join. A 250 ms reserve allowed successful measurements to be
    // reclassified as TIMEOUT on slower TV sticks during cleanup.
    private const val CLEANUP_RESERVE_MS = 1_000L
    private const val SAMPLE_INTERVAL_MS = 500L
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val STARTUP_POLL_MS = 50L
    private const val ACTIVE_STREAM_FRESHNESS_NS = 1_250_000_000L
    private const val BUFFER_SIZE = 128 * 1024

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val httpThreadIndex = AtomicInteger(0)
    private val measurementMutex = Mutex()

    /**
     * A fully isolated client: speed-test saturation cannot consume the app's
     * playback/API dispatcher slots, connection pool or retry interceptors.
     *
     * The isolated client deliberately uses HTTP/1.1 so ten concurrent requests
     * remain ten independent TCP flows. HTTP/2 would multiplex requests for the
     * same CDN onto one socket and can under-fill fast links on TV-class devices.
     */
    private val speedClient: OkHttpClient by lazy {
        val threadFactory = ThreadFactory { runnable ->
            Thread(
                runnable,
                "speed-test-http-${httpThreadIndex.incrementAndGet()}"
            ).apply { isDaemon = true }
        }
        val executor = ThreadPoolExecutor(
            STREAM_COUNT,
            STREAM_COUNT,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(),
            threadFactory
        ).apply {
            // Reclaim all ten thread stacks between infrequent tests. Threads are
            // recreated lazily when the next measurement is requested.
            allowCoreThreadTimeOut(true)
        }
        val dispatcher = Dispatcher(executor).apply {
            maxRequests = STREAM_COUNT
            maxRequestsPerHost = STREAM_COUNT
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(0, 1L, TimeUnit.SECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(3L, TimeUnit.SECONDS)
            .readTimeout(4L, TimeUnit.SECONDS)
            .callTimeout(TOTAL_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Runs one bounded test and reports structured diagnostics.
     *
     * [onProgress] is delivered on the caller's dispatcher through a conflated
     * channel. A slow UI therefore cannot back-pressure the network readers or
     * the sampling clock.
     */
    suspend fun measure(
        onProgress: (Progress) -> Unit = {}
    ): Result {
        if (!measurementMutex.tryLock()) {
            return Result.Failure(
                reason = FailureReason.BUSY,
                diagnostic = "speed_test_already_running",
                elapsedMs = 0L,
                validSampleCount = 0,
                activeStreamHighWaterMark = 0,
                endpointStats = emptyList()
            )
        }
        return try {
            measureInternal(onProgress)
        } finally {
            measurementMutex.unlock()
        }
    }

    private suspend fun measureInternal(
        onProgress: (Progress) -> Unit
    ): Result = coroutineScope {
        val callbackContext = currentCoroutineContext().minusKey(Job)
        val callbackScope = CoroutineScope(callbackContext + SupervisorJob())
        val progressChannel = Channel<Progress>(Channel.CONFLATED)
        callbackScope.launch {
            for (progress in progressChannel) {
                runCatching { onProgress(progress) }
            }
        }

        val startedAtNs = System.nanoTime()
        // Kotlin's radix conversion works on every supported Android API level;
        // java.lang.Long.toUnsignedString(long, radix) is API 26+ on Android.
        val sessionId = startedAtNs.toString(36)
        val streams = List(STREAM_COUNT) { index ->
            StreamState(
                id = index,
                endpoint = endpoints[index % endpoints.size]
            )
        }
        val runState = RunState(startedAtNs, streams)

        try {
            try {
                withTimeout(TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        runMeasurement(runState, sessionId) { progress ->
                            progressChannel.trySend(progress)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Distinguish our own deadline from cancellation of the caller.
                currentCoroutineContext().ensureActive()
                runState.failure(
                    reason = FailureReason.TIMEOUT,
                    validSampleCount = runState.validSampleCount,
                    diagnostic = "speed_test_deadline_exceeded"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runState.failure(
                    reason = FailureReason.INTERNAL_ERROR,
                    validSampleCount = runState.validSampleCount,
                    diagnostic = error.javaClass.simpleName.ifBlank { "internal_error" }
                )
            }
        } finally {
            // Progress is advisory; the final Result is authoritative. Cancelling
            // this independent scope is immediate and never extends the network
            // deadline while waiting for a UI callback to drain.
            progressChannel.cancel()
            callbackScope.cancel()
        }
    }

    /**
     * Compatibility helper for screens that only need the displayed number.
     * Failure is represented as -1.0; no portal/fallback URL is accepted.
     */
    suspend fun measureMbps(onProgress: (Double) -> Unit): Double {
        return when (
            val result = measure { progress ->
                if (progress.mbps > 0.0) onProgress(progress.mbps)
            }
        ) {
            is Result.Success -> result.mbps
            is Result.Failure -> -1.0
        }
    }

    private suspend fun runMeasurement(
        state: RunState,
        sessionId: String,
        emit: (Progress) -> Unit
    ): Result = coroutineScope {
        val readers = state.streams.map { stream ->
            launch {
                streamOnce(
                    state = stream,
                    totalBytes = state.totalBytes,
                    sessionId = sessionId
                )
            }
        }

        try {
            emit(state.progress(Phase.CONNECTING, mbps = 0.0, sampleCount = 0))
            val startupDeadlineNs =
                state.startedAtNs + TimeUnit.MILLISECONDS.toNanos(STARTUP_TIMEOUT_MS)
            var lastProgressAtNs = state.startedAtNs
            var usableSinceNs: Long? = null

            while (true) {
                coroutineContext.ensureActive()
                val nowNs = System.nanoTime()
                val activeStreams = state.updateHighWaterMark(nowNs)

                if (
                    nowNs - lastProgressAtNs >=
                    TimeUnit.MILLISECONDS.toNanos(PROGRESS_INTERVAL_MS)
                ) {
                    emit(state.progress(Phase.CONNECTING, mbps = 0.0, sampleCount = 0))
                    lastProgressAtNs = nowNs
                }

                usableSinceNs = when {
                    SpeedTestPolicy.hasMinimumActiveStreams(activeStreams) ->
                        usableSinceNs ?: nowNs
                    else -> null
                }
                val usableForMs = usableSinceNs?.let {
                    TimeUnit.NANOSECONDS.toMillis((nowNs - it).coerceAtLeast(0L))
                } ?: 0L
                when (
                    SpeedTestPolicy.startupDecision(
                        activeStreams = activeStreams,
                        usableForMs = usableForMs,
                        deadlineReached = nowNs >= startupDeadlineNs,
                        allReadersFinished = readers.all { it.isCompleted },
                    )
                ) {
                    SpeedTestPolicy.StartupDecision.START -> break
                    SpeedTestPolicy.StartupDecision.FAIL -> {
                        return@coroutineScope state.failure(
                            reason = FailureReason.INSUFFICIENT_ACTIVE_STREAMS,
                            validSampleCount = 0,
                            diagnostic =
                                "active=$activeStreams/" +
                                    "${SpeedTestPolicy.MIN_STARTUP_STREAMS}"
                        )
                    }
                    SpeedTestPolicy.StartupDecision.WAIT -> Unit
                }
                delay(STARTUP_POLL_MS)
            }

            val warmupStartedAtNs = System.nanoTime()
            val warmupDeadlineNs =
                warmupStartedAtNs + TimeUnit.MILLISECONDS.toNanos(WARMUP_MS)
            emit(state.progress(Phase.WARMING_UP, mbps = 0.0, sampleCount = 0))

            while (System.nanoTime() < warmupDeadlineNs) {
                coroutineContext.ensureActive()
                delay(PROGRESS_INTERVAL_MS)
                state.updateHighWaterMark(System.nanoTime())
                emit(state.progress(Phase.WARMING_UP, mbps = 0.0, sampleCount = 0))
            }

            val warmupCompletedAtNs = System.nanoTime()
            val warmupActiveStreams =
                state.updateHighWaterMark(warmupCompletedAtNs)
            if (!SpeedTestPolicy.hasMinimumActiveStreams(warmupActiveStreams)) {
                return@coroutineScope state.failure(
                    reason = FailureReason.INSUFFICIENT_ACTIVE_STREAMS,
                    validSampleCount = 0,
                    diagnostic =
                        "post_warmup_active=$warmupActiveStreams/" +
                            "${SpeedTestPolicy.MIN_STARTUP_STREAMS}"
                )
            }

            // This snapshot is the hard boundary: TLS/TCP ramp and all bytes read
            // during warm-up are excluded from every sample and the final result.
            val measurementStartedAtNs = System.nanoTime()
            val measurementStartedBytes = state.totalBytes.get()
            // Fast connections receive the full eight-second sample window. Slow
            // DNS/TLS startup receives the remaining budget rather than pushing
            // the complete operation beyond its twelve-second hard deadline.
            val measurementDeadlineNs = minOf(
                measurementStartedAtNs +
                    TimeUnit.MILLISECONDS.toNanos(TARGET_MEASUREMENT_MS),
                state.startedAtNs +
                    TimeUnit.MILLISECONDS.toNanos(
                        TOTAL_TIMEOUT_MS - CLEANUP_RESERVE_MS
                    )
            )
            var previousSampleAtNs = measurementStartedAtNs
            var previousSampleBytes = measurementStartedBytes
            val previousStreamBytes = LongArray(state.streams.size) { index ->
                state.streams[index].bytes.get()
            }
            val samples = mutableListOf<Double>()

            emit(state.progress(Phase.MEASURING, mbps = 0.0, sampleCount = 0))

            while (System.nanoTime() < measurementDeadlineNs) {
                coroutineContext.ensureActive()
                val remainingNs = measurementDeadlineNs - System.nanoTime()
                if (remainingNs <= 0L) break
                delay(
                    minOf(
                        SAMPLE_INTERVAL_MS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L)
                    )
                )

                val nowNs = System.nanoTime()
                val nowBytes = state.totalBytes.get()
                var activeStreams = 0
                val activeCdnHosts = mutableSetOf<String>()
                state.streams.forEachIndexed { index, stream ->
                    val streamBytes = stream.bytes.get()
                    if (streamBytes > previousStreamBytes[index]) {
                        activeStreams += 1
                        stream.endpoint.host.takeIf(String::isNotBlank)
                            ?.let(activeCdnHosts::add)
                    }
                    previousStreamBytes[index] = streamBytes
                }
                state.updateHighWaterMark(activeStreams)
                val activeCdns = activeCdnHosts.size
                val intervalNs = nowNs - previousSampleAtNs
                val intervalBytes =
                    (nowBytes - previousSampleBytes).coerceAtLeast(0L)

                if (
                    SpeedTestPolicy.isUsableSample(
                        activeStreams = activeStreams,
                        transferredBytes = intervalBytes,
                        intervalNs = intervalNs,
                    )
                ) {
                    val sample = SpeedTestMath.mbpsBetween(
                        startBytes = previousSampleBytes,
                        endBytes = nowBytes,
                        startNs = previousSampleAtNs,
                        endNs = nowNs
                    )
                    if (sample > 0.0 && sample.isFinite()) samples += sample
                }
                state.validSampleCount = samples.size

                previousSampleAtNs = nowNs
                previousSampleBytes = nowBytes
                emit(
                    state.progress(
                        phase = Phase.MEASURING,
                        mbps = SpeedTestMath.representativeMbps(samples),
                        sampleCount = samples.size,
                        activeStreamsOverride = activeStreams,
                        activeCdnCountOverride = activeCdns
                    )
                )

                if (readers.all { it.isCompleted }) break
            }

            val measurementEndedAtNs = System.nanoTime()
            val measuredBytes =
                (state.totalBytes.get() - measurementStartedBytes).coerceAtLeast(0L)
            val measurementDurationNs =
                (measurementEndedAtNs - measurementStartedAtNs).coerceAtLeast(0L)

            if (samples.size < MIN_VALID_SAMPLES) {
                return@coroutineScope state.failure(
                    reason = FailureReason.INSUFFICIENT_SAMPLES,
                    validSampleCount = samples.size,
                    diagnostic = "samples=${samples.size}/$MIN_VALID_SAMPLES"
                )
            }
            if (measuredBytes < MIN_MEASURED_BYTES) {
                return@coroutineScope state.failure(
                    reason = FailureReason.INSUFFICIENT_DATA,
                    validSampleCount = samples.size,
                    diagnostic = "bytes=$measuredBytes/$MIN_MEASURED_BYTES"
                )
            }

            Result.Success(
                mbps = SpeedTestMath.representativeMbps(samples),
                medianMbps = SpeedTestMath.median(samples),
                peakMbps = samples.maxOrNull() ?: 0.0,
                measuredBytes = measuredBytes,
                totalDurationMs = state.elapsedMs(),
                measurementDurationMs =
                    TimeUnit.NANOSECONDS.toMillis(measurementDurationNs),
                samplesMbps = samples.toList(),
                activeStreamHighWaterMark = state.activeStreamHighWaterMark,
                endpointStats = state.endpointStats()
            )
        } finally {
            readers.forEach { it.cancel() }
            readers.joinAll()
        }
    }

    /**
     * Uses OkHttp's asynchronous API so coroutine cancellation can cancel a call
     * immediately. Body reads still block, but only private dispatcher threads.
     */
    private suspend fun streamOnce(
        state: StreamState,
        totalBytes: AtomicLong,
        sessionId: String
    ) {
        val baseUrl = state.endpoint.url.toHttpUrlOrNull()
        if (baseUrl == null) {
            state.fail("invalid_url")
            return
        }
        val cacheBustedUrl = baseUrl.newBuilder()
            .addQueryParameter(
                "tvasset_speedtest",
                "$sessionId-${state.id}-${System.nanoTime()}"
            )
            .build()
        val request = Request.Builder()
            .url(cacheBustedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream,*/*;q=0.8")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .build()
        val call = speedClient.newCall(request)

        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation {
                state.cancel()
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) {
                            state.fail(
                                error.javaClass.simpleName.ifBlank { "network_failure" }
                            )
                            continuation.resume(Unit)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { safeResponse ->
                                if (!safeResponse.isSuccessful) {
                                    state.fail("http_${safeResponse.code}")
                                    return@use
                                }
                                val body = safeResponse.body
                                if (body == null) {
                                    state.fail("missing_body")
                                    return@use
                                }
                                val contentLength = body.contentLength()
                                if (
                                    contentLength in 0L until MIN_EXPECTED_BODY_BYTES
                                ) {
                                    state.fail("body_too_small")
                                    return@use
                                }

                                state.protocol.set(safeResponse.protocol.toString())
                                val buffer = ByteArray(BUFFER_SIZE)
                                body.byteStream().use { input ->
                                    while (continuation.isActive) {
                                        val read = input.read(buffer)
                                        if (read < 0) {
                                            state.complete()
                                            break
                                        }
                                        if (read > 0) {
                                            val nowNs = System.nanoTime()
                                            state.recordBytes(read, nowNs)
                                            totalBytes.addAndGet(read.toLong())
                                        }
                                    }
                                }
                            }
                        } catch (error: Throwable) {
                            if (continuation.isActive) {
                                state.fail(
                                    error.javaClass.simpleName.ifBlank {
                                        "body_read_failure"
                                    }
                                )
                            }
                        } finally {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
            )
        }
    }

    private fun RunState.progress(
        phase: Phase,
        mbps: Double,
        sampleCount: Int,
        activeStreamsOverride: Int? = null,
        activeCdnCountOverride: Int? = null
    ): Progress {
        val nowNs = System.nanoTime()
        return Progress(
            phase = phase,
            elapsedMs = elapsedMs(),
            mbps = mbps,
            activeStreams = activeStreamsOverride ?: activeStreamCount(nowNs),
            startedStreams = streams.count(StreamState::hasStarted),
            cancelledStreams =
                streams.count { it.status.get() == StreamStatus.CANCELLED },
            completedStreams =
                streams.count { it.status.get() == StreamStatus.COMPLETED },
            failedStreams = streams.count { it.status.get() == StreamStatus.FAILED },
            attemptedStreams = streams.size,
            activeCdnCount = activeCdnCountOverride ?: activeCdnCount(nowNs),
            sampleCount = sampleCount
        )
    }

    private fun RunState.failure(
        reason: FailureReason,
        validSampleCount: Int,
        diagnostic: String
    ): Result.Failure {
        return Result.Failure(
            reason = reason,
            diagnostic = diagnostic,
            elapsedMs = elapsedMs(),
            validSampleCount = validSampleCount,
            activeStreamHighWaterMark = activeStreamHighWaterMark,
            endpointStats = endpointStats()
        )
    }

    private fun RunState.elapsedMs(): Long {
        return TimeUnit.NANOSECONDS.toMillis(
            (System.nanoTime() - startedAtNs).coerceAtLeast(0L)
        )
    }

    private fun RunState.activeCdnCount(nowNs: Long): Int {
        return streams.asSequence()
            .filter { it.isTransferring(nowNs) }
            .map { it.endpoint.host }
            .filter(String::isNotBlank)
            .distinct()
            .count()
    }

    private fun RunState.endpointStats(): List<EndpointStats> {
        val nowNs = System.nanoTime()
        return streams.groupBy(StreamState::endpoint).map { (endpoint, group) ->
            val activeStreams = group.count { it.isTransferring(nowNs) }
            val stalledStreams = group.count {
                it.status.get() == StreamStatus.ACTIVE &&
                    !it.isTransferring(nowNs)
            }
            EndpointStats(
                cdn = endpoint.name,
                host = endpoint.host,
                attemptedStreams = group.size,
                connectingStreams =
                    group.count { it.status.get() == StreamStatus.CONNECTING },
                activeStreams = activeStreams,
                stalledStreams = stalledStreams,
                startedStreams = group.count(StreamState::hasStarted),
                cancelledStreams =
                    group.count { it.status.get() == StreamStatus.CANCELLED },
                completedStreams =
                    group.count { it.status.get() == StreamStatus.COMPLETED },
                failedStreams =
                    group.count { it.status.get() == StreamStatus.FAILED },
                transferredBytes = group.sumOf { it.bytes.get() },
                protocols = group.mapNotNull { it.protocol.get() }.distinct().sorted()
            )
        }
    }
}

/**
 * Pure speed-test state policy. Keeping the thresholds out of the network code
 * makes the Android TV startup edge cases deterministic in local JVM tests.
 */
internal object SpeedTestPolicy {

    const val MIN_STARTUP_STREAMS = 2
    private const val PREFERRED_STARTUP_STREAMS = 6
    private const val STARTUP_SETTLE_MS = 750L
    private const val MIN_SAMPLE_ACTIVE_STREAMS = 1
    private const val MIN_SAMPLE_INTERVAL_NS = 300_000_000L

    enum class StartupDecision {
        WAIT,
        START,
        FAIL
    }

    /**
     * Start immediately when most connections are ready. If only a smaller but
     * usable set is available, give late TLS handshakes a short grace period and
     * then continue instead of failing at 3/10. The hard startup deadline remains
     * authoritative when too few streams ever transfer bytes.
     */
    fun startupDecision(
        activeStreams: Int,
        usableForMs: Long,
        deadlineReached: Boolean,
        allReadersFinished: Boolean
    ): StartupDecision {
        val usable = hasMinimumActiveStreams(activeStreams)
        if (
            usable &&
            (
                activeStreams >= PREFERRED_STARTUP_STREAMS ||
                    usableForMs >= STARTUP_SETTLE_MS ||
                    deadlineReached ||
                    allReadersFinished
                )
        ) {
            return StartupDecision.START
        }
        return if (deadlineReached || allReadersFinished) {
            StartupDecision.FAIL
        } else {
            StartupDecision.WAIT
        }
    }

    fun hasMinimumActiveStreams(activeStreams: Int): Boolean {
        return activeStreams >= MIN_STARTUP_STREAMS
    }

    /**
     * Once warm-up has proved parallel capacity, retain any positive interval
     * with at least one transferring stream. Requiring five simultaneous streams
     * in every 500 ms bucket discarded real bytes during normal scheduler jitter
     * and could leave a completed test with too few valid samples.
     */
    fun isUsableSample(
        activeStreams: Int,
        transferredBytes: Long,
        intervalNs: Long
    ): Boolean {
        return activeStreams >= MIN_SAMPLE_ACTIVE_STREAMS &&
            transferredBytes > 0L &&
            intervalNs >= MIN_SAMPLE_INTERVAL_NS
    }
}

/**
 * Pure arithmetic kept separate from Android/network code so boundary cases can
 * be verified by local JVM tests.
 */
internal object SpeedTestMath {

    /**
     * Decimal megabits per second. 31,250,000 bytes in one second is 250 Mbps.
     */
    fun mbpsBetween(
        startBytes: Long,
        endBytes: Long,
        startNs: Long,
        endNs: Long
    ): Double {
        val byteDelta = endBytes - startBytes
        val timeDeltaNs = endNs - startNs
        if (byteDelta <= 0L || timeDeltaNs <= 0L) return 0.0
        return byteDelta.toDouble() * 8_000.0 / timeDeltaNs.toDouble()
    }

    fun p90(samples: List<Double>): Double = percentile(samples, 0.90)

    /**
     * A completed test normally has 12-16 samples and uses p90 so short dips do
     * not under-report the line. A late-starting connection can legitimately
     * leave only six samples; p90 would then select the single highest value.
     * Use p80 for those shorter windows so one scheduling spike is still rejected.
     */
    fun representativeMbps(samples: List<Double>): Double {
        val validCount = validSorted(samples).size
        return percentile(samples, if (validCount >= 10) 0.90 else 0.80)
    }

    fun median(samples: List<Double>): Double {
        val sorted = validSorted(samples)
        if (sorted.isEmpty()) return 0.0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    /**
     * Nearest-rank percentile. With ten samples p90 selects the ninth value, so
     * one isolated high spike cannot inflate the reported result.
     */
    fun percentile(samples: List<Double>, percentile: Double): Double {
        val sorted = validSorted(samples)
        if (sorted.isEmpty()) return 0.0

        val boundedPercentile = percentile.coerceIn(0.0, 1.0)
        val rank = ceil(boundedPercentile * sorted.size)
            .toInt()
            .coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun validSorted(samples: List<Double>): List<Double> {
        return samples.asSequence()
            .filter { it.isFinite() && it >= 0.0 }
            .sorted()
            .toList()
    }
}
