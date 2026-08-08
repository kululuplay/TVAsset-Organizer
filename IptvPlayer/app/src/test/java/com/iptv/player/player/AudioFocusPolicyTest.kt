package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusPolicyTest {

    @Test
    fun `transient loss pauses and resumes only when playback was active`() {
        assertEquals(
            AudioFocusPolicy.Action.PAUSE_AND_RESUME_ON_GAIN,
            AudioFocusPolicy.actionFor(AudioFocusPolicy.Change.LOSS_TRANSIENT, true),
        )
        assertEquals(
            AudioFocusPolicy.Action.NONE,
            AudioFocusPolicy.actionFor(AudioFocusPolicy.Change.LOSS_TRANSIENT, false),
        )
    }

    @Test
    fun `duck request pauses video instead of mixing speech over it`() {
        assertEquals(
            AudioFocusPolicy.Action.PAUSE_AND_RESUME_ON_GAIN,
            AudioFocusPolicy.actionFor(AudioFocusPolicy.Change.LOSS_TRANSIENT_CAN_DUCK, true),
        )
    }

    @Test
    fun `permanent loss never arms automatic resume`() {
        assertEquals(
            AudioFocusPolicy.Action.PAUSE,
            AudioFocusPolicy.actionFor(AudioFocusPolicy.Change.LOSS, true),
        )
    }

    @Test
    fun `gain is a resumable event`() {
        assertEquals(
            AudioFocusPolicy.Action.RESUME,
            AudioFocusPolicy.actionFor(AudioFocusPolicy.Change.GAIN, false),
        )
    }
}
