package com.iptv.player.player

import com.iptv.player.data.model.StreamFormat
import com.iptv.player.playback.core.PlaybackContentKind
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackTransportKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaybackQoePolicyTest {

    @Test
    fun `live TS session maps only to closed telemetry enums`() {
        val descriptor = LivePlaybackQoePolicy.sessionDescriptor(
            radio = false,
            hls = false,
        )

        assertEquals(PlaybackContentKind.LIVE_TV, descriptor.content)
        assertEquals(PlaybackTransportKind.MPEG_TS, descriptor.transport)
    }

    @Test
    fun `radio HLS session remains distinguishable without content identity`() {
        val descriptor = LivePlaybackQoePolicy.sessionDescriptor(
            radio = true,
            hls = true,
        )

        assertEquals(PlaybackContentKind.RADIO, descriptor.content)
        assertEquals(PlaybackTransportKind.HLS, descriptor.transport)
    }

    @Test
    fun `native engine labels are reduced to a closed enum`() {
        assertEquals(PlaybackEngineKind.EXO_PLAYER, LivePlaybackQoePolicy.engine("ExoPlayer"))
        assertEquals(PlaybackEngineKind.VLC, LivePlaybackQoePolicy.engine("VLC software"))
        assertEquals(PlaybackEngineKind.UNKNOWN, LivePlaybackQoePolicy.engine("future backend"))
    }

    @Test
    fun `resolved live format maps to the actual transport`() {
        assertEquals(
            PlaybackTransportKind.HLS,
            LivePlaybackQoePolicy.transport(StreamFormat.HLS),
        )
        assertEquals(
            PlaybackTransportKind.MPEG_TS,
            LivePlaybackQoePolicy.transport(StreamFormat.TS),
        )
    }
}
