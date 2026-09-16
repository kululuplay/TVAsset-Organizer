package com.iptv.player.ui.player

import com.iptv.player.ui.player.VodModalTransportPolicy.Action
import com.iptv.player.ui.player.VodModalTransportPolicy.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

class VodModalTransportPolicyTest {

    private fun resolve(transport: Transport, prompt: Boolean = false, error: Boolean = false) =
        VodModalTransportPolicy.resolve(
            transport = transport,
            nextEpisodePromptShowing = prompt,
            errorCardShowing = error,
        )

    @Test
    fun `no modal card passes every transport through`() {
        Transport.values().forEach { transport ->
            assertEquals(transport.name, Action.PASS_THROUGH, resolve(transport))
        }
    }

    @Test
    fun `next episode prompt turns play into play-next and swallows the rest`() {
        assertEquals(Action.PLAY_NEXT_EPISODE, resolve(Transport.PLAY, prompt = true))
        assertEquals(Action.PLAY_NEXT_EPISODE, resolve(Transport.TOGGLE, prompt = true))
        assertEquals(Action.IGNORE, resolve(Transport.PAUSE, prompt = true))
        assertEquals(Action.IGNORE, resolve(Transport.SEEK, prompt = true))
    }

    @Test
    fun `error card turns play into retry and swallows the rest`() {
        assertEquals(Action.RETRY, resolve(Transport.PLAY, error = true))
        assertEquals(Action.RETRY, resolve(Transport.TOGGLE, error = true))
        assertEquals(Action.IGNORE, resolve(Transport.PAUSE, error = true))
        assertEquals(Action.IGNORE, resolve(Transport.SEEK, error = true))
    }

    @Test
    fun `next episode prompt wins over a stale error card`() {
        assertEquals(
            Action.PLAY_NEXT_EPISODE,
            resolve(Transport.PLAY, prompt = true, error = true),
        )
        assertEquals(Action.IGNORE, resolve(Transport.SEEK, prompt = true, error = true))
    }
}
