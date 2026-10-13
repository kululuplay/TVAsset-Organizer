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
        // Nothing else: audio/caption/splice streams keep their default handling.
        assertEquals(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            flags,
        )
        assertEquals(0, flags and DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM)
        assertEquals(0, flags and DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM)
    }

    @Test
    fun timestampSearchCoversFifteenHundredPackets() {
        assertEquals(1500 * 188, LiveTsExtractorPolicy.TIMESTAMP_SEARCH_BYTES)
        assertEquals(1500 * TsExtractor.TS_PACKET_SIZE, LiveTsExtractorPolicy.TIMESTAMP_SEARCH_BYTES)
        assertNotEquals(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES, LiveTsExtractorPolicy.TIMESTAMP_SEARCH_BYTES)
    }
}
