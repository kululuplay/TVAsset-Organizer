package com.iptv.player.ui.home

import com.iptv.player.data.model.Program
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBrowsePoliciesTest {

    private val fmt = SimpleDateFormat("HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val caption = HomeCaptionText(nextLabel = "Next", timeFmt = fmt)

    private fun program(title: String, startMin: Long, stopMin: Long) =
        Program("ch", title, null, startMin * 60_000L, stopMin * 60_000L)

    private val programs = listOf(
        program("News", 60, 90),
        program("Film", 90, 180),
    )

    @Test
    fun `now line renders the live programme window or nothing`() {
        assertEquals("01:00 - 01:30  News", caption.nowPlayingLabel(programs, now = 70 * 60_000L))
        assertEquals("", caption.nowPlayingLabel(programs, now = 200 * 60_000L))
    }

    @Test
    fun `next line is appended only when the card has no video`() {
        val now = 70 * 60_000L
        assertEquals(
            "01:00 - 01:30  News",
            caption.captionProgramLabel(programs, now, includeNext = false),
        )
        assertEquals(
            "01:00 - 01:30  News\nNext: 01:30  Film",
            caption.captionProgramLabel(programs, now, includeNext = true),
        )
        // Between programmes only the next line remains.
        assertEquals(
            "Next: 01:00  News",
            caption.captionProgramLabel(programs, now = 30 * 60_000L, includeNext = true),
        )
    }

    @Test
    fun `first-load failure blocks even when only synthetic rows exist`() {
        val flags = HomeBrowseStatePolicy.resolve(
            loading = false,
            failed = true,
            hasRealCategories = false,
            hasChannels = false,
            visibleItemCount = 2,
        )
        assertTrue(flags.blockingFailure)
        assertFalse(flags.blockingLoading)
        assertFalse(flags.empty)
    }

    @Test
    fun `cached data keeps the browser usable while refreshing or failing`() {
        val loading = HomeBrowseStatePolicy.resolve(
            loading = true,
            failed = false,
            hasRealCategories = true,
            hasChannels = false,
            visibleItemCount = 0,
        )
        assertFalse(loading.blockingLoading)
        assertFalse(loading.empty)

        val settled = HomeBrowseStatePolicy.resolve(
            loading = false,
            failed = true,
            hasRealCategories = false,
            hasChannels = true,
            visibleItemCount = 0,
        )
        assertFalse(settled.blockingFailure)
        assertTrue(settled.empty)
    }
}
