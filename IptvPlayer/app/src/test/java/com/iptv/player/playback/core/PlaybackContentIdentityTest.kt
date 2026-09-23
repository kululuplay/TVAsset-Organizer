package com.iptv.player.playback.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackContentIdentityTest {
    @Test fun `different accounts and schemes identify the same provider stream without hashing credentials`() {
        val a = PlaybackContentIdentity.forStream("https://account:secret@PROVIDER.test/live/user/pass/123.ts?token=abc", PlaybackContentKind.LIVE_TV)
        val b = PlaybackContentIdentity.forStream("http://provider.test:80/live/example/sample/123.ts?token=xyz", PlaybackContentKind.LIVE_TV)
        assertNotNull(a)
        assertEquals(a, b)
        assertTrue(Regex("[a-f0-9]{64}").matches(a!!))
        assertEquals(a, PlaybackContentIdentity.key("https://provider.test/ignored?user=other", PlaybackContentKind.LIVE_TV, "00123"))
    }

    @Test fun `unknown or credential shaped identifiers never fall back to a title or whole URL`() {
        assertNull(PlaybackContentIdentity.key("https://provider.test", PlaybackContentKind.LIVE_TV, "TR same channel"))
        assertNull(PlaybackContentIdentity.forStream("https://provider.test/customer-secret/video.ts", PlaybackContentKind.LIVE_TV))
        assertNull(PlaybackContentIdentity.forStream("rtsp://provider.test/live/user/pass/123.ts", PlaybackContentKind.LIVE_TV))
        assertNull(PlaybackContentIdentity.forStream("https://provider.test/movie/user/pass/token.ts", PlaybackContentKind.VOD_MOVIE))
    }

    @Test fun `provider content kind and each episode have separate identities`() {
        val movie = PlaybackContentIdentity.forStream("https://provider.test/movie/user/pass/123.mkv", PlaybackContentKind.VOD_MOVIE)
        val episode = PlaybackContentIdentity.forStream("https://provider.test/series/user/pass/123.mp4", PlaybackContentKind.VOD_EPISODE)
        assertNotEquals(movie, episode)
        assertNotEquals(episode, PlaybackContentIdentity.forStream("https://provider.test/series/user/pass/124.mp4", PlaybackContentKind.VOD_EPISODE))
        assertNotEquals(movie, PlaybackContentIdentity.forStream("https://other.test/movie/user/pass/123.mkv", PlaybackContentKind.VOD_MOVIE))
        assertNotEquals(movie, PlaybackContentIdentity.key("https://provider.test:8080", PlaybackContentKind.VOD_MOVIE, "123"))
    }
}
