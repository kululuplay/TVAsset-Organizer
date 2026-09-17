package com.iptv.player.player.vod

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.playback.core.FrameRateMatcher
import com.iptv.player.player.VodPlaybackRoutingPolicy.ContentHint
import org.junit.Assert.assertEquals
import org.junit.Test

class VodPlaybackCoordinatorAfrTest {

    private class FakeMatcher : FrameRateMatcher {
        val formats = mutableListOf<Triple<Float, Int, Int>>()
        var restores = 0
        override fun onVideoFormat(fps: Float, width: Int, height: Int) {
            formats += Triple(fps, width, height)
        }
        override fun restore() { restores++ }
    }

    private fun selection() = VodPlaybackCoordinator.Selection(
        playerMode = PlayerMode.AUTO,
        decoderMode = DecoderMode.AUTO,
        contentHint = ContentHint.MEDIA3_PREFERRED,
    )

    @Test
    fun `video format of the current generation reaches the matcher`() {
        val coordinator = VodPlaybackCoordinator()
        val matcher = FakeMatcher()
        coordinator.attachDisplayModeSwitcher(matcher)
        coordinator.dispatch(VodPlaybackCoordinator.Event.Replace(selection()))
        val generation = coordinator.state.generation
        coordinator.dispatch(VodPlaybackCoordinator.Event.Ready(generation))
        val actions = coordinator.dispatch(
            VodPlaybackCoordinator.Event.VideoFormat(generation, 23.976f, 1920, 1080),
        )
        assertEquals(emptyList<VodPlaybackCoordinator.Action>(), actions)
        assertEquals(listOf(Triple(23.976f, 1920, 1080)), matcher.formats)
        assertEquals(VodPlaybackCoordinator.Phase.PLAYING, coordinator.state.phase)
    }

    @Test
    fun `stale or idle video format never reaches the matcher`() {
        val coordinator = VodPlaybackCoordinator()
        val matcher = FakeMatcher()
        coordinator.attachDisplayModeSwitcher(matcher)
        coordinator.dispatch(VodPlaybackCoordinator.Event.VideoFormat(1L, 25f, 1920, 1080))
        coordinator.dispatch(VodPlaybackCoordinator.Event.Replace(selection()))
        val stale = coordinator.state.generation - 1
        coordinator.dispatch(VodPlaybackCoordinator.Event.VideoFormat(stale, 25f, 1920, 1080))
        assertEquals(emptyList<Triple<Float, Int, Int>>(), matcher.formats)
    }

    @Test
    fun `user stop restores the display mode and replace does not`() {
        val coordinator = VodPlaybackCoordinator()
        val matcher = FakeMatcher()
        coordinator.attachDisplayModeSwitcher(matcher)
        coordinator.dispatch(VodPlaybackCoordinator.Event.Replace(selection()))
        // Next episode: keep the matched mode (the matcher dedupes per family).
        coordinator.dispatch(VodPlaybackCoordinator.Event.Replace(selection()))
        assertEquals(0, matcher.restores)
        val generation = coordinator.state.generation
        coordinator.dispatch(VodPlaybackCoordinator.Event.Stop(generation - 1))
        assertEquals(0, matcher.restores)
        coordinator.dispatch(VodPlaybackCoordinator.Event.Stop(generation))
        assertEquals(1, matcher.restores)
        coordinator.releaseDisplayMode()
        assertEquals(2, matcher.restores)
    }

    @Test
    fun `swapping the matcher restores the previous one`() {
        val coordinator = VodPlaybackCoordinator()
        val first = FakeMatcher()
        coordinator.attachDisplayModeSwitcher(first)
        coordinator.attachDisplayModeSwitcher(first)
        assertEquals(0, first.restores)
        coordinator.attachDisplayModeSwitcher(null)
        assertEquals(1, first.restores)
    }
}
