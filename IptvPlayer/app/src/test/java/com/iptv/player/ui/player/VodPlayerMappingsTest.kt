package com.iptv.player.ui.player

import com.iptv.player.R
import com.iptv.player.player.VodPlaybackRoutingPolicy
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackTransportKind
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class VodPlayerMappingsTest {

    @Test
    fun `transport is classified from the path, ignoring query and fragment`() {
        assertEquals(PlaybackTransportKind.DASH, VodQoeMapping.transport("http://h/a/b.MPD?token=1"))
        assertEquals(PlaybackTransportKind.MPEG_TS, VodQoeMapping.transport("http://h/live/1.ts#x"))
        assertEquals(PlaybackTransportKind.PROGRESSIVE, VodQoeMapping.transport("http://h/movie/1.mkv"))
        assertEquals(PlaybackTransportKind.UNKNOWN, VodQoeMapping.transport(null))
        assertEquals(PlaybackTransportKind.UNKNOWN, VodQoeMapping.transport("?only=query"))
    }

    @Test
    fun `engine kind follows the route`() {
        assertEquals(PlaybackEngineKind.EXO_PLAYER, VodQoeMapping.engine(VodPlaybackRoutingPolicy.Route.EXO))
        assertEquals(PlaybackEngineKind.VLC, VodQoeMapping.engine(VodPlaybackRoutingPolicy.Route.VLC_HARDWARE))
        assertEquals(PlaybackEngineKind.VLC, VodQoeMapping.engine(VodPlaybackRoutingPolicy.Route.VLC_SOFTWARE))
    }

    @Test
    fun `time label drops the hour field under one hour and never goes negative`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("00:00", VodTimeFormat.format(-5L))
            assertEquals("00:00", VodTimeFormat.format(0L))
            assertEquals("05:07", VodTimeFormat.format(5 * 60_000L + 7_000L))
            assertEquals("1:02:03", VodTimeFormat.format(3_723_000L))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `customer error messages map one to one`() {
        val resources = VodPlaybackRoutingPolicy.CustomerMessage.values()
            .map(VodErrorMessages::messageRes)
        assertEquals(resources.size, resources.toSet().size)
        assertEquals(
            R.string.error_cannot_play_content,
            VodErrorMessages.messageRes(VodPlaybackRoutingPolicy.CustomerMessage.GENERIC),
        )
        assertEquals(
            R.string.vod_error_range_rejected,
            VodErrorMessages.messageRes(VodPlaybackRoutingPolicy.CustomerMessage.RANGE_REJECTED),
        )
    }
}
