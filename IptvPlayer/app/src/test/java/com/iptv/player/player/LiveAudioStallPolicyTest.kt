package com.iptv.player.player

import com.iptv.player.playback.core.AudioFailureEvidence
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAudioStallPolicyTest {

    @Test
    fun `repeated AC3 underruns with advancing video use VLC PCM fallback`() {
        assertEquals(
            LiveAudioStallPolicy.Decision.FALLBACK_TO_VLC_PCM,
            LiveAudioStallPolicy.decide(
                healthyBase().copy(
                    audioClockStarted = true,
                    recentVideoProgress = true,
                    underrunsInWindow = 2,
                    sinkEvent = AudioFailureEvidence.SinkEvent.UNDERRUN,
                ),
            ),
        )
    }

    @Test
    fun `network-wide stall is not misclassified as an audio stall`() {
        assertEquals(
            LiveAudioStallPolicy.Decision.WAIT,
            LiveAudioStallPolicy.decide(
                healthyBase().copy(
                    audioClockStarted = true,
                    recentVideoProgress = false,
                    underrunsInWindow = 3,
                    sinkEvent = AudioFailureEvidence.SinkEvent.UNDERRUN,
                ),
            ),
        )
    }

    @Test
    fun `AC3 decoder with no audio clock after video progress falls back`() {
        assertEquals(
            LiveAudioStallPolicy.Decision.FALLBACK_TO_VLC_PCM,
            LiveAudioStallPolicy.decide(
                healthyBase().copy(
                    audioClockStarted = false,
                    recentVideoProgress = true,
                    bufferingDurationMs = LiveAudioStallPolicy.AUDIO_CLOCK_START_TIMEOUT_MS,
                    sinkEvent = AudioFailureEvidence.SinkEvent.CLOCK_STALL,
                ),
            ),
        )
    }

    @Test
    fun `AC3 clock wait without fresh video progress stays on current route`() {
        assertEquals(
            LiveAudioStallPolicy.Decision.WAIT,
            LiveAudioStallPolicy.decide(
                healthyBase().copy(
                    audioClockStarted = false,
                    recentVideoProgress = false,
                    bufferingDurationMs = LiveAudioStallPolicy.AUDIO_CLOCK_START_TIMEOUT_MS,
                    sinkEvent = AudioFailureEvidence.SinkEvent.CLOCK_STALL,
                ),
            ),
        )
    }

    @Test
    fun `AC3 clock stall with source buffer progress uses PCM rescue`() {
        assertEquals(
            LiveAudioStallPolicy.Decision.FALLBACK_TO_VLC_PCM,
            LiveAudioStallPolicy.decide(
                healthyBase().copy(
                    audioClockStarted = false,
                    recentVideoProgress = false,
                    sourceProgressAfterIssue = true,
                    bufferingDurationMs = LiveAudioStallPolicy.AUDIO_CLOCK_START_TIMEOUT_MS,
                    sinkEvent = AudioFailureEvidence.SinkEvent.CLOCK_STALL,
                ),
            ),
        )
    }

    @Test
    fun `EAC3 remains on its working route`() {
        assertEquals(
            LiveAudioStallPolicy.Decision.WAIT,
            LiveAudioStallPolicy.decide(
                healthyBase().copy(
                    codec = AudioFailureEvidence.Codec.E_AC3,
                    audioClockStarted = false,
                    bufferingDurationMs = 30_000L,
                    sinkEvent = AudioFailureEvidence.SinkEvent.SINK_ERROR,
                ),
            ),
        )
    }

    @Test
    fun `explicit passthrough is never overridden`() {
        assertEquals(
            LiveAudioStallPolicy.Decision.WAIT,
            LiveAudioStallPolicy.decide(
                healthyBase().copy(
                    outputMode = AudioFailureEvidence.OutputMode.PASSTHROUGH,
                    sinkEvent = AudioFailureEvidence.SinkEvent.SINK_ERROR,
                ),
            ),
        )
    }

    @Test
    fun `PCM rescue targets VLC hardware once and never loops`() {
        val first = LiveAudioStallPolicy.fallbackStage(
            current = PlaybackRoutingPolicy.Stage.EXO,
            outputMode = AudioFailureEvidence.OutputMode.PCM,
            alreadyUsed = false,
            triedStages = setOf(PlaybackRoutingPolicy.Stage.EXO),
            bypassVlcHardware = false,
        )
        assertEquals(PlaybackRoutingPolicy.Stage.VLC_HW, first)
        assertEquals(
            null,
            LiveAudioStallPolicy.fallbackStage(
                current = PlaybackRoutingPolicy.Stage.EXO,
                outputMode = AudioFailureEvidence.OutputMode.PCM,
                alreadyUsed = true,
                triedStages = setOf(PlaybackRoutingPolicy.Stage.EXO, first!!),
                bypassVlcHardware = false,
            ),
        )
    }

    @Test
    fun `unsafe VLC hardware devices use bounded VLC software PCM rescue`() {
        assertEquals(
            PlaybackRoutingPolicy.Stage.VLC_SW,
            LiveAudioStallPolicy.fallbackStage(
                current = PlaybackRoutingPolicy.Stage.EXO,
                outputMode = AudioFailureEvidence.OutputMode.PCM,
                alreadyUsed = false,
                triedStages = setOf(PlaybackRoutingPolicy.Stage.EXO),
                bypassVlcHardware = true,
            ),
        )
    }

    @Test
    fun `sink failure before any media progress does not trigger heuristic fallback`() {
        assertEquals(
            LiveAudioStallPolicy.Decision.WAIT,
            LiveAudioStallPolicy.decide(
                healthyBase().copy(
                    mediaProgressObserved = false,
                    sinkEvent = AudioFailureEvidence.SinkEvent.SINK_ERROR,
                ),
            ),
        )
    }

    @Test
    fun `mime and decoder evidence is reduced to closed safe enums`() {
        assertEquals(
            AudioFailureEvidence.Codec.AC3,
            LiveAudioStallPolicy.codecForMime("audio/ac3"),
        )
        assertEquals(
            AudioFailureEvidence.Codec.E_AC3,
            LiveAudioStallPolicy.codecForMime("audio/eac3-joc"),
        )
        assertEquals(
            AudioFailureEvidence.Decoder.SOFTWARE,
            LiveAudioStallPolicy.decoderForName("c2.android.ac3.decoder"),
        )
        assertEquals(
            AudioFailureEvidence.Decoder.HARDWARE,
            LiveAudioStallPolicy.decoderForName("OMX.vendor.audio.decoder.ac3"),
        )
    }

    private fun healthyBase() = LiveAudioStallPolicy.Evidence(
        codec = AudioFailureEvidence.Codec.AC3,
        decoder = AudioFailureEvidence.Decoder.HARDWARE,
        outputMode = AudioFailureEvidence.OutputMode.PCM,
        mediaProgressObserved = true,
        recentVideoProgress = false,
        decoderInitialized = true,
        audioClockStarted = false,
    )
}
