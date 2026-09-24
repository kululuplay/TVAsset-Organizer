package com.iptv.player.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpsInterlaceProbeTest {

    @Test
    fun `nal offset skips a 3- or 4-byte start code and nothing else`() {
        assertEquals(3, SpsInterlaceProbe.nalOffset(byteArrayOf(0, 0, 1, 0x67, 0x64)))
        assertEquals(4, SpsInterlaceProbe.nalOffset(byteArrayOf(0, 0, 0, 1, 0x67, 0x64)))
        assertEquals(0, SpsInterlaceProbe.nalOffset(byteArrayOf(0x67, 0x64, 0, 0)))
        assertEquals(0, SpsInterlaceProbe.nalOffset(byteArrayOf()))
    }

    @Test
    fun `non-H264 formats and unparsable or missing SPS give no verdict`() {
        assertNull(SpsInterlaceProbe.isInterlaced(MimeTypes.VIDEO_H265, listOf(byteArrayOf(0, 0, 1, 0x67, 0x64))))
        assertNull(SpsInterlaceProbe.isInterlaced(MimeTypes.VIDEO_H264, emptyList()))
        // PPS only (type 8): no SPS to read.
        assertNull(SpsInterlaceProbe.isInterlaced(MimeTypes.VIDEO_H264, listOf(byteArrayOf(0, 0, 1, 0x68, 0x00))))
        // SPS header with a truncated body must not throw.
        assertNull(SpsInterlaceProbe.isInterlaced(MimeTypes.VIDEO_H264, listOf(byteArrayOf(0, 0, 1, 0x67))))
    }
}
