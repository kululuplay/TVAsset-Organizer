package com.iptv.player.ui.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodKeyDispatchPolicyTest {

    private fun input(
        keyCode: Int,
        isDown: Boolean = true,
        repeatCount: Int = 0,
        nextEpisodeVisible: Boolean = false,
        errorVisible: Boolean = false,
        controlsVisible: Boolean = true,
        seekBarFocused: Boolean = false,
        playing: Boolean = true,
    ) = VodKeyDispatchPolicy.Input(
        keyCode = keyCode,
        isDown = isDown,
        repeatCount = repeatCount,
        nextEpisodeVisible = nextEpisodeVisible,
        errorVisible = errorVisible,
        controlsVisible = controlsVisible,
        seekBarFocused = seekBarFocused,
        playing = playing,
        skipMs = 10_000L,
    )

    @Test
    fun `back is consumed on both down and up while the next-episode prompt shows`() {
        val down = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_BACK, nextEpisodeVisible = true),
        )
        assertTrue(down.consume)
        assertEquals(VodKeyDispatchPolicy.Action.CancelNextEpisode, down.action)

        val up = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_BACK, isDown = false, nextEpisodeVisible = true),
        )
        assertTrue(up.consume)
        assertEquals(VodKeyDispatchPolicy.Action.None, up.action)
    }

    @Test
    fun `play keys mean retry behind the terminal error card and transport keys are swallowed`() {
        val play = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_MEDIA_PLAY, errorVisible = true),
        )
        assertEquals(VodKeyDispatchPolicy.Action.Retry, play.action)
        assertEquals(VodKeyDispatchPolicy.Reveal.NONE, play.reveal)

        val ff = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, errorVisible = true),
        )
        assertTrue(ff.consume)
        assertEquals(VodKeyDispatchPolicy.Action.None, ff.action)
    }

    @Test
    fun `media play pause reveals controls first and ignores auto-repeat`() {
        val first = VodKeyDispatchPolicy.decide(input(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(VodKeyDispatchPolicy.Reveal.BEFORE, first.reveal)
        assertEquals(VodKeyDispatchPolicy.Action.TogglePlayPause, first.action)

        val repeat = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeatCount = 3),
        )
        assertTrue(repeat.consume)
        assertEquals(VodKeyDispatchPolicy.Action.None, repeat.action)
    }

    @Test
    fun `dedicated play and pause keys only toggle when the state differs`() {
        assertEquals(
            VodKeyDispatchPolicy.Action.None,
            VodKeyDispatchPolicy.decide(input(KeyEvent.KEYCODE_MEDIA_PLAY, playing = true)).action,
        )
        assertEquals(
            VodKeyDispatchPolicy.Action.TogglePlayPause,
            VodKeyDispatchPolicy.decide(input(KeyEvent.KEYCODE_MEDIA_PLAY, playing = false)).action,
        )
        assertEquals(
            VodKeyDispatchPolicy.Action.TogglePlayPause,
            VodKeyDispatchPolicy.decide(input(KeyEvent.KEYCODE_MEDIA_PAUSE, playing = true)).action,
        )
    }

    @Test
    fun `left and right seek from the bar when controls are hidden or the bar is focused`() {
        val hidden = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_DPAD_LEFT, controlsVisible = false),
        )
        assertEquals(VodKeyDispatchPolicy.Action.SeekFromBar(-10_000L), hidden.action)
        assertEquals(VodKeyDispatchPolicy.Reveal.BEFORE, hidden.reveal)

        val held = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 20, seekBarFocused = true),
        )
        assertEquals(VodKeyDispatchPolicy.Action.SeekFromBar(60_000L), held.action)

        // With the controls visible and focus elsewhere the platform handles it.
        val visible = VodKeyDispatchPolicy.decide(input(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(visible.consume)
        assertEquals(VodKeyDispatchPolicy.Reveal.BEFORE, visible.reveal)
    }

    @Test
    fun `hidden controls are revealed after the key and system keys pass through silently`() {
        val center = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_DPAD_CENTER, controlsVisible = false),
        )
        assertTrue(center.consume)
        assertEquals(VodKeyDispatchPolicy.Action.None, center.action)
        assertEquals(VodKeyDispatchPolicy.Reveal.AFTER, center.reveal)

        // Overlays keep the raw 10 s shortcut instead of the seek-bar path.
        val leftBehindPrompt = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_DPAD_LEFT, controlsVisible = false, nextEpisodeVisible = true),
        )
        assertEquals(VodKeyDispatchPolicy.Action.SeekBy(-10_000L), leftBehindPrompt.action)
        assertEquals(VodKeyDispatchPolicy.Reveal.AFTER, leftBehindPrompt.reveal)

        val volume = VodKeyDispatchPolicy.decide(
            input(KeyEvent.KEYCODE_VOLUME_UP, controlsVisible = false),
        )
        assertFalse(volume.consume)
        assertEquals(VodKeyDispatchPolicy.Reveal.NONE, volume.reveal)
    }
}
