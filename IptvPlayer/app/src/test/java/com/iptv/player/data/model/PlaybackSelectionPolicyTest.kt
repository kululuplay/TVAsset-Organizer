package com.iptv.player.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSelectionPolicyTest {

    @Test
    fun `valid explicit selections survive normalization`() {
        val valid = listOf(
            PlaybackSelection(PlayerMode.AUTO, DecoderMode.AUTO),
            PlaybackSelection(PlayerMode.AUTO, DecoderMode.HARDWARE),
            PlaybackSelection(PlayerMode.AUTO, DecoderMode.SOFTWARE),
            PlaybackSelection(PlayerMode.EXOPLAYER, DecoderMode.AUTO),
            PlaybackSelection(PlayerMode.EXOPLAYER, DecoderMode.HARDWARE),
            PlaybackSelection(PlayerMode.VLC, DecoderMode.AUTO),
            PlaybackSelection(PlayerMode.VLC, DecoderMode.HARDWARE),
            PlaybackSelection(PlayerMode.VLC, DecoderMode.SOFTWARE),
        )

        valid.forEach { selection ->
            assertEquals(
                selection,
                PlaybackSelectionPolicy.normalize(selection.player, selection.decoder),
            )
        }
    }

    @Test
    fun `legacy Exo software pair has one idempotent canonical result`() {
        val canonical = PlaybackSelectionPolicy.normalize(
            PlayerMode.EXOPLAYER,
            DecoderMode.SOFTWARE,
        )

        assertEquals(
            PlaybackSelection(PlayerMode.EXOPLAYER, DecoderMode.HARDWARE),
            canonical,
        )
        assertEquals(
            canonical,
            PlaybackSelectionPolicy.normalize(canonical.player, canonical.decoder),
        )
    }

    @Test
    fun `missing or unknown persisted values migrate to current defaults`() {
        assertEquals(
            PlaybackSelection(PlayerMode.AUTO, DecoderMode.AUTO),
            PlaybackSelectionPolicy.normalize(
                PlayerMode.fromName(null),
                DecoderMode.fromName(null),
            ),
        )
        assertEquals(
            PlaybackSelection(PlayerMode.AUTO, DecoderMode.AUTO),
            PlaybackSelectionPolicy.normalize(
                PlayerMode.fromName("OLD_ENGINE"),
                DecoderMode.fromName("OLD_DECODER"),
            ),
        )
    }

    @Test
    fun `explicit persisted choices are preserved by migration normalization`() {
        assertEquals(
            PlaybackSelection(PlayerMode.VLC, DecoderMode.SOFTWARE),
            PlaybackSelectionPolicy.normalize(
                PlayerMode.fromName(PlayerMode.VLC.name),
                DecoderMode.fromName(DecoderMode.SOFTWARE.name),
            ),
        )
    }

    @Test
    fun `rapid engine then decoder intents apply in click order without lost axes`() {
        var selection = PlaybackSelection(PlayerMode.AUTO, DecoderMode.AUTO)

        selection = PlaybackSelectionPolicy.withPlayer(selection, PlayerMode.EXOPLAYER)
        selection = PlaybackSelectionPolicy.withDecoder(selection, DecoderMode.SOFTWARE)

        assertEquals(
            PlaybackSelection(PlayerMode.VLC, DecoderMode.SOFTWARE),
            selection,
        )
    }

    @Test
    fun `rapid decoder then engine intents apply in click order without lost axes`() {
        var selection = PlaybackSelection(PlayerMode.AUTO, DecoderMode.AUTO)

        selection = PlaybackSelectionPolicy.withDecoder(selection, DecoderMode.SOFTWARE)
        selection = PlaybackSelectionPolicy.withPlayer(selection, PlayerMode.EXOPLAYER)

        assertEquals(
            PlaybackSelection(PlayerMode.EXOPLAYER, DecoderMode.HARDWARE),
            selection,
        )
    }
}
