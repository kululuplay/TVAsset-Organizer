package com.iptv.player.playback.android

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(markerClass = [UnstableApi::class])
class VerifiedCodecFormatTest {
    private val hd = Format.Builder().setSampleMimeType("video/avc").setCodecs("avc1.4d401f")
        .setWidth(1280).setHeight(720).setFrameRate(25f).build()

    @Test fun `codec profile resolution and frame rate changes need separate evidence`() {
        val key = VerifiedCodecAdapterFactory.formatKey(hd)
        assertNotEquals(key, VerifiedCodecAdapterFactory.formatKey(hd.buildUpon().setWidth(1920).setHeight(1080).build()))
        assertNotEquals(key, VerifiedCodecAdapterFactory.formatKey(hd.buildUpon().setFrameRate(50f).build()))
        assertNotEquals(key, VerifiedCodecAdapterFactory.formatKey(hd.buildUpon().setCodecs("avc1.64002a").build()))
    }

    @Test fun `content labels do not leak into codec keys or prevent reusable device evidence`() {
        assertEquals(VerifiedCodecAdapterFactory.formatKey(hd),
            VerifiedCodecAdapterFactory.formatKey(hd.buildUpon().setLabel("Private title").setId("private-id").build()))
        assertFalse(VerifiedCodecAdapterFactory.formatKey(hd).contains("Private"))
    }
}
