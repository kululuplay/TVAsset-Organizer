package com.iptv.player.player

import androidx.media3.common.PlaybackException
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.player.VodPlaybackRoutingPolicy.Route
import com.iptv.player.player.vod.VodPlaybackCoordinator
import com.iptv.player.playback.core.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoStuckDetectionPolicyTest {

    @Test
    fun `disabled means Media3's largest positive timeout`() {
        assertEquals(Int.MAX_VALUE, ExoStuckDetectionPolicy.DISABLED_MS)
    }

    @Test
    fun `live leaves frozen clocks to the controller and never ends on a duration guess`() {
        val live = ExoStuckDetectionPolicy.LIVE

        assertEquals(ExoStuckDetectionPolicy.DISABLED_MS, live.playingNoProgressMs)
        assertEquals(ExoStuckDetectionPolicy.DISABLED_MS, live.playingNotEndingMs)
        // media3 1.11.1 ExoPlayer.Builder defaults, kept on purpose.
        assertEquals(600_000, live.bufferingNoProgressMs)
        assertEquals(600_000, live.suppressedMs)
    }

    @Test
    fun `vod keeps the ten second no-progress detector but plays to real EOF`() {
        val vod = ExoStuckDetectionPolicy.VOD

        assertEquals(10_000, vod.playingNoProgressMs)
        assertEquals(ExoStuckDetectionPolicy.DISABLED_MS, vod.playingNotEndingMs)
        assertEquals(600_000, vod.bufferingNoProgressMs)
        assertEquals(600_000, vod.suppressedMs)
    }

    @Test
    fun `documented Media3 defaults match the 1_11_1 builder`() {
        assertEquals(10_000, ExoStuckDetectionPolicy.MEDIA3_DEFAULT_PLAYING_NO_PROGRESS_MS)
        assertEquals(60_000, ExoStuckDetectionPolicy.MEDIA3_DEFAULT_PLAYING_NOT_ENDING_MS)
        assertEquals(600_000, ExoStuckDetectionPolicy.MEDIA3_DEFAULT_BUFFERING_NO_PROGRESS_MS)
        assertEquals(600_000, ExoStuckDetectionPolicy.MEDIA3_DEFAULT_SUPPRESSED_MS)
    }

    @Test
    fun `every timeout satisfies the builder's positive contract`() {
        listOf(ExoStuckDetectionPolicy.LIVE, ExoStuckDetectionPolicy.VOD).forEach { timeouts ->
            assertTrue(timeouts.playingNoProgressMs > 0)
            assertTrue(timeouts.playingNotEndingMs > 0)
            assertTrue(timeouts.bufferingNoProgressMs > 0)
            assertTrue(timeouts.suppressedMs > 0)
        }
    }

    @Test
    fun `only ERROR_CODE_TIMEOUT is a stuck-player error`() {
        assertTrue(
            ExoStuckDetectionPolicy.isStuckPlayerError(PlaybackException.ERROR_CODE_TIMEOUT),
        )
        listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        ).forEach { errorCode ->
            assertFalse(ExoStuckDetectionPolicy.isStuckPlayerError(errorCode))
        }
    }

    @Test
    fun `vod stuck detection is a same-route stall, not decoder evidence`() {
        val failure = ExoStuckDetectionPolicy.stallFailure(PlaybackFailure.Phase.PLAYBACK)

        assertEquals(PlaybackFailure.Category.TIMEOUT, failure.category)
        assertEquals(PlaybackFailure.Code.PLAYBACK_STALL, failure.code)
        assertEquals(PlaybackFailure.Component.PLAYER, failure.component)
        assertEquals(PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE, failure.retryAdvice)
        assertEquals(PlaybackFailure.Phase.PLAYBACK, failure.phase)
        assertEquals(
            VodPlaybackRoutingPolicy.Failure.SOURCE,
            VodPlaybackRoutingPolicy.routeFailure(failure),
        )
        assertEquals(
            VodPlaybackRoutingPolicy.CustomerMessage.TIMEOUT,
            VodPlaybackRoutingPolicy.customerError(failure).message,
        )
    }

    @Test
    fun `startup-phase stuck detection keeps the same-route stall ladder`() {
        val failure = ExoStuckDetectionPolicy.stallFailure(PlaybackFailure.Phase.STARTUP)

        assertEquals(PlaybackFailure.Code.PLAYBACK_STALL, failure.code)
        assertEquals(
            VodPlaybackRoutingPolicy.Failure.SOURCE,
            VodPlaybackRoutingPolicy.routeFailure(failure),
        )
    }

    @Test
    fun `coordinator retries Media3 on the same route before any engine change`() {
        val coordinator = VodPlaybackCoordinator()
        val start = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Replace(
                VodPlaybackCoordinator.Selection(
                    playerMode = PlayerMode.AUTO,
                    decoderMode = DecoderMode.AUTO,
                    contentHint = VodPlaybackRoutingPolicy.ContentHint.MEDIA3_PREFERRED,
                ),
            ),
        ).filterIsInstance<VodPlaybackCoordinator.Action.Start>().single()
        assertEquals(Route.EXO, start.route)
        coordinator.dispatch(VodPlaybackCoordinator.Event.Ready(start.generation))

        val retry = coordinator.dispatch(
            VodPlaybackCoordinator.Event.Failed(
                start.generation,
                ExoStuckDetectionPolicy.stallFailure(PlaybackFailure.Phase.PLAYBACK),
            ),
        ).filterIsInstance<VodPlaybackCoordinator.Action.Start>().single()

        assertEquals(Route.EXO, retry.route)
        assertEquals(VodPlaybackCoordinator.StartReason.SOURCE_RETRY, retry.reason)
    }
}
