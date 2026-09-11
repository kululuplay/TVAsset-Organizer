package com.iptv.player.ui.login

import org.junit.Assert.*
import org.junit.Test

class LoginUsernameTest {
    @Test fun `provider usernames remain valid without changing their case or symbols`() {
        assertTrue(LoginUsername.isValid("AbC_27-xy"))
        assertTrue(LoginUsername.isValid("username.123"))
    }
    @Test fun `email and blank input cannot be submitted as username`() {
        assertFalse(LoginUsername.isValid("viewer@example.test"))
        assertFalse(LoginUsername.isValid("viewer@"))
        assertFalse(LoginUsername.isValid("   "))
    }
}
