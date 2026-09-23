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

    @Test fun `catch-up identity is the archived stream id and never the account, start time or duration`() {
        val a = PlaybackContentIdentity.forStream(
            "https://provider.test/streaming/timeshift.php?username=acc&password=secret&stream=123&start=2026-09-23:10-00&duration=60",
            PlaybackContentKind.CATCH_UP,
        )
        val b = PlaybackContentIdentity.forStream(
            "http://PROVIDER.test:80/streaming/timeshift.php?username=other&password=pw&stream=0123&start=2026-09-22:20-00&duration=30",
            PlaybackContentKind.CATCH_UP,
        )
        val c = PlaybackContentIdentity.forStream("https://provider.test/timeshift/acc/secret/60/2026-09-23:10-00/123.ts", PlaybackContentKind.CATCH_UP)
        assertNotNull(a)
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a, PlaybackContentIdentity.key("https://provider.test", PlaybackContentKind.CATCH_UP, "123"))
        assertNotEquals(a, PlaybackContentIdentity.forStream("https://provider.test/live/acc/secret/123.ts", PlaybackContentKind.LIVE_TV))
        assertNull(PlaybackContentIdentity.forStream(
            "https://provider.test/streaming/timeshift.php?username=acc&password=secret&start=2026-09-23:10-00", PlaybackContentKind.CATCH_UP,
        ))
        assertNull(PlaybackContentIdentity.forStream(
            "https://provider.test/streaming/timeshift.php?username=acc&password=secret&stream=acc-secret", PlaybackContentKind.CATCH_UP,
        ))
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
