package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestMathTest {

    @Test
    fun `31 point 25 megabytes per second is 250 megabits per second`() {
        val result = SpeedTestMath.mbpsBetween(
            startBytes = 0L,
            endBytes = 31_250_000L,
            startNs = 0L,
            endNs = 1_000_000_000L
        )

        assertEquals(250.0, result, 0.000_001)
    }

    @Test
    fun `250 megabits per second remains correct over a longer window`() {
        val result = SpeedTestMath.mbpsBetween(
            startBytes = 90_000_000L,
            endBytes = 402_500_000L,
            startNs = 3_000_000_000L,
            endNs = 13_000_000_000L
        )

        assertEquals(250.0, result, 0.000_001)
    }

    @Test
    fun `bytes before warmup snapshot are excluded`() {
        val result = SpeedTestMath.mbpsBetween(
            startBytes = 1_000_000_000L,
            endBytes = 1_031_250_000L,
            startNs = 8_000_000_000L,
            endNs = 9_000_000_000L
        )

        assertEquals(250.0, result, 0.000_001)
    }

    @Test
    fun `invalid or empty intervals return zero`() {
        assertEquals(
            0.0,
            SpeedTestMath.mbpsBetween(100L, 100L, 0L, 1_000_000_000L),
            0.0
        )
        assertEquals(
            0.0,
            SpeedTestMath.mbpsBetween(0L, 100L, 1_000L, 1_000L),
            0.0
        )
    }

    @Test
    fun `p90 rejects one isolated high spike`() {
        val samples = List(9) { 250.0 } + 1_500.0

        assertEquals(250.0, SpeedTestMath.p90(samples), 0.0)
    }

    @Test
    fun `p90 rejects the top two spikes in a twenty sample window`() {
        val samples = List(18) { 250.0 } + listOf(1_000.0, 1_500.0)

        assertEquals(250.0, SpeedTestMath.p90(samples), 0.0)
    }

    @Test
    fun `p90 ignores non finite samples`() {
        val samples = listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            100.0,
            150.0,
            200.0
        )

        assertEquals(200.0, SpeedTestMath.p90(samples), 0.0)
    }

    @Test
    fun `short measurement window rejects one isolated high spike`() {
        val samples = List(5) { 250.0 } + 1_500.0

        assertEquals(250.0, SpeedTestMath.representativeMbps(samples), 0.0)
    }

    @Test
    fun `full measurement window keeps p90 steady rate`() {
        val samples = List(9) { 250.0 } + 1_500.0

        assertEquals(250.0, SpeedTestMath.representativeMbps(samples), 0.0)
    }

    @Test
    fun `median averages the two middle samples`() {
        val samples = listOf(400.0, 100.0, 300.0, 200.0)

        assertEquals(250.0, SpeedTestMath.median(samples), 0.0)
    }

    @Test
    fun `three active streams start after connection settle window`() {
        assertEquals(
            SpeedTestPolicy.StartupDecision.WAIT,
            SpeedTestPolicy.startupDecision(
                activeStreams = 3,
                usableForMs = 749L,
                deadlineReached = false,
                allReadersFinished = false,
            ),
        )
        assertEquals(
            SpeedTestPolicy.StartupDecision.START,
            SpeedTestPolicy.startupDecision(
                activeStreams = 3,
                usableForMs = 750L,
                deadlineReached = false,
                allReadersFinished = false,
            ),
        )
    }

    @Test
    fun `preferred parallelism starts without waiting`() {
        assertEquals(
            SpeedTestPolicy.StartupDecision.START,
            SpeedTestPolicy.startupDecision(
                activeStreams = 6,
                usableForMs = 0L,
                deadlineReached = false,
                allReadersFinished = false,
            ),
        )
    }

    @Test
    fun `startup deadline accepts usable pair and rejects a single stream`() {
        assertEquals(
            SpeedTestPolicy.StartupDecision.START,
            SpeedTestPolicy.startupDecision(
                activeStreams = 2,
                usableForMs = 0L,
                deadlineReached = true,
                allReadersFinished = false,
            ),
        )
        assertEquals(
            SpeedTestPolicy.StartupDecision.FAIL,
            SpeedTestPolicy.startupDecision(
                activeStreams = 1,
                usableForMs = 5_000L,
                deadlineReached = true,
                allReadersFinished = false,
            ),
        )
    }

    @Test
    fun `sample policy keeps positive scheduler jitter intervals`() {
        assertEquals(
            true,
            SpeedTestPolicy.isUsableSample(
                activeStreams = 1,
                transferredBytes = 1_000_000L,
                intervalNs = 500_000_000L,
            ),
        )
        assertEquals(
            false,
            SpeedTestPolicy.isUsableSample(
                activeStreams = 1,
                transferredBytes = 0L,
                intervalNs = 500_000_000L,
            ),
        )
        assertEquals(
            false,
            SpeedTestPolicy.isUsableSample(
                activeStreams = 1,
                transferredBytes = 1_000_000L,
                intervalNs = 299_999_999L,
            ),
        )
    }
}
