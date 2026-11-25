/*
 * VoiceSearchPolicy.kt
 * Picks the text the search field receives from the system speech recogniser.
 */
package com.iptv.player.ui.search

internal object VoiceSearchPolicy {

    /**
     * Recognisers list their best guess first. Null when nothing usable came
     * back, so a cancelled or empty recognition leaves the search untouched.
     */
    fun bestResult(results: List<String?>?): String? =
        results?.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
}
