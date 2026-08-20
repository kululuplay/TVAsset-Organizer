package com.iptv.player.player

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.player.VodPlaybackRoutingPolicy.Failure
import com.iptv.player.player.VodPlaybackRoutingPolicy.Route
import com.iptv.player.playback.core.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlaybackRoutingPolicyTest {

    @Test
    fun `auto uses Media3 for adaptive and standard mp4 formats`() {
        assertEquals(
            Route.EXO,
            VodPlaybackRoutingPolicy.initialRoute(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                "https://example.test/movie.mp4?quality=hd",
            ),
        )
        assertTrue(VodPlaybackRoutingPolicy.isMedia3Preferred("https://x/live.m3u8#fragment"))
        assertTrue(VodPlaybackRoutingPolicy.isMedia3Preferred("https://x/manifest.mpd"))
    }

    @Test
    fun `auto keeps opaque and legacy containers on VLC`() {
        assertEquals(
            Route.VLC_HARDWARE,
            VodPlaybackRoutingPolicy.initialRoute(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                "https://example.test/movie.mkv",
            ),
        )
        assertFalse(VodPlaybackRoutingPolicy.isMedia3Preferred("https://x/movie.avi"))
    }

    @Test
    fun `explicit software always begins on VLC software`() {
        assertEquals(
            Route.VLC_SOFTWARE,
            VodPlaybackRoutingPolicy.initialRoute(
                PlayerMode.AUTO,
                DecoderMode.SOFTWARE,
                "https://example.test/movie.mp4",
            ),
        )
    }

    @Test
    fun `source failure compatibility route switches engine not decoder`() {
        assertEquals(
            Route.VLC_HARDWARE,
            VodPlaybackRoutingPolicy.nextRoute(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Route.EXO,
                Failure.SOURCE,
                setOf(Route.EXO),
            ),
        )
        assertEquals(
            Route.EXO,
            VodPlaybackRoutingPolicy.nextRoute(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Route.VLC_SOFTWARE,
                Failure.SOURCE,
                setOf(Route.VLC_SOFTWARE),
            ),
        )
    }

    @Test
    fun `confirmed decoder failure follows a bounded untried route`() {
        assertEquals(
            Route.VLC_HARDWARE,
            VodPlaybackRoutingPolicy.nextRoute(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Route.EXO,
                Failure.DECODER,
                setOf(Route.EXO),
            ),
        )
        assertEquals(
            Route.VLC_SOFTWARE,
            VodPlaybackRoutingPolicy.nextRoute(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Route.VLC_HARDWARE,
                Failure.DECODER,
                setOf(Route.EXO, Route.VLC_HARDWARE),
            ),
        )
        assertNull(
            VodPlaybackRoutingPolicy.nextRoute(
                PlayerMode.AUTO,
                DecoderMode.AUTO,
                Route.VLC_SOFTWARE,
                Failure.DECODER,
                Route.entries.toSet(),
            ),
        )
    }

    @Test
    fun `typed source and output failures map to different recovery ladders`() {
        assertEquals(
            Failure.SOURCE,
            VodPlaybackRoutingPolicy.routeFailure(
                PlaybackFailure(
                    category = PlaybackFailure.Category.NETWORK,
                    code = PlaybackFailure.Code.CONNECTION_FAILED,
                ),
            ),
        )
        assertEquals(
            Failure.VIDEO_OUTPUT,
            VodPlaybackRoutingPolicy.routeFailure(
                PlaybackFailure(
                    category = PlaybackFailure.Category.OUTPUT,
                    code = PlaybackFailure.Code.VIDEO_OUTPUT_FAILED,
                    component = PlaybackFailure.Component.VIDEO,
                ),
            ),
        )
    }

    @Test
    fun `resource failure maps software route back to a performant decoder`() {
        val mapped = VodPlaybackRoutingPolicy.routeFailure(
            PlaybackFailure(
                category = PlaybackFailure.Category.RESOURCE,
                code = PlaybackFailure.Code.RESOURCE_EXHAUSTED,
                phase = PlaybackFailure.Phase.PLAYBACK,
                component = PlaybackFailure.Component.VIDEO,
                retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_DECODER,
            ),
        )

        assertEquals(Failure.SOFTWARE_TOO_SLOW, mapped)
        assertEquals(
            Route.EXO,
            VodPlaybackRoutingPolicy.nextRoute(
                mode = PlayerMode.AUTO,
                decoderMode = DecoderMode.AUTO,
                current = Route.VLC_SOFTWARE,
                failure = mapped,
                tried = setOf(Route.VLC_SOFTWARE),
            ),
        )
        assertNull(
            VodPlaybackRoutingPolicy.nextRoute(
                mode = PlayerMode.AUTO,
                decoderMode = DecoderMode.AUTO,
                current = Route.EXO,
                failure = mapped,
                tried = setOf(Route.EXO),
            ),
        )
    }

    @Test
    fun `VLC error uses native counters for decoder and output classification`() {
        val output = VodPlaybackRoutingPolicy.classifyVlcEncounteredError(
            evidence = VodPlaybackRoutingPolicy.VlcErrorEvidence(
                playbackStarted = false,
                voutSeen = true,
                decodedVideo = 8,
                displayedPictures = 0,
                readBytes = 4096,
                videoCodec = "hvc1",
            ),
            phase = PlaybackFailure.Phase.STARTUP,
        )
        assertEquals(PlaybackFailure.Code.VIDEO_OUTPUT_FAILED, output.code)

        val hevcDecoder = VodPlaybackRoutingPolicy.classifyVlcEncounteredError(
            evidence = VodPlaybackRoutingPolicy.VlcErrorEvidence(
                playbackStarted = false,
                voutSeen = false,
                decodedVideo = 0,
                displayedPictures = 0,
                readBytes = 8192,
                videoCodec = "HEVC",
            ),
            phase = PlaybackFailure.Phase.STARTUP,
        )
        assertEquals(PlaybackFailure.Code.DECODER_RUNTIME_FAILED, hevcDecoder.code)

        val noEvidence = VodPlaybackRoutingPolicy.classifyVlcEncounteredError(
            evidence = VodPlaybackRoutingPolicy.VlcErrorEvidence(
                playbackStarted = false,
                voutSeen = false,
                decodedVideo = null,
                displayedPictures = null,
                readBytes = null,
                videoCodec = null,
            ),
            phase = PlaybackFailure.Phase.STARTUP,
        )
        assertEquals(PlaybackFailure.Code.CONNECTION_FAILED, noEvidence.code)
    }

    @Test
    fun `customer errors preserve useful HTTP and playback support codes`() {
        val range = VodPlaybackRoutingPolicy.customerError(
            PlaybackFailure(
                category = PlaybackFailure.Category.SOURCE,
                code = PlaybackFailure.Code.HTTP_CLIENT_ERROR,
                httpStatus = 416,
            ),
        )
        assertEquals(VodPlaybackRoutingPolicy.CustomerMessage.RANGE_REJECTED, range.message)
        assertEquals("HTTP-416", range.supportCode)

        val decoder = VodPlaybackRoutingPolicy.customerError(
            PlaybackFailure(
                category = PlaybackFailure.Category.DECODER,
                code = PlaybackFailure.Code.DECODER_INIT_FAILED,
                component = PlaybackFailure.Component.VIDEO,
            ),
        )
        assertEquals("VOD-DECODER", decoder.supportCode)
    }

    @Test
    fun `redirect policy accepts only HTTP to HTTPS upgrades`() {
        assertEquals(
            "https://cdn.example.test/movie.mp4",
            VodHttpRedirectPolicy.httpsUpgradeTarget(
                originalUrl = "http://panel.example.test/movie.mp4",
                status = 302,
                location = "https://cdn.example.test/movie.mp4",
            ),
        )
        assertNull(
            VodHttpRedirectPolicy.httpsUpgradeTarget(
                originalUrl = "https://panel.example.test/movie.mp4",
                status = 302,
                location = "http://cdn.example.test/movie.mp4",
            ),
        )
        assertNull(
            VodHttpRedirectPolicy.httpsUpgradeTarget(
                originalUrl = "http://panel.example.test/movie.mp4",
                status = 302,
                location = "http://cdn.example.test/movie.mp4",
            ),
        )
    }
}
