package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KululuEndpointTest {

    @Test
    fun `legacy Kululu endpoints migrate to canonical HTTPS`() {
        listOf(
            "http://kululu.live:8080",
            "http://kululu.live:8080/",
            "http://kululu.live",
            "HTTP://KULULU.LIVE:8080/",
        ).forEach { legacy ->
            assertEquals(
                KululuEndpoint.HTTPS_SERVER_URL,
                KululuEndpoint.migrateLegacyServerUrl(legacy),
            )
        }
    }

    @Test
    fun `unrelated provider endpoints remain unchanged`() {
        listOf(
            "https://provider.example:8080",
            "http://other.example:8080",
            "https://kululu.live/custom",
        ).forEach { endpoint ->
            assertEquals(endpoint, KululuEndpoint.migrateLegacyServerUrl(endpoint))
        }
    }

    @Test
    fun `cached Kululu stream path migrates without losing credentials or query`() {
        assertEquals(
            "https://kululu.live/live/user/pass/42.ts?token=abc",
            KululuEndpoint.migrateLegacyAssetUrl(
                "http://kululu.live:8080/live/user/pass/42.ts?token=abc",
            ),
        )
    }
}
