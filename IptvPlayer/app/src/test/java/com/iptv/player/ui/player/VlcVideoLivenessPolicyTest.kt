package com.iptv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VlcVideoLivenessPolicyTest {

    @Test
    fun `decoded pictures continuing while display counter freezes is output stall`() {
        var state = observe(StateInput(now = 0, position = 0, decoded = 10, displayed = 10)).state
        state = observe(
            StateInput(now = 1_000, position = 1_000, decoded = 35, displayed = 35),
            state,
        ).state

        val result = observe(
            StateInput(
                now = 13_000,
                position = 13_000,
                decoded = 335,
                displayed = 35,
                audio = 500,
                readBytes = 500_000,
            ),
            state,
        )

        assertEquals(VlcVideoLivenessPolicy.Decision.VIDEO_STALL, result.decision)
    }

    @Test
    fun `video decoder freeze with healthy input and audio is bounded stall`() {
        var state = observe(StateInput(now = 0, position = 0, decoded = 10, displayed = 10)).state
        state = observe(
            StateInput(now = 1_000, position = 1_000, decoded = 35, displayed = 35),
            state,
        ).state

        val result = observe(
            StateInput(
                now = 13_000,
                position = 13_000,
                decoded = 35,
                displayed = 35,
                audio = 500,
                readBytes = 500_000,
                fps = 25f,
            ),
            state,
        )

        assertEquals(VlcVideoLivenessPolicy.Decision.VIDEO_STALL, result.decision)
    }

    @Test
    fun `single still image never establishes cadence and cannot false positive`() {
        var state = observe(StateInput(now = 0, position = 0, decoded = 1, displayed = 1)).state
        val result = observe(
            StateInput(
                now = 30_000,
                position = 30_000,
                decoded = 1,
                displayed = 1,
                audio = 500,
                readBytes = 500_000,
                fps = 0.2f,
            ),
            state,
        )
        state = result.state

        assertEquals(VlcVideoLivenessPolicy.Decision.WAIT, result.decision)
        assertEquals(false, state.cadenceEstablished)
    }

    @Test
    fun `buffering resets proof window instead of selecting decoder recovery`() {
        var state = observe(StateInput(now = 0, position = 0, decoded = 10, displayed = 10)).state
        state = observe(
            StateInput(now = 1_000, position = 1_000, decoded = 35, displayed = 35),
            state,
        ).state
        val result = observe(
            StateInput(
                now = 30_000,
                position = 1_000,
                decoded = 35,
                displayed = 35,
                buffering = true,
            ),
            state,
        )

        assertEquals(VlcVideoLivenessPolicy.Decision.WAIT, result.decision)
        assertEquals(false, result.state.cadenceEstablished)
    }

    @Test
    fun `display counter advance remains healthy`() {
        var state = observe(StateInput(now = 0, position = 0, decoded = 10, displayed = 10)).state
        state = observe(
            StateInput(now = 1_000, position = 1_000, decoded = 35, displayed = 35),
            state,
        ).state
        val result = observe(
            StateInput(now = 20_000, position = 20_000, decoded = 500, displayed = 500),
            state,
        )

        assertEquals(VlcVideoLivenessPolicy.Decision.WAIT, result.decision)
    }

    private data class StateInput(
        val now: Long,
        val position: Long,
        val decoded: Int,
        val displayed: Int,
        val audio: Int = decoded,
        val readBytes: Int = decoded * 1_000,
        val buffering: Boolean = false,
        val fps: Float = 25f,
    )

    private fun observe(
        input: StateInput,
        state: VlcVideoLivenessPolicy.State = VlcVideoLivenessPolicy.State(),
    ): VlcVideoLivenessPolicy.Result = VlcVideoLivenessPolicy.reduce(
        previous = state,
        sample = VlcVideoLivenessPolicy.Sample(
            nowMs = input.now,
            playbackActive = true,
            inputBuffering = input.buffering,
            verifiedVideo = true,
            positionMs = input.position,
            decodedVideo = input.decoded,
            displayedPictures = input.displayed,
            playedAudioBuffers = input.audio,
            readBytes = input.readBytes,
            expectedVideoFps = input.fps,
        ),
        frameTimeoutMs = 12_000L,
        minimumClockAdvanceMs = 4_000L,
    )
}
