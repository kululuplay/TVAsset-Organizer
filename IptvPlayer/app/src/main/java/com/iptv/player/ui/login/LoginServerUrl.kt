package com.iptv.player.ui.login

/**
 * Xtream server URL normalisation shared by the login form and the Profiles
 * "add subscription" form so both save the same shape: trimmed, with a scheme
 * (https by default) and without a trailing slash.
 */
internal object LoginServerUrl {
    fun normalize(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        val withScheme =
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                // Store a canonical lower-case scheme; the host keeps its spelling.
                val split = trimmed.indexOf("://") + 3
                trimmed.substring(0, split).lowercase() + trimmed.substring(split)
            } else {
                "https://$trimmed"
            }
        return withScheme.trimEnd('/')
    }
}
