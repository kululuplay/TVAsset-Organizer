/*
 * LikeEscape.kt
 * Escapes user text for SQL LIKE patterns paired with ESCAPE '\' so a typed
 * "%" or "_" matches literally instead of acting as a wildcard.
 */
package com.iptv.player.data.local

object LikeEscape {
    fun escape(raw: String): String = buildString(raw.length + 4) {
        for (c in raw) {
            if (c == '\\' || c == '%' || c == '_') append('\\')
            append(c)
        }
    }
}
