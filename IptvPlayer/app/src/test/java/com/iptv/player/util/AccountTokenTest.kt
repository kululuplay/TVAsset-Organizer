package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTokenTest {

    @Test
    fun `same username yields the same opaque token`() {
        val token = AccountToken.of("alice")

        assertEquals(token, AccountToken.of(" alice "))
        assertTrue(token!!.matches(Regex("[0-9a-f]{64}")))
        assertFalse(token.contains("alice"))
    }

    @Test
    fun `different usernames yield different tokens`() {
        assertNotEquals(AccountToken.of("alice"), AccountToken.of("bob"))
        assertNotEquals(AccountToken.of("alice"), AccountToken.of("Alice"))
    }

    @Test
    fun `blank username has no token`() {
        assertNull(AccountToken.of(null))
        assertNull(AccountToken.of("   "))
    }
}
