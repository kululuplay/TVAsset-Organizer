package com.iptv.player.player

import com.iptv.player.playback.core.PlaybackVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamFingerprintTest {

    @Test
    fun `key folds codec resolution frame rate interlace and audio`() {
        val fp = StreamFingerprint.from(
            videoMime = "video/avc",
            width = 1920,
            height = 1080,
            fps = 50f,
            interlaced = true,
            audioMime = "audio/eac3",
        )
        assertEquals("H264|1920|50i|eac3", fp?.key)
        assertEquals(PlaybackVideoCodec.H264, fp?.videoCodec)
    }

    @Test
    fun `media3 and libVLC spellings share a fingerprint`() {
        val exo = StreamFingerprint.from("video/hevc", 1920, 1080, 25f, false, "audio/mp4a-latm")
        val vlc = StreamFingerprint.from("hevc", 1920, 1080, 25f, null, "mp4a")
        assertEquals(exo, vlc)
        assertEquals("H265|1920|25|aac", exo?.key)
    }

    @Test
    fun `unknown codec or dimensions yield no fingerprint`() {
        assertNull(StreamFingerprint.from(null, 1920, 1080, 25f, false, "audio/ac3"))
        assertNull(StreamFingerprint.from("video/avc", 0, 0, 25f, false, "audio/ac3"))
    }

    @Test
    fun `resolution buckets follow the height class`() {
        assertEquals(720, StreamFingerprint.widthBucket(720, 576))
        assertEquals(720, StreamFingerprint.widthBucket(544, 576))
        assertEquals(1280, StreamFingerprint.widthBucket(1280, 720))
        assertEquals(1920, StreamFingerprint.widthBucket(1920, 1080))
        assertEquals(1920, StreamFingerprint.widthBucket(1440, 1080))
        assertEquals(2560, StreamFingerprint.widthBucket(2560, 1440))
        assertEquals(3840, StreamFingerprint.widthBucket(3840, 2160))
        assertEquals(0, StreamFingerprint.widthBucket(0, 0))
    }

    @Test
    fun `frame rate families absorb broadcast fractions`() {
        assertEquals(0, StreamFingerprint.frameRateFamily(0f))
        assertEquals(0, StreamFingerprint.frameRateFamily(Float.NaN))
        assertEquals(24, StreamFingerprint.frameRateFamily(23.976f))
        assertEquals(25, StreamFingerprint.frameRateFamily(25f))
        assertEquals(30, StreamFingerprint.frameRateFamily(29.97f))
        assertEquals(50, StreamFingerprint.frameRateFamily(50f))
        assertEquals(60, StreamFingerprint.frameRateFamily(59.94f))
        assertEquals(100, StreamFingerprint.frameRateFamily(100f))
    }

    @Test
    fun `audio families cover the live TS codecs of both engines`() {
        assertEquals("aac", StreamFingerprint.audioFamily("audio/mp4a-latm"))
        assertEquals("aac", StreamFingerprint.audioFamily("mp4a"))
        assertEquals("ac3", StreamFingerprint.audioFamily("audio/ac3"))
        assertEquals("ac3", StreamFingerprint.audioFamily("a52"))
        assertEquals("eac3", StreamFingerprint.audioFamily("audio/eac3"))
        assertEquals("eac3", StreamFingerprint.audioFamily("eac3"))
        assertEquals("mp2", StreamFingerprint.audioFamily("audio/mpeg-L2"))
        assertEquals("mp2", StreamFingerprint.audioFamily("mpga"))
        assertEquals("mp3", StreamFingerprint.audioFamily("audio/mpeg"))
        assertEquals("dts", StreamFingerprint.audioFamily("audio/vnd.dts"))
        assertEquals("opus", StreamFingerprint.audioFamily("audio/opus"))
        assertEquals("?", StreamFingerprint.audioFamily(null))
        assertEquals("?", StreamFingerprint.audioFamily(" "))
    }
}
