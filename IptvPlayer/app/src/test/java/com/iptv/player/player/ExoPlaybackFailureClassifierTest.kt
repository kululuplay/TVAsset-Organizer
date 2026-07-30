package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(markerClass = [UnstableApi::class])
class ExoPlaybackFailureClassifierTest {

    @Test
    fun `audio renderer decoder failures use audio compatibility fallback`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        ).forEach { errorCode ->
            assertEquals(
                ExoPlaybackFailureClassifier.Failure.AUDIO,
                ExoPlaybackFailureClassifier.classifyError(
                    errorCode = errorCode,
                    rendererType = C.TRACK_TYPE_AUDIO,
                ),
            )
        }
    }

    @Test
    fun `video and unidentified decoder failures use decode fallback`() {
        listOf(C.TRACK_TYPE_VIDEO, null).forEach { rendererType ->
            assertEquals(
                ExoPlaybackFailureClassifier.Failure.DECODE,
                ExoPlaybackFailureClassifier.classifyError(
                    errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                    rendererType = rendererType,
                ),
            )
        }
    }

    @Test
    fun `AudioTrack output failures are always classified as audio`() {
        listOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        ).forEach { errorCode ->
            assertEquals(
                ExoPlaybackFailureClassifier.Failure.AUDIO,
                ExoPlaybackFailureClassifier.classifyError(
                    errorCode = errorCode,
                    rendererType = null,
                ),
            )
        }
    }

    @Test
    fun `video processing failures use decode fallback`() {
        listOf(
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        ).forEach { errorCode ->
            assertEquals(
                ExoPlaybackFailureClassifier.Failure.DECODE,
                ExoPlaybackFailureClassifier.classifyError(
                    errorCode = errorCode,
                    rendererType = C.TRACK_TYPE_VIDEO,
                ),
            )
        }
    }

    @Test
    fun `network source and parsing failures remain ordinary errors`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        ).forEach { errorCode ->
            assertEquals(
                ExoPlaybackFailureClassifier.Failure.ERROR,
                ExoPlaybackFailureClassifier.classifyError(
                    errorCode = errorCode,
                    rendererType = null,
                ),
            )
        }
    }

    @Test
    fun `audio first TV snapshot remains unresolved until video group arrives`() {
        assertNull(
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = listOf(supportedAudio()),
                expectsVideo = true,
            ),
        )
    }

    @Test
    fun `unselected or unsupported TV video is a decode failure`() {
        listOf(
            track(C.TRACK_TYPE_VIDEO, selected = false, supported = true),
            track(C.TRACK_TYPE_VIDEO, selected = true, supported = false),
        ).forEach { video ->
            assertEquals(
                ExoPlaybackFailureClassifier.Failure.DECODE,
                ExoPlaybackFailureClassifier.classifyTracks(
                    groups = listOf(video, supportedAudio()),
                    expectsVideo = true,
                ),
            )
        }
    }

    @Test
    fun `selected but unsupported audio is an audio failure`() {
        assertEquals(
            ExoPlaybackFailureClassifier.Failure.AUDIO,
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = listOf(
                    supportedVideo(),
                    track(C.TRACK_TYPE_AUDIO, selected = true, supported = false),
                ),
                expectsVideo = true,
            ),
        )
    }

    @Test
    fun `supported but temporarily unselected audio stays pending`() {
        val groups = listOf(
            supportedVideo(),
            track(C.TRACK_TYPE_AUDIO, selected = false, supported = true),
        )

        assertNull(
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = groups,
                expectsVideo = true,
                selectionSettled = false,
            ),
        )
        assertEquals(
            true,
            ExoPlaybackFailureClassifier.hasPendingSupportedSelection(
                groups = groups,
                expectsVideo = true,
            ),
        )
        assertEquals(
            ExoPlaybackFailureClassifier.Failure.AUDIO,
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = groups,
                expectsVideo = true,
                selectionSettled = true,
            ),
        )
    }

    @Test
    fun `unsupported selected audio fails without waiting for settle timeout`() {
        val groups = listOf(
            supportedVideo(),
            track(C.TRACK_TYPE_AUDIO, selected = true, supported = false),
        )

        assertEquals(
            ExoPlaybackFailureClassifier.Failure.AUDIO,
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = groups,
                expectsVideo = true,
                selectionSettled = false,
            ),
        )
    }

    @Test
    fun `selected supported audio and video are healthy`() {
        assertNull(
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = listOf(supportedVideo(), supportedAudio()),
                expectsVideo = true,
            ),
        )
    }

    @Test
    fun `radio can be healthy without a video track`() {
        assertNull(
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = listOf(supportedAudio()),
                expectsVideo = false,
            ),
        )
    }

    @Test
    fun `empty snapshot remains unresolved`() {
        assertNull(
            ExoPlaybackFailureClassifier.classifyTracks(
                groups = emptyList(),
                expectsVideo = true,
            ),
        )
    }

    private fun supportedVideo() =
        track(C.TRACK_TYPE_VIDEO, selected = true, supported = true)

    private fun supportedAudio() =
        track(C.TRACK_TYPE_AUDIO, selected = true, supported = true)

    private fun track(
        type: Int,
        selected: Boolean,
        supported: Boolean,
    ) = ExoPlaybackFailureClassifier.TrackGroupState(
        type = type,
        selected = listOf(selected),
        supported = listOf(supported),
    )
}
