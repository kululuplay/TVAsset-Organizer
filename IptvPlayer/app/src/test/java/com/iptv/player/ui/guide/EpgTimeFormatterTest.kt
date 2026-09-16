package com.iptv.player.ui.guide

import com.iptv.player.data.model.Program
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class EpgTimeFormatterTest {

    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun rememberDefaults() {
        originalZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreDefaults() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `time renders 24h clock in the default zone`() {
        assertEquals("00:00", EpgTimeFormatter.time(0L))
        assertEquals("13:05", EpgTimeFormatter.time(13 * 3_600_000L + 5 * 60_000L))
    }

    @Test
    fun `range joins start and stop with a hyphen`() {
        val program = Program(
            epgChannelId = "ch1",
            title = "News",
            description = null,
            startMs = 20 * 3_600_000L,
            stopMs = 20 * 3_600_000L + 30 * 60_000L,
        )
        assertEquals("20:00-20:30", EpgTimeFormatter.range(program))
        assertEquals("20:00-20:30", EpgTimeFormatter.range(program.startMs, program.stopMs))
    }

    @Test
    fun `a later time zone change is picked up without restarting`() {
        val noonUtc = 12 * 3_600_000L
        assertEquals("12:00", EpgTimeFormatter.time(noonUtc))

        TimeZone.setDefault(TimeZone.getTimeZone("GMT+03:00"))
        assertEquals("15:00", EpgTimeFormatter.time(noonUtc))

        TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"))
        assertEquals("07:00", EpgTimeFormatter.time(noonUtc))
    }
}
