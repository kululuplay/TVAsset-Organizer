package com.iptv.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNotesPolicyTest {

    @Test
    fun currentCiCommitBodyIsHidden() {
        assertNull(
            ReleaseNotesPolicy.customerFacing(
                "Automated signed build from commit 0123456789abcdef.",
            ),
        )
    }

    @Test
    fun humanNotesBecomeCompactBullets() {
        val result = ReleaseNotesPolicy.customerFacing(
            """
            ## What's changed
            - Faster channel switching
            - Improved HEVC compatibility
            """.trimIndent(),
        )

        assertEquals(
            "• Faster channel switching\n• Improved HEVC compatibility",
            result,
        )
    }

    @Test
    fun technicalReferencesAreRemovedWithoutLeakingLinksOrHashes() {
        val result = ReleaseNotesPolicy.customerFacing(
            """
            - More reliable sign-in (#42)
            - Full Changelog: https://github.com/example/compare/v1...v2
            - commit abcdef1234567890
            """.trimIndent(),
        ).orEmpty()

        assertEquals("• More reliable sign-in", result)
        assertFalse(result.contains("http"))
        assertFalse(result.contains("abcdef"))
    }
}
