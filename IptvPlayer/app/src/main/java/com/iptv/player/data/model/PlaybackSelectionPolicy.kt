package com.iptv.player.data.model

/**
 * Canonicalises the two playback-setting axes and applies one-axis UI changes.
 *
 * ExoPlayer is MediaCodec-backed and therefore cannot honour VLC software
 * decoding. A raw/legacy EXOPLAYER + SOFTWARE pair has one canonical stored
 * representation: EXOPLAYER + HARDWARE. When the user explicitly changes the
 * decoder axis to SOFTWARE, the decoder choice wins and the engine becomes VLC.
 */
object PlaybackSelectionPolicy {

    fun normalize(player: PlayerMode, decoder: DecoderMode): PlaybackSelection =
        if (player == PlayerMode.EXOPLAYER && decoder == DecoderMode.SOFTWARE) {
            PlaybackSelection(PlayerMode.EXOPLAYER, DecoderMode.HARDWARE)
        } else {
            PlaybackSelection(player, decoder)
        }

    /** Apply a user change to the engine axis while preserving the decoder when valid. */
    fun withPlayer(current: PlaybackSelection, player: PlayerMode): PlaybackSelection =
        normalize(player, current.decoder)

    /**
     * Apply a user change to the decoder axis. Software decoding is provided by VLC,
     * so an explicit software choice moves an ExoPlayer selection to VLC atomically.
     */
    fun withDecoder(current: PlaybackSelection, decoder: DecoderMode): PlaybackSelection =
        if (current.player == PlayerMode.EXOPLAYER && decoder == DecoderMode.SOFTWARE) {
            PlaybackSelection(PlayerMode.VLC, DecoderMode.SOFTWARE)
        } else {
            normalize(current.player, decoder)
        }
}
