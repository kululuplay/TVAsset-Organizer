package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcPlaybackHealthTest {

    @Test
    fun `first displayed picture confirms real video output once`() {
        val health = VlcPlaybackHealth()

        assertFalse(health.evaluate(sample(displayed = 0), nowMs = 0).firstDisplayedFrame)
        assertTrue(health.evaluate(sample(displayed = 1), nowMs = 1_500).firstDisplayedFrame)
        assertFalse(health.evaluate(sample(displayed = 40), nowMs = 3_000).firstDisplayedFrame)
    }

    @Test
    fun `advancing audio and input with frozen displayed picture is detected`() {
        val health = VlcPlaybackHealth()
        health.evaluate(sample(bytes = 10_000, decoded = 10, displayed = 10, time = 1_000), 0)
        health.evaluate(sample(bytes = 120_000, decoded = 20, displayed = 10, time = 3_000), 3_000)
        val decision = health.evaluate(
            sample(bytes = 260_000, decoded = 35, displayed = 10, time = 9_000),
            7_500,
        )

        assertTrue(decision.videoFrozen)
    }

    @Test
    fun `normal displayed picture progress does not freeze`() {
        val health = VlcPlaybackHealth()
        health.evaluate(sample(bytes = 10_000, decoded = 10, displayed = 10, time = 1_000), 0)
        health.evaluate(sample(bytes = 100_000, decoded = 80, displayed = 75, time = 3_000), 4_000)
        val decision = health.evaluate(
            sample(bytes = 220_000, decoded = 150, displayed = 140, time = 8_000),
            8_000,
        )

        assertFalse(decision.videoFrozen)
    }

    @Test
    fun `fully stopped pipeline is left to the source reconnect watchdog`() {
        val health = VlcPlaybackHealth()
        health.evaluate(
            sample(bytes = 10_000, decoded = 10, displayed = 10, time = 1_000),
            0,
        )
        // Video output is frozen while decode/input still advances.
        health.evaluate(
            sample(bytes = 160_000, decoded = 40, displayed = 10, time = 4_000),
            4_000,
        )
        // By the deadline the whole pipeline has stopped. This no longer proves
        // a decoder failure; routing must remain unchanged while the controller
        // reconnects the source.
        val decision = health.evaluate(
            sample(bytes = 160_000, decoded = 40, displayed = 10, time = 4_000),
            8_000,
        )

        assertFalse(decision.videoFrozen)
    }

    @Test
    fun `lost picture ratio never overrides advancing displayed output`() {
        val health = VlcPlaybackHealth()
        health.evaluate(sample(decoded = 10, displayed = 10, lost = 0), 0)
        health.evaluate(sample(decoded = 110, displayed = 65, lost = 45), 2_000)
        health.evaluate(sample(decoded = 210, displayed = 120, lost = 90), 4_000)
        val decision = health.evaluate(
            sample(bytes = 400_000, decoded = 310, displayed = 175, lost = 135, time = 8_000),
            8_000,
        )

        assertFalse(decision.videoFrozen)
    }

    @Test
    fun `network buffering pauses freeze detection`() {
        val health = VlcPlaybackHealth()
        health.evaluate(sample(bytes = 10_000, decoded = 10, displayed = 10), 0)
        health.evaluate(
            sample(bytes = 120_000, decoded = 70, displayed = 40, lost = 30, time = 3_000),
            3_000,
        )
        val buffering = health.evaluate(
            sample(bytes = 240_000, decoded = 130, displayed = 40, lost = 90, time = 9_000),
            8_000,
            videoActivelyPlaying = false,
        )

        assertFalse(buffering.videoFrozen)

        val resumed = health.evaluate(
            sample(bytes = 360_000, decoded = 190, displayed = 70, lost = 120, time = 11_000),
            10_000,
        )
        assertFalse(resumed.videoFrozen)
    }

    private fun sample(
        bytes: Long = 0,
        decoded: Long = 0,
        displayed: Long = 0,
        lost: Long = 0,
        time: Long = 0,
    ) = VlcPlaybackHealth.Sample(
        readBytes = bytes,
        decodedVideo = decoded,
        displayedPictures = displayed,
        lostPictures = lost,
        playbackTimeMs = time,
    )
}
