package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

/**
 * How the live engine's [TsExtractor] reads raw MPEG-TS from IPTV providers.
 *
 * The Media3 defaults are tuned for well-formed broadcast TS. Provider streams
 * are often re-muxed: H.264 without access unit delimiters, open-GOP streams
 * whose random access points are recovery-point I-frames rather than IDR
 * frames, and 1080i PAFF field pairs. Without these flags the payload reader
 * waits for an IDR (slow channel starts, a frozen first second) and emits late
 * or merged access units (micro-stutter, A/V drift).
 *
 * MODE_SINGLE_PMT and the DVB subtitle transcoding of the
 * `TsExtractor(SubtitleParser.Factory)` constructor are kept (1.11
 * TextRenderer rejects raw subtitle samples); only the payload-reader flags
 * and the timestamp search window change.
 */
@OptIn(markerClass = [UnstableApi::class])
internal object LiveTsExtractorPolicy {

    /** Recovery-point I-frames count as keyframes; access units are detected from NAL units. */
    const val PAYLOAD_READER_FLAGS: Int =
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

    /**
     * Bytes scanned for PCR/PTS when the input length is known (seekable
     * inputs only; a live chunked stream skips the scan). 1500 packets instead
     * of Media3's 600 so a sparse-PCR stream still yields a timestamp.
     */
    const val TIMESTAMP_SEARCH_BYTES: Int = 1500 * TsExtractor.TS_PACKET_SIZE
}
