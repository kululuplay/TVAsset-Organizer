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
 * The DVB subtitle transcoding of the `TsExtractor(SubtitleParser.Factory)`
 * constructor is kept (1.11 TextRenderer rejects raw subtitle samples).
 *
 * Why [TS_MODE] stays MODE_SINGLE_PMT (1.6.3 review): it is Media3's own
 * progressive default and the right mode for the single-program streams every
 * provider remuxes. MODE_MULTI_PMT ends track discovery only once every PMT the
 * PAT lists has arrived: a transponder relay whose PAT still names ten
 * programs while one PMT passes the filter would never finish preparing (the
 * exact "loads forever in Exo, plays in VLC" symptom), and a real
 * multi-program stream could pair video and audio from different programs.
 * The remaining "declared track that never carries data" case is handled by
 * [LivePrepareStallPolicy], which hands the stream to libVLC after 8 s.
 */
@OptIn(markerClass = [UnstableApi::class])
internal object LiveTsExtractorPolicy {

    /** Single-program streams; see the class note on MODE_MULTI_PMT. */
    const val TS_MODE: Int = TsExtractor.MODE_SINGLE_PMT

    /**
     * Recovery-point I-frames count as keyframes; access units are detected
     * from NAL units; SCTE-35 splice sections never become a track of their
     * own; HDMV DTS audio (stream type 0x82) is recognised (the last two since
     * 1.6.3).
     */
    const val PAYLOAD_READER_FLAGS: Int =
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM or
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS

    /**
     * Bytes scanned for PCR/PTS when the input length is known (seekable
     * inputs only; a live chunked stream skips the scan). 1500 packets instead
     * of Media3's 600 so a sparse-PCR stream still yields a timestamp.
     */
    const val TIMESTAMP_SEARCH_BYTES: Int = 1500 * TsExtractor.TS_PACKET_SIZE
}
