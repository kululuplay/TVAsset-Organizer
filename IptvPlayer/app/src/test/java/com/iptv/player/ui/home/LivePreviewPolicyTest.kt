package com.iptv.player.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreviewPolicyTest {

    @Test
    fun `default is on for a standard device without overrides`() {
        assertTrue(LivePreviewPolicy.enabled(userChoice = null, remote = null, compat = false))
    }

    @Test
    fun `compatibility profile turns the default off`() {
        assertFalse(LivePreviewPolicy.enabled(userChoice = null, remote = null, compat = true))
    }

    @Test
    fun `remote override moves the default in both directions`() {
        assertFalse(LivePreviewPolicy.enabled(userChoice = null, remote = false, compat = false))
        assertTrue(LivePreviewPolicy.enabled(userChoice = null, remote = true, compat = true))
    }

    @Test
    fun `explicit user choice beats remote and compat`() {
        assertTrue(LivePreviewPolicy.enabled(userChoice = true, remote = false, compat = true))
        assertFalse(LivePreviewPolicy.enabled(userChoice = false, remote = true, compat = false))
    }

    @Test
    fun `defaultEnabled mirrors the no-choice path`() {
        assertTrue(LivePreviewPolicy.defaultEnabled(remote = null, compat = false))
        assertFalse(LivePreviewPolicy.defaultEnabled(remote = null, compat = true))
        assertTrue(LivePreviewPolicy.defaultEnabled(remote = true, compat = true))
    }
}
