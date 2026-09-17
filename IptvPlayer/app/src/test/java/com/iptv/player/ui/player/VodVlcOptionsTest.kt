package com.iptv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodVlcOptionsTest {

    @Test
    fun `hardware path with passthrough off keeps the stereo downmix and no spdif`() {
        val options = VodVlcOptions.libVlcOptions(
            cachingMs = 4000,
            forceSoftware = false,
            allowPassthrough = false,
        )
        assertEquals(
            listOf(
                "--network-caching=4000",
                "--file-caching=4000",
                "--stats",
                "--http-reconnect",
                "--http-user-agent=KULULUPLAY",
                "--no-spdif",
                "--stereo-mode=1",
            ),
            options,
        )
    }

    @Test
    fun `software path disables hardware decoding and passthrough drops the downmix`() {
        val options = VodVlcOptions.libVlcOptions(
            cachingMs = 1500,
            forceSoftware = true,
            allowPassthrough = true,
        )
        assertTrue(options.contains("--avcodec-hw=none"))
        assertFalse(options.contains("--no-spdif"))
        assertFalse(options.contains("--stereo-mode=1"))
        // The macroblocking-prone flags must never come back (see memory note).
        assertFalse(options.any { it.startsWith("--avcodec-skiploopfilter") })
        assertFalse(options.contains("--avcodec-fast"))
    }
}
