package com.iptv.player.player

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTsExtractorPolicyTest {

    @Test
    fun payloadReadersAcceptNonIdrKeyframesAndDetectAccessUnits() {
        val flags = LiveTsExtractorPolicy.PAYLOAD_READER_FLAGS
        assertTrue(flags and DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES != 0)
        assertTrue(flags and DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS != 0)
        assertTrue(flags and DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM != 0)
        assertTrue(flags and DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS != 0)
        // Nothing else: audio/caption streams keep their default handling.
        assertEquals(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM or
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
            flags,
        )
        assertEquals(0, flags and DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM)
        assertEquals(0, flags and DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM)
    }

    @Test
    fun singleProgramModeIsKept() {
        // MODE_MULTI_PMT would wait for every PMT the PAT lists (filtered
        // transponder relays never finish preparing); see LiveTsExtractorPolicy.
        assertEquals(TsExtractor.MODE_SINGLE_PMT, LiveTsExtractorPolicy.TS_MODE)
    }

    @Test
    fun timestampSearchCoversFifteenHundredPackets() {
        assertEquals(1500 * 188, LiveTsExtractorPolicy.TIMESTAMP_SEARCH_BYTES)
        assertEquals(1500 * TsExtractor.TS_PACKET_SIZE, LiveTsExtractorPolicy.TIMESTAMP_SEARCH_BYTES)
        assertNotEquals(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES, LiveTsExtractorPolicy.TIMESTAMP_SEARCH_BYTES)
    }
}
