package com.iptv.player.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportUploaderTest {

    @Test
    fun `support details redact urls and credentials`() {
        val safe = SupportReportUploader.sanitize(
            "https://host.example.test/asset.ts?token=secret username=john password=doe",
        )

        assertFalse(safe.contains("https://"))
        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("john"))
        assertFalse(safe.contains("doe"))
        assertTrue(safe.contains("•••"))
    }
}
