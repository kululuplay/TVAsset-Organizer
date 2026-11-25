package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramReminderPolicyTest {

    private fun reminder(startMs: Long, channel: String = "trt1") =
        ProgramReminder(channel, "TRT 1", "News", startMs)

    @Test
    fun `a second press on the same programme removes its reminder`() {
        val once = ProgramReminderPolicy.toggle(emptyList(), reminder(1_000_000L))
        assertEquals(1, once.size)
        assertTrue(ProgramReminderPolicy.toggle(once, reminder(1_000_000L)).isEmpty())
        // Same start time on another channel is another programme.
        assertEquals(2, ProgramReminderPolicy.toggle(once, reminder(1_000_000L, "atv")).size)
    }

    @Test
    fun `a reminder is due from one minute before the start and dropped when missed`() {
        val start = 100L * 60_000L
        val list = listOf(reminder(start))
        val early = start - ProgramReminderPolicy.LEAD_MS - 1
        assertTrue(ProgramReminderPolicy.due(list, early).isEmpty())
        assertEquals(list, ProgramReminderPolicy.pending(list, early))

        val atLead = start - ProgramReminderPolicy.LEAD_MS
        assertEquals(list, ProgramReminderPolicy.due(list, atLead))
        // Told once: it leaves the stored list in the same check.
        assertTrue(ProgramReminderPolicy.pending(list, atLead).isEmpty())

        // App was closed over the start: neither announced nor kept.
        val late = start + ProgramReminderPolicy.LATE_MS + 1
        assertTrue(ProgramReminderPolicy.due(list, late).isEmpty())
        assertTrue(ProgramReminderPolicy.pending(list, late).isEmpty())
    }

    @Test
    fun `the list is capped and keeps the latest starts`() {
        var list = emptyList<ProgramReminder>()
        repeat(ProgramReminderPolicy.MAX_REMINDERS + 5) { list = ProgramReminderPolicy.toggle(list, reminder(it * 1_000L)) }
        assertEquals(ProgramReminderPolicy.MAX_REMINDERS, list.size)
        assertEquals((ProgramReminderPolicy.MAX_REMINDERS + 4) * 1_000L, list.last().startMs)
    }
}
