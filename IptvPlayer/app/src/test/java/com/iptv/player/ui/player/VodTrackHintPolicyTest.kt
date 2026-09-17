package com.iptv.player.ui.player

import com.iptv.player.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodTrackHintPolicyTest {

    @Test
    fun `single audio track without subtitles shows no hint`() {
        assertNull(VodTrackHintPolicy.pick(audioCount = 1, subtitleCount = 0))
        assertNull(VodTrackHintPolicy.pick(audioCount = 0, subtitleCount = 0))
    }

    @Test
    fun `hint wording follows what the content offers`() {
        assertEquals(
            VodTrackHintPolicy.Hint(R.drawable.ic_audiotrack, R.string.track_hint_audio),
            VodTrackHintPolicy.pick(audioCount = 2, subtitleCount = 0),
        )
        assertEquals(
            VodTrackHintPolicy.Hint(R.drawable.ic_subtitles, R.string.track_hint_subtitle),
            VodTrackHintPolicy.pick(audioCount = 1, subtitleCount = 1),
        )
        assertEquals(
            VodTrackHintPolicy.Hint(R.drawable.ic_subtitles, R.string.track_hint_audio_subtitle),
            VodTrackHintPolicy.pick(audioCount = 3, subtitleCount = 2),
        )
    }
}
