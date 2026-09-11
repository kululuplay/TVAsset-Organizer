package com.iptv.player.ui.login

/** IPTV usernames are provider identifiers; email sign-in is not supported. */
internal object LoginUsername {
    fun isValid(value: String): Boolean = value.isNotBlank() && '@' !in value
}
