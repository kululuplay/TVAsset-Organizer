package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoVerdictTelemetryTest {

    @Test
    fun `decoder names map to a closed family`() {
        assertEquals(DecoderFamily.AMLOGIC, VideoVerdictTelemetry.familyOf("OMX.amlogic.avc.decoder.awesome"))
        assertEquals(DecoderFamily.AMLOGIC, VideoVerdictTelemetry.familyOf("c2.amlogic.avc.decoder"))
        assertEquals(DecoderFamily.SOFTWARE, VideoVerdictTelemetry.familyOf("c2.android.avc.decoder"))
        assertEquals(DecoderFamily.SOFTWARE, VideoVerdictTelemetry.familyOf("OMX.google.h264.decoder"))
        assertEquals(DecoderFamily.OTHER, VideoVerdictTelemetry.familyOf("OMX.MTK.VIDEO.DECODER.AVC"))
        assertEquals(DecoderFamily.UNKNOWN, VideoVerdictTelemetry.familyOf(null))
        assertEquals(DecoderFamily.UNKNOWN, VideoVerdictTelemetry.familyOf("  "))
    }

    @Test
    fun `suffix renders every field`() {
        val verdict = VideoVerdict(
            check = VideoVerdict.Check.VALIDATION_DEADLINE,
            decoderFamily = DecoderFamily.AMLOGIC,
            interlaced = true,
            logEvidence = "frames=240 last1s=25 sinceFirstFrameMs=9000 px=0/12/0/0",
        )
        assertEquals(
            " check=VALIDATION_DEADLINE fam=AMLOGIC il=1 surf=FULLSCREEN",
            VideoVerdictTelemetry.suffix(verdict, inlinePreview = false),
        )
        assertEquals(
            " check=SOLID_GREEN fam=OTHER il=0 surf=INLINE_PREVIEW",
            VideoVerdictTelemetry.suffix(
                VideoVerdict(VideoVerdict.Check.SOLID_GREEN, DecoderFamily.OTHER, interlaced = false),
                inlinePreview = true,
            ),
        )
    }

    @Test
    fun `VLC defaults render as unspecified`() {
        assertEquals(
            " check=UNSPECIFIED fam=UNKNOWN il=? surf=FULLSCREEN",
            VideoVerdictTelemetry.suffix(VideoVerdict.UNSPECIFIED, inlinePreview = false),
        )
        assertEquals(" proof=UNSPECIFIED", VideoVerdictTelemetry.proofSuffix(VideoProof.UNSPECIFIED))
        assertEquals(" proof=NATIVE_ADVISORY", VideoVerdictTelemetry.proofSuffix(VideoProof.NATIVE_ADVISORY))
    }

    @Test
    fun `an oversized base detail is clipped so the suffix survives within 300`() {
        val suffix = VideoVerdictTelemetry.suffix(
            VideoVerdict(VideoVerdict.Check.INTERLACED_FAIL_FAST),
            inlinePreview = true,
        )
        val detail = VideoVerdictTelemetry.append("x".repeat(400), suffix)
        assertEquals(VideoVerdictTelemetry.MAX_DETAIL, detail.length)
        assertTrue(detail.endsWith(suffix))
        assertEquals("stage=EXO$suffix", VideoVerdictTelemetry.append("stage=EXO", suffix))
    }

    @Test
    fun `telemetry never carries the decoder name, a URL or a path`() {
        val decoder = "OMX.amlogic.avc.decoder.awesome"
        val verdict = VideoVerdict(
            check = VideoVerdict.Check.FRAME_STALL,
            decoderFamily = VideoVerdictTelemetry.familyOf(decoder),
            interlaced = null,
            logEvidence = "frames=5 last1s=0 sinceFirstFrameMs=7200 px=0/0/0/0",
        )
        val suffix = VideoVerdictTelemetry.suffix(verdict, inlinePreview = false)
        assertFalse(suffix.contains(decoder, ignoreCase = true))
        assertFalse(suffix.contains("omx", ignoreCase = true))
        assertFalse(suffix.contains("://"))
        assertFalse(suffix.contains('/'))
        // Local log evidence is not part of the telemetry suffix.
        assertFalse(suffix.contains("frames="))
    }
}
