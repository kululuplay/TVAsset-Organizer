package com.iptv.player.player.vod

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodRouteKeyTest {

    @Test
    fun `key is deterministic and exposes no url credential profile or content data`() {
        val profileId = 987_654_321L
        val contentId = "ep_private-customer-title-42"
        val streamUrl =
            "https://alice:super-secret@portal.example.test/live/user/pass/movie.m3u8" +
                "?token=private-token"

        val first = create(profileId, contentId, streamUrl)
        val second = create(profileId, contentId, streamUrl)

        assertEquals(first, second)
        assertTrue(first.matches(Regex("vod-route-v1\\|AUTO\\|AUTO\\|[0-9a-f]{24}")))
        listOf(
            profileId.toString(),
            contentId,
            streamUrl,
            "alice",
            "super-secret",
            "portal.example.test",
            "user",
            "pass",
            "private-token",
        ).forEach { sensitive ->
            assertFalse(first.contains(sensitive))
        }
    }

    @Test
    fun `credentials path query and host case do not perturb the same route namespace`() {
        val first = create(
            profileId = 7L,
            contentId = "vod_9",
            streamUrl = "https://one:secret@PORTAL.EXAMPLE.test/a/b.mp4?token=one",
        )
        val second = create(
            profileId = 7L,
            contentId = "vod_9",
            streamUrl = "https://two:changed@portal.example.test/other/movie.mkv?token=two",
        )

        assertEquals(first, second)
    }

    @Test
    fun `profile content host and playback policy remain isolated`() {
        val base = create(11L, "ep_3", "https://vod-a.example.test/movie.mp4")

        assertNotEquals(base, create(12L, "ep_3", "https://vod-a.example.test/movie.mp4"))
        assertNotEquals(base, create(11L, "ep_4", "https://vod-a.example.test/movie.mp4"))
        assertNotEquals(base, create(11L, "ep_3", "https://vod-b.example.test/movie.mp4"))
        assertNotEquals(
            base,
            VodRouteKey.create(
                activeProfileId = 11L,
                contentId = "ep_3",
                streamUrl = "https://vod-a.example.test/movie.mp4",
                playerMode = PlayerMode.VLC,
                decoderMode = DecoderMode.SOFTWARE,
            ),
        )
    }

    @Test
    fun `missing content or invalid host cannot create a route key`() {
        assertNull(
            VodRouteKey.create(
                activeProfileId = 1L,
                contentId = " ",
                streamUrl = "https://vod.example.test/movie.mp4",
                playerMode = PlayerMode.AUTO,
                decoderMode = DecoderMode.AUTO,
            ),
        )
        assertNull(
            VodRouteKey.create(
                activeProfileId = 1L,
                contentId = "vod_1",
                streamUrl = "not-a-url",
                playerMode = PlayerMode.AUTO,
                decoderMode = DecoderMode.AUTO,
            ),
        )
    }

    private fun create(
        profileId: Long,
        contentId: String,
        streamUrl: String,
    ): String = requireNotNull(
        VodRouteKey.create(
            activeProfileId = profileId,
            contentId = contentId,
            streamUrl = streamUrl,
            playerMode = PlayerMode.AUTO,
            decoderMode = DecoderMode.AUTO,
        ),
    )
}
