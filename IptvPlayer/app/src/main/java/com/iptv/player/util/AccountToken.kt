/*
 * AccountToken.kt
 * Opaque, stable token for the portal username the heartbeat reports. The ops
 * panel's account-sharing alert only needs EQUALITY across devices ("these two
 * boxes use the same login"), so the clear-text username never has to leave the
 * device: a salted SHA-256 gives the same token for the same username on every
 * device while the server side sees nothing it could log in with.
 */
package com.iptv.player.util

import java.security.MessageDigest

object AccountToken {

    /**
     * App-wide constant salt (not per device: the token must match across boxes
     * that share one login). It only stops a plain rainbow-table lookup of the
     * SHA-256 of common usernames on the receiver side.
     */
    private const val SALT = "kululu-heartbeat-account-v1"

    /** Lowercase hex SHA-256 of the salted username, or null for a blank name. */
    fun of(username: String?): String? {
        val normalized = username?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$SALT:$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
