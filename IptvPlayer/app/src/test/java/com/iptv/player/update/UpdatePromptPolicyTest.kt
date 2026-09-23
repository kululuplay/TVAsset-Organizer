package com.iptv.player.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePromptPolicyTest {
    private val now = 1_790_000_000_000L
    private val day = UpdatePromptPolicy.REPROMPT_INTERVAL_MS

    @Test
    fun `an update nobody dismissed is announced`() {
        assertTrue(shouldPrompt(dismissal = null))
    }

    @Test
    fun `a release the device cannot run is never announced`() {
        assertFalse(shouldPrompt(deviceSdkInt = 22, dismissal = null))
        assertFalse(shouldPrompt(deviceSdkInt = 22, dismissal = dismissed("1.5.90", now - 10 * day)))
        assertTrue(shouldPrompt(deviceSdkInt = 23, dismissal = null))
    }

    @Test
    fun `later keeps the same version quiet for a day`() {
        assertFalse(shouldPrompt(dismissal = dismissed("1.5.97", now)))
        assertFalse(shouldPrompt(dismissal = dismissed("1.5.97", now - day + 1)))
        assertTrue(shouldPrompt(dismissal = dismissed("1.5.97", now - day)))
        assertTrue(shouldPrompt(dismissal = dismissed("1.5.97", now - 30 * day)))
    }

    @Test
    fun `dismissing an older version does not silence a newer one`() {
        assertTrue(shouldPrompt(dismissal = dismissed("1.5.96", now)))
    }

    @Test
    fun `a marker stamped by a wrong clock is ignored beyond a day`() {
        // Slightly ahead (clock corrected backwards by minutes): still quiet.
        assertFalse(shouldPrompt(dismissal = dismissed("1.5.97", now + day - 1)))
        // Implausibly far ahead: honouring it could silence the prompt for years.
        assertTrue(shouldPrompt(dismissal = dismissed("1.5.97", now + day)))
    }

    private fun dismissed(version: String, atMs: Long) =
        UpdatePromptPolicy.Dismissal(version, atMs)

    private fun shouldPrompt(
        deviceSdkInt: Int = 30,
        dismissal: UpdatePromptPolicy.Dismissal?,
    ) = UpdatePromptPolicy.shouldPrompt(
        info = UpdateInfo(
            versionName = "1.5.97",
            apkUrl = "https://example.test/KululuIPTV-v1.5.97.apk",
            apkSize = 1L,
            apkSha256 = null,
            releaseUrl = "https://example.test/releases/v1.5.97",
            notes = null,
            minAndroidApi = 23,
        ),
        deviceSdkInt = deviceSdkInt,
        dismissal = dismissal,
        nowMs = now,
    )
}
