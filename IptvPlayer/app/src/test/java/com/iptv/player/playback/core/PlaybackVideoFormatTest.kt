package com.iptv.player.playback.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackVideoFormatTest {
    @Test fun `maps MIME and native FOURCC to closed codec values`() {
        assertEquals(PlaybackVideoCodec.H264, PlaybackVideoCodec.from("video/avc"))
        assertEquals(PlaybackVideoCodec.H265, PlaybackVideoCodec.from(" HEVC "))
        assertEquals(PlaybackVideoCodec.MPEG2, PlaybackVideoCodec.from("MPGV"))
        assertEquals(PlaybackVideoCodec.AV1, PlaybackVideoCodec.from("av01"))
        assertEquals(PlaybackVideoCodec.UNKNOWN, PlaybackVideoCodec.from(null))
        assertEquals(PlaybackVideoCodec.OTHER, PlaybackVideoCodec.from("https://secret.invalid/path"))
    }

    @Test fun `unknown or invalid format numbers are omitted including nonfinite fps`() {
        val invalid = PlaybackVideoFormat(PlaybackVideoCodec.OTHER, width = -1, height = 20_000, frameRate = Float.NaN)
        assertEquals(setOf("video_codec", "video_decoder"), invalid.toSafeFields().keys)
        val valid = PlaybackVideoFormat(PlaybackVideoCodec.H265, PlaybackVideoDecoder.HARDWARE, 1920, 1080, 29.97f)
        assertEquals(1920, valid.toSafeFields()["video_width"])
        assertEquals(29.97f, valid.toSafeFields()["frame_rate"])
    }

    @Test fun `decoder change removes stale hardware and format evidence until sampled again`() {
        val recorder = PlaybackQoeRecorder()
        val id = PlaybackSessionId.random()
        recorder.start(PlaybackSession(id, PlaybackContentKind.LIVE_TV, 1_000, PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS))
        recorder.observe(id, PlaybackObservation("exo", video = PlaybackVideoFormat(
            PlaybackVideoCodec.H264, PlaybackVideoDecoder.HARDWARE, 1920, 1080, 50f,
        )))
        assertEquals("HARDWARE", recorder.snapshotActive(id)!!.toSafeFields()["video_decoder"])
        recorder.markEngine(id, PlaybackEngineKind.VLC)
        assertFalse(recorder.snapshotActive(id)!!.toSafeFields().containsKey("video_decoder"))
        recorder.observe(id, PlaybackObservation("vlc", video = PlaybackVideoFormat(
            PlaybackVideoCodec.H264, PlaybackVideoDecoder.UNKNOWN, 1920, 1080, 50f,
        )))
        assertEquals("UNKNOWN", recorder.snapshotActive(id)!!.toSafeFields()["video_decoder"])
        recorder.finish(id, PlaybackEndReason.USER_STOP, 2_000)
        assertTrue(recorder.activeSnapshot().isEmpty())
    }

    @Test fun `same VLC engine restart cannot retain stale frame buffer or decoder evidence`() {
        val recorder = PlaybackQoeRecorder()
        val id = PlaybackSessionId.random()
        recorder.start(PlaybackSession(id, PlaybackContentKind.LIVE_TV, 1_000, PlaybackEngineKind.VLC, PlaybackTransportKind.MPEG_TS))
        recorder.observe(id, PlaybackObservation("vlc1", rendered = 100, dropped = 1, bufferMs = 1_000,
            video = PlaybackVideoFormat(PlaybackVideoCodec.H264, PlaybackVideoDecoder.UNKNOWN, 1920, 1080, 50f)))
        recorder.resetOutputEvidence(id)
        val record = recorder.snapshotActive(id)!!
        assertFalse(record.framesKnown)
        assertNull(record.lastFrameAgeMs)
        assertNull(record.currentBufferMs)
        assertNull(record.video)
        assertEquals(PlaybackEngineKind.VLC, record.finalEngine)
    }
}
