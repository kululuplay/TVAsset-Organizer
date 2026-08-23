package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLoadingOverlayPolicyTest {

    @Test
    fun `video startup waits for a verified frame`() {
        val policy = LiveLoadingOverlayPolicy()
        policy.onEngineChanged("EXO")

        assertFalse(policy.shouldHideOnPlaying("EXO", expectsVideo = true))

        policy.onVideoResumed()
        assertTrue(policy.shouldHideOnPlaying("EXO", expectsVideo = true))
    }

    @Test
    fun `normal rebuffer keeps prior surface proof`() {
        val policy = LiveLoadingOverlayPolicy()
        policy.onEngineChanged("VLC")
        policy.onVideoResumed()

        policy.onBuffering()

        assertTrue(policy.shouldHideOnPlaying("VLC", expectsVideo = true))
    }

    @Test
    fun `restart on same engine requires a fresh frame`() {
        val policy = LiveLoadingOverlayPolicy()
        policy.onEngineChanged("VLC")
        policy.onVideoResumed()

        policy.requireFreshFrame()

        assertFalse(policy.shouldHideOnPlaying("VLC", expectsVideo = true))
        policy.onVideoResumed()
        assertTrue(policy.shouldHideOnPlaying("VLC", expectsVideo = true))
    }

    @Test
    fun `engine change invalidates old surface and rejects stale playing callback`() {
        val policy = LiveLoadingOverlayPolicy()
        policy.onEngineChanged("EXO")
        policy.onVideoResumed()

        policy.onEngineChanged("VLC")

        assertFalse(policy.shouldHideOnPlaying("EXO", expectsVideo = true))
        assertFalse(policy.shouldHideOnPlaying("VLC", expectsVideo = true))
        policy.onVideoResumed()
        assertTrue(policy.shouldHideOnPlaying("VLC", expectsVideo = true))
    }

    @Test
    fun `audio only playback can become ready without a video frame`() {
        val policy = LiveLoadingOverlayPolicy()
        policy.onEngineChanged("EXO")

        assertTrue(policy.shouldHideOnPlaying("EXO", expectsVideo = false))
    }
}
