package com.iptv.player.ui.login

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginServerUrlTest {

    @Test
    fun `adds https when no scheme is given`() {
        assertEquals("https://panel.example.test:8080", LoginServerUrl.normalize("panel.example.test:8080"))
    }

    @Test
    fun `keeps an explicit scheme`() {
        assertEquals("http://panel.example.test", LoginServerUrl.normalize("http://panel.example.test"))
        assertEquals("https://panel.example.test", LoginServerUrl.normalize("HTTPS://panel.example.test"))
    }

    @Test
    fun `trims surrounding whitespace and trailing slashes`() {
        assertEquals("https://panel.example.test", LoginServerUrl.normalize("  panel.example.test/  "))
        assertEquals("http://panel.example.test", LoginServerUrl.normalize("http://panel.example.test///"))
    }

    @Test
    fun `blank input stays blank so validation can reject it`() {
        assertEquals("", LoginServerUrl.normalize("   "))
    }
}
