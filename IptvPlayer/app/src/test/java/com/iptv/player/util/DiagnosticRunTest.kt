package com.iptv.player.util

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRunTest {
    @Test fun `old logs excluded while current multiline cause is retained`() {
        val log = "08-01 legacy\n[run=old] old failure\n old stack\n[run=current] failure\n cause chain\n[run=other] ignore"
        assertEquals("[run=current] failure\n cause chain", DiagnosticRun.currentText(log, "[run=current]"))
        assertEquals("", DiagnosticRun.currentText("legacy only", "[run=current]"))
    }
}
