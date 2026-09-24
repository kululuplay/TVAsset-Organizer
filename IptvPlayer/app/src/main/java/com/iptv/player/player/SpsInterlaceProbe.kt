package com.iptv.player.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

/**
 * Reads `frame_mbs_only_flag` from the H.264 SPS that Media3 places in
 * `Format.initializationData` (the TS reader stores the SPS NAL unit with its
 * start code; MP4-derived formats carry the bare NAL unit). A cleared flag
 * means the sequence may contain field or MBAFF pictures, i.e. interlaced
 * coding. Null when the format is not H.264 or the SPS cannot be parsed.
 */
@UnstableApi
internal object SpsInterlaceProbe {

    fun isInterlaced(sampleMimeType: String?, initializationData: List<ByteArray>): Boolean? {
        if (sampleMimeType != MimeTypes.VIDEO_H264) return null
        val sps = initializationData.firstOrNull { nalUnitType(it) == NalUnitUtil.H264_NAL_UNIT_TYPE_SPS }
            ?: return null
        return runCatching {
            !NalUnitUtil.parseSpsNalUnit(sps, nalOffset(sps), sps.size).frameMbsOnlyFlag
        }.getOrNull()
    }

    /** Index of the NAL header byte: after a 3- or 4-byte start code, else 0. */
    fun nalOffset(data: ByteArray): Int = when {
        data.size > 4 && data[0] == ZERO && data[1] == ZERO && data[2] == ZERO && data[3] == ONE -> 4
        data.size > 3 && data[0] == ZERO && data[1] == ZERO && data[2] == ONE -> 3
        else -> 0
    }

    private fun nalUnitType(data: ByteArray): Int {
        val offset = nalOffset(data)
        if (offset >= data.size) return -1
        return data[offset].toInt() and 0x1F
    }

    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
}
